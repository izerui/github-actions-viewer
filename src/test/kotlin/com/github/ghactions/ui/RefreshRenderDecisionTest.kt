package com.github.ghactions.ui

import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.model.RepositoryNode
import com.github.ghactions.poll.ViewState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant

class RefreshRenderDecisionTest {
    private val error = ViewState.Error("connection refused")
    private val loaded =
        ViewState.WorkspaceLoaded(
            repositories = listOf(RepositoryNode(RepoCoordinates("o", "r"), emptyList())),
            lastUpdated = Instant.ofEpochSecond(100),
            degraded = false,
        )

    @Test
    fun `错误态开始刷新时切到 Loading`() {
        val result =
            resolveRefreshRender(
                refreshing = true,
                lastViewState = error,
                serviceCurrentState = error,
            )
        assertSame(ViewState.Loading, result)
    }

    @Test
    fun `GhNotInstalled 开始刷新时切到 Loading`() {
        val result =
            resolveRefreshRender(
                refreshing = true,
                lastViewState = ViewState.GhNotInstalled,
                serviceCurrentState = ViewState.GhNotInstalled,
            )
        assertSame(ViewState.Loading, result)
    }

    @Test
    fun `WorkspaceLoaded 开始刷新时不切换`() {
        val result =
            resolveRefreshRender(
                refreshing = true,
                lastViewState = loaded,
                serviceCurrentState = loaded,
            )
        assertNull(result)
    }

    @Test
    fun `刷新结束后返回 service 当前状态`() {
        val result =
            resolveRefreshRender(
                refreshing = false,
                lastViewState = ViewState.Loading,
                serviceCurrentState = error,
            )
        assertSame(error, result)
    }

    @Test
    fun `同样错误再次失败时刷新结束后返回该错误`() {
        // 完整路径：Error(x) → refreshing=true → Loading → refreshing=false → Error(x)
        // 第一步：开始刷新
        val step1 =
            resolveRefreshRender(
                refreshing = true,
                lastViewState = error,
                serviceCurrentState = error,
            )
        assertSame(ViewState.Loading, step1, "开始刷新应切到 Loading")

        // 模拟 render(Loading) 后 lastViewState 变成了 Loading
        // 第二步：刷新结束，service 状态仍是同一个 Error
        val step2 =
            resolveRefreshRender(
                refreshing = false,
                lastViewState = ViewState.Loading,
                serviceCurrentState = error,
            )
        assertSame(error, step2, "刷新结束应返回 service 当前错误状态，不能卡在 Loading")
    }

    @Test
    fun `刷新结束后恢复到 WorkspaceLoaded`() {
        val result =
            resolveRefreshRender(
                refreshing = false,
                lastViewState = ViewState.Loading,
                serviceCurrentState = loaded,
            )
        assertSame(loaded, result)
    }

    @Test
    fun `lastViewState 为 null 时开始刷新切到 Loading`() {
        val result =
            resolveRefreshRender(
                refreshing = true,
                lastViewState = null,
                serviceCurrentState = ViewState.Loading,
            )
        assertSame(ViewState.Loading, result)
    }

    @Test
    fun `EDT 同步设置 refreshing 后 flow observer 的后续发射不破坏状态`() {
        // 模拟完整时序：
        // 1. UI 处于 Error 态
        // 2. 用户点击刷新 → EDT 同步调 onRefreshingChanged(true) → render(Loading)
        // 3. flow observer 可能先发一次旧的 false（上一轮残留），然后发 true，最后发 false
        // 每一步都应产生正确的 render 决策

        var lastViewState: ViewState? = error

        // Step 1: EDT 同步 onRefreshingChanged(true)
        val r1 = resolveRefreshRender(true, lastViewState, error)
        assertSame(ViewState.Loading, r1, "Step 1: 错误态开始刷新应切到 Loading")
        lastViewState = ViewState.Loading // 模拟 render(Loading)

        // Step 2: flow observer 发了一次旧的 false（上一轮残留 — 实际不会发生因为 EDT 单线程，但验证鲁棒性）
        val r2 = resolveRefreshRender(false, lastViewState, error)
        assertSame(error, r2, "Step 2: 刷新结束应恢复到 service 当前状态")
        lastViewState = error // 模拟 render(error)

        // Step 3: flow observer 发 true（engine 正在刷新）
        val r3 = resolveRefreshRender(true, lastViewState, error)
        assertSame(ViewState.Loading, r3, "Step 3: 重新进入刷新应切回 Loading")
        lastViewState = ViewState.Loading

        // Step 4: flow observer 发 false（engine 刷新完成，仍然是同样的 error）
        val r4 = resolveRefreshRender(false, lastViewState, error)
        assertSame(error, r4, "Step 4: 最终应恢复到 Error")
    }

    @Test
    fun `无 engine 快速刷新时 EDT 同步设置保证至少一帧显示加载`() {
        // 场景：NoGitRemote，无 engine，StateFlow 的 true→false 被合并
        // UI 依赖 EDT actionPerformed 中同步调 onRefreshingChanged(true)，
        // 不依赖 flow observer 收到 true
        var lastViewState: ViewState? = ViewState.NoGitRemote

        // 用户点击 → EDT 同步置 refreshing=true
        val r1 = resolveRefreshRender(true, lastViewState, ViewState.NoGitRemote)
        assertSame(ViewState.Loading, r1, "NoGitRemote 开始刷新应切到 Loading")
        lastViewState = ViewState.Loading

        // flow observer 只收到 false（true 被合并掉了）
        val r2 = resolveRefreshRender(false, lastViewState, ViewState.NoGitRemote)
        assertSame(ViewState.NoGitRemote, r2, "刷新结束应恢复到 NoGitRemote")
    }
}
