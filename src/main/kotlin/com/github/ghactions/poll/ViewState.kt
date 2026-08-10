package com.github.ghactions.poll

import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.model.WorkflowNode
import java.time.Instant

/**
 * 面板的完整状态。所有失败模式在类型层面穷举，
 * UI 必须为每个分支提供画面，不存在「空白且无解释」的情况。
 */
sealed interface ViewState {
    data object Loading : ViewState
    data object NoGitRemote : ViewState
    data object GhNotInstalled : ViewState
    data object GhNotLoggedIn : ViewState
    data class RateLimited(val resetAt: Instant) : ViewState
    data class Error(val message: String) : ViewState
    data class Loaded(
        val repo: RepoCoordinates,
        val workflows: List<WorkflowNode>,
        val lastUpdated: Instant,
        val degraded: Boolean,
    ) : ViewState
}
