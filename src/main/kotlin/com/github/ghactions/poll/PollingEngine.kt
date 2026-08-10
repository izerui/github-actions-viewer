package com.github.ghactions.poll

import com.github.ghactions.api.ApiResult
import com.github.ghactions.api.EtagCache
import com.github.ghactions.api.GitHubActionsClient
import com.github.ghactions.model.Job
import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.model.RunNode
import com.github.ghactions.model.WorkflowNode
import com.github.ghactions.model.WorkflowRun
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    /** 缓存 jobs 时该 run 所处的状态，用于识别「刚跑完」这一刻并重取终态。 */
    private val lastJobsRunStatus = mutableMapOf<Long, com.github.ghactions.model.RunStatus>()

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

    private suspend fun pollOnce(): Duration? {
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

        // 只请求「真正需要」的 jobs：
        //   - 运行中的 run：状态还在变，每轮都要刷新
        //   - 尚未缓存过的 run：第一次展开，必须拉一次
        // 已经拉过的、且已进入终态的 run 直接跳过——它的 jobs 与 steps 不会再变化。
        // 不能指望 ETag 兜住这件事：304 虽然不计配额，却仍要走一次完整的网络往返，
        // 在慢网络下同样是数秒。省配额和省延迟是两回事。
        val runsNeedingJobs = visibleRuns.filter { run ->
            run.id in expanded && (
                run.status.isRunning ||
                    run.id !in lastJobs ||
                    // run 刚由运行中转入终态的那一轮，它同时满足「已完成」和「已缓存」。
                    // 若不在此处补取一次，jobs 会永远停留在运行中的最后一帧——
                    // run 那行已是绿勾，展开后里面的 step 却还在转圈。
                    lastJobsRunStatus[run.id] != run.status
                )
        }

        // 剩下的请求并发进行：单次往返在慢网络下可达数秒，串行会让耗时线性叠加。
        // 但不能无限并发——首次展开十几个 run 时一次性打出那么多请求会撞上
        // GitHub 的 secondary rate limit（滥用保护），因此分批推进。
        val jobsResults = runsNeedingJobs
            .chunked(JOBS_FETCH_CONCURRENCY)
            .flatMap { batch ->
                coroutineScope {
                    batch
                        .map { run -> run.id to async { client.listJobs(repo, run.id) } }
                        .map { (runId, deferred) -> runId to deferred.await() }
                }
            }

        for ((runId, jobsResult) in jobsResults) {
            when (jobsResult) {
                is ApiResult.Data -> {
                    lastJobs[runId] = jobsResult.value
                    visibleRuns.firstOrNull { it.id == runId }?.let { lastJobsRunStatus[runId] = it.status }
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
        // 按「这个 run 还在不在列表里」清理缓存，而不是按用户有没有展开它。
        // 已完成 run 的 jobs 是终态、数据量很小，折叠就丢弃只会让用户每次重新展开
        // 都干等一轮网络往返。run 滑出最近 N 条之后自然会被清掉，不会无限增长。
        lastJobs.keys.retainAll(visibleIds)
        lastJobsRunStatus.keys.retainAll(visibleIds)

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

    private companion object {
        /**
         * 同时进行的 jobs 请求数上限。
         * 并发能把「展开 N 个 run」的耗时从累加压成取最慢的一个，
         * 但并发过猛会撞上 GitHub 的 secondary rate limit，因此分批推进。
         */
        const val JOBS_FETCH_CONCURRENCY = 4
    }

    private fun untilReset(resetAt: Instant): Duration {
        val seconds = java.time.Duration.between(now(), resetAt).seconds
        return maxOf(seconds, 60L).seconds
    }
}
