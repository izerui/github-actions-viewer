package com.github.ghactions.poll

import com.github.ghactions.api.EtagCache
import com.github.ghactions.api.GitHubActionsClient
import com.github.ghactions.api.HttpResponse
import com.github.ghactions.api.HttpTransport
import com.github.ghactions.auth.CommandOutput
import com.github.ghactions.auth.CommandRunner
import com.github.ghactions.auth.GhCliTokenProvider
import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.model.WorkflowNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class WorkspaceStateAggregatorTest {
    private val first = RepoCoordinates("one", "repo")
    private val second = RepoCoordinates("two", "repo")

    @Test
    fun `仓库短暂失败时保留最后成功数据并显示错误`() {
        val firstWorkflow = WorkflowNode("CI", emptyList())
        val secondWorkflow = WorkflowNode("Release", emptyList())
        val firstLoaded = ViewState.Loaded(listOf(firstWorkflow), Instant.ofEpochSecond(10), degraded = false)
        val secondLoaded = ViewState.Loaded(listOf(secondWorkflow), Instant.ofEpochSecond(20), degraded = true)

        val result =
            aggregateWorkspaceState(
                coordinates = listOf(first, second),
                states = mapOf(first to ViewState.Error("network down"), second to secondLoaded),
                lastLoadedStates = mapOf(first to firstLoaded, second to secondLoaded),
            )

        val workspace = assertInstanceOf(ViewState.WorkspaceLoaded::class.java, result)
        assertEquals(listOf(firstWorkflow), workspace.repositories[0].workflows)
        assertEquals("加载失败：network down", workspace.repositories[0].statusMessage)
        assertEquals(listOf(secondWorkflow), workspace.repositories[1].workflows)
        assertEquals(Instant.ofEpochSecond(20), workspace.lastUpdated)
        assertEquals(true, workspace.degraded)
    }

    @Test
    fun `仓库短暂失败时 refreshError 携带错误信息`() {
        val firstLoaded = ViewState.Loaded(listOf(WorkflowNode("CI", emptyList())), Instant.ofEpochSecond(10), degraded = false)

        val result =
            aggregateWorkspaceState(
                coordinates = listOf(first),
                states = mapOf(first to ViewState.Error("connection refused")),
                lastLoadedStates = mapOf(first to firstLoaded),
            )

        val workspace = assertInstanceOf(ViewState.WorkspaceLoaded::class.java, result)
        assertEquals("one/repo: connection refused", workspace.refreshError)
    }

    @Test
    fun `所有仓库正常时 refreshError 为 null`() {
        val loaded = ViewState.Loaded(listOf(WorkflowNode("CI", emptyList())), Instant.ofEpochSecond(10), degraded = false)

        val result =
            aggregateWorkspaceState(
                coordinates = listOf(first),
                states = mapOf(first to loaded),
                lastLoadedStates = mapOf(first to loaded),
            )

        val workspace = assertInstanceOf(ViewState.WorkspaceLoaded::class.java, result)
        assertEquals(null, workspace.refreshError)
    }

    @Test
    fun `尚无成功数据时保留全局认证错误`() {
        val result =
            aggregateWorkspaceState(
                coordinates = listOf(first, second),
                states = mapOf(first to ViewState.GhNotLoggedIn, second to ViewState.Loading),
                lastLoadedStates = emptyMap(),
            )

        assertSame(ViewState.GhNotLoggedIn, result)
    }

    // ---- aggregateRefreshing ----

    private fun dummyEngine(): PollingEngine {
        val noop = HttpTransport { _, _ -> HttpResponse(200, """{"workflow_runs":[]}""", emptyMap()) }
        val etags = EtagCache()
        return PollingEngine(
            client = GitHubActionsClient(noop, GhCliTokenProvider(CommandRunner { CommandOutput(0, "t", "") }), etags),
            etags = etags,
            repoProvider = { first },
            branchProvider = { "main" },
            expandedRuns = { emptySet() },
        )
    }

    @Test
    fun `没有引擎时 aggregateRefreshing 返回 false`() {
        assertFalse(aggregateRefreshing(emptyList()))
    }

    @Test
    fun `所有引擎都不在刷新时 aggregateRefreshing 返回 false`() {
        val engine = dummyEngine()
        assertFalse(aggregateRefreshing(listOf(engine)))
    }

    @Test
    fun `有引擎在刷新时 aggregateRefreshing 返回 true`() {
        val engine = dummyEngine()
        engine.requestRefresh()
        assertTrue(aggregateRefreshing(listOf(engine)))
    }

    @Test
    fun `唯一刷新中的引擎被移除后 aggregateRefreshing 返回 false`() {
        val refreshing = dummyEngine()
        refreshing.requestRefresh()
        assertTrue(refreshing.refreshing.value, "前置条件：引擎正在刷新")

        // 模拟 syncRepositories 把引擎从 map 移除后的遍历
        val remaining = emptyList<PollingEngine>()
        assertFalse(aggregateRefreshing(remaining), "引擎被移除后聚合结果应为 false")
    }

    @Test
    fun `一个引擎刷新另一个不刷新，移除刷新中的引擎后 aggregateRefreshing 返回 false`() {
        val refreshing = dummyEngine()
        val idle = dummyEngine()
        refreshing.requestRefresh()

        assertTrue(aggregateRefreshing(listOf(refreshing, idle)))
        assertFalse(aggregateRefreshing(listOf(idle)), "只剩空闲引擎时应为 false")
    }
}
