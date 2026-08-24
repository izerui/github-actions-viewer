package com.github.ghactions.poll

import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.model.WorkflowNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
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
    fun `尚无成功数据时保留全局认证错误`() {
        val result =
            aggregateWorkspaceState(
                coordinates = listOf(first, second),
                states = mapOf(first to ViewState.GhNotLoggedIn, second to ViewState.Loading),
                lastLoadedStates = emptyMap(),
            )

        assertSame(ViewState.GhNotLoggedIn, result)
    }
}
