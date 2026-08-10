package com.github.ghactions.poll

import com.github.ghactions.api.ApiResult
import com.github.ghactions.api.EtagCache
import com.github.ghactions.api.GitHubActionsClient
import com.github.ghactions.model.Job
import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.model.RunNode
import com.github.ghactions.model.WorkflowNode
import com.github.ghactions.model.WorkflowRun
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 轮询循环与状态汇聚。不依赖 IDE 与 Swing，因此整个节奏行为
 * 可以用协程虚拟时间完整验证。
 *
 * 所有 client 调用均为阻塞式，调用方须在 IO 线程上启动 [run]。
 */
class PollingEngine(
    private val client: GitHubActionsClient,
    private val etags: EtagCache,
    private val repoProvider: () -> RepoCoordinates?,
    private val branchProvider: () -> String?,
    private val expandedRuns: () -> Set<Long>,
    private val now: () -> Instant = Instant::now,
) {

    private val _state = MutableStateFlow<ViewState>(ViewState.Loading)
    val state: StateFlow<ViewState> = _state.asStateFlow()

    private val visible = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var branchFilterEnabled = false

    /** 连续拿不到仓库的轮次，用于区分「Git 还没就绪」与「确实没有 GitHub remote」。 */
    private var repoMissCount = 0

    private var lastRuns: List<WorkflowRun> = emptyList()
    private val lastJobs = mutableMapOf<Long, List<Job>>()

    fun setVisible(value: Boolean) {
        val previous = visible.value
        visible.value = value
        if (!previous && value) requestRefresh()
    }

    /** 强制刷新：丢弃 ETag 并立即唤醒循环。 */
    fun requestRefresh() {
        etags.clear()
        wake.trySend(Unit)
    }

    /** 切换分支过滤。不清 ETag——数据没变，只是展示范围变了。 */
    fun setBranchFilter(enabled: Boolean) {
        branchFilterEnabled = enabled
        wake.trySend(Unit)
    }

    /** 树的展开状态变化后调用，使新展开的 run 立刻加载 jobs。 */
    fun onExpansionChanged() {
        wake.trySend(Unit)
    }

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            if (!visible.value) {
                visible.first { it }
                continue
            }
            // 丢弃积压的唤醒信号，否则刚拉完就会被自己触发的信号立即重拉
            wake.tryReceive()

            val interval = pollOnce() ?: continue
            withTimeoutOrNull(interval) { wake.receive() }
        }
    }

    private fun pollOnce(): Duration? {
        val repo = repoProvider()
        if (repo == null) {
            // IDE 启动初期 GitRepositoryManager 尚未初始化完，repoProvider 会短暂返回 null。
            // 那是「还没准备好」而非「确实没有 GitHub remote」——过早下结论会让用户
            // 对着「当前项目没有 GitHub remote」这句误导性提示，以为自己配置错了。
            // 因此设一个宽限期：期间保持加载中，连续多轮都拿不到才认定为真的没有。
            // 这条路径只查内存中的仓库列表、不发 HTTP，所以探测得很勤也没有成本。
            repoMissCount++
            _state.value = if (repoMissCount > PollingSchedule.REPO_GRACE_ROUNDS) {
                ViewState.NoGitRemote
            } else {
                ViewState.Loading
            }
            return if (visible.value) PollingSchedule.REPO_PROBE else null
        }
        repoMissCount = 0

        var remaining: Int? = null

        when (val result = client.listRuns(repo)) {
            is ApiResult.Data -> {
                lastRuns = result.value
                remaining = result.rateLimitRemaining
            }

            is ApiResult.NotModified -> remaining = result.rateLimitRemaining

            is ApiResult.RateLimited -> {
                _state.value = ViewState.RateLimited(result.resetAt)
                return untilReset(result.resetAt)
            }

            ApiResult.GhNotInstalled -> {
                _state.value = ViewState.GhNotInstalled
                return PollingSchedule.IDLE
            }

            ApiResult.GhNotLoggedIn -> {
                _state.value = ViewState.GhNotLoggedIn
                return PollingSchedule.IDLE
            }

            is ApiResult.Error -> {
                _state.value = ViewState.Error(result.message)
                return PollingSchedule.IDLE
            }
        }

        val currentBranch = branchProvider()
        val visibleRuns = lastRuns
            .filter { !branchFilterEnabled || it.branch == currentBranch }
            .sortedByDescending { it.runNumber }

        // 兜底收敛：JTree 只对可见节点派发 treeCollapsed。run 因分支过滤被移出、
        // 或因超出最近条数从列表消失时，其 id 不会被自然移除，会永久留在 expanded 集合。
        // 这里与当前可见 run 的 id 求交，只对仍存在的 run 拉 jobs 并保留缓存。
        val visibleIds = visibleRuns.mapTo(HashSet()) { it.id }
        val expanded = expandedRuns().intersect(visibleIds)
        for (run in visibleRuns) {
            if (run.id !in expanded) continue
            when (val jobsResult = client.listJobs(repo, run.id)) {
                is ApiResult.Data -> {
                    lastJobs[run.id] = jobsResult.value
                    jobsResult.rateLimitRemaining?.let { remaining = it }
                }

                is ApiResult.NotModified -> jobsResult.rateLimitRemaining?.let { remaining = it }

                is ApiResult.RateLimited -> {
                    _state.value = ViewState.RateLimited(jobsResult.resetAt)
                    return untilReset(jobsResult.resetAt)
                }

                // jobs 拉取失败不应清空已有的树，保留上一轮数据继续展示
                else -> Unit
            }
        }
        // 折叠的 run 释放缓存，避免长期占用内存
        lastJobs.keys.retainAll(expanded)

        val workflows = visibleRuns
            .groupBy { it.workflowName }
            .map { (name, runs) -> WorkflowNode(name, runs.map { RunNode(it, lastJobs[it.id]) }) }
            .sortedBy { it.name }

        val quota = remaining
        _state.value = ViewState.Loaded(
            workflows = workflows,
            lastUpdated = now(),
            degraded = quota != null && quota < PollingSchedule.LOW_QUOTA_THRESHOLD,
        )

        return PollingSchedule.intervalFor(
            visible = visible.value,
            hasRunning = visibleRuns.any { it.status.isRunning },
            rateLimitRemaining = quota,
        )
    }

    private fun untilReset(resetAt: Instant): Duration {
        val seconds = java.time.Duration.between(now(), resetAt).seconds
        return maxOf(seconds, 60L).seconds
    }
}
