package com.github.ghactions.poll

import com.github.ghactions.api.EtagCache
import com.github.ghactions.api.GitHubActionsClient
import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.repo.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 协调多仓库轮询引擎的核心逻辑。不依赖 IDE，可在普通测试中验证。
 *
 * [ActionsPollingService] 是它的 IDE 壳——构造 IDE 依赖后委托到这里。
 */
internal class PollingCoordinator(
    private val client: GitHubActionsClient,
    private val etags: EtagCache,
    private val scope: CoroutineScope,
    private val repoProvider: () -> List<WorkspaceRepository>,
) {
    private val engines = ConcurrentHashMap<RepoCoordinates, EngineHolder>()
    private val repositorySnapshots = ConcurrentHashMap<RepoCoordinates, WorkspaceRepository>()
    private val engineStates = ConcurrentHashMap<RepoCoordinates, ViewState>()
    private val lastLoadedStates = ConcurrentHashMap<RepoCoordinates, ViewState.Loaded>()
    val expanded: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val repositoryWake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow<ViewState>(ViewState.Loading)
    val state: StateFlow<ViewState> = _state.asStateFlow()

    /**
     * 刷新状态。用 [RefreshStatus] 而非裸 Boolean：每次变化都产生新的
     * generation，StateFlow 不会因 equals 把 `false→true→false` 合并。
     * 这保证 UI 的 collector 一定能收到完成态的 `false`。
     */
    private val refreshStatusGen = AtomicLong()
    private val _refreshing = MutableStateFlow(RefreshStatus(0L, false))
    val refreshStatus: StateFlow<RefreshStatus> = _refreshing.asStateFlow()
    val isRefreshing: Boolean get() = _refreshing.value.refreshing

    @Volatile
    internal var isVisible = false

    @Volatile
    var branchFilterEnabled = false
    private var repositoryMissCount = 0
    private var lastLoggedRepositories: Set<RepoCoordinates> = emptySet()

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                syncRepositories(repoProvider())
                val scanInterval =
                    when {
                        repositorySnapshots.isEmpty() &&
                            repositoryMissCount <= PollingSchedule.REPO_GRACE_ROUNDS -> PollingSchedule.REPO_PROBE

                        isVisible -> PollingSchedule.ACTIVE

                        else -> PollingSchedule.IDLE
                    }
                withTimeoutOrNull(scanInterval) { repositoryWake.receive() }
            }
        }
    }

    internal fun syncRepositories(repositories: List<WorkspaceRepository>) {
        val current = repositories.distinctBy { it.coordinates }.associateBy { it.coordinates }
        repositorySnapshots.clear()
        repositorySnapshots.putAll(current)

        val removed = engines.keys - current.keys
        removed.forEach { coordinates ->
            engines.remove(coordinates)?.jobs?.forEach(Job::cancel)
            engineStates.remove(coordinates)
            lastLoadedStates.remove(coordinates)
        }

        current.forEach { (coordinates, repository) ->
            engines.computeIfAbsent(coordinates) {
                createEngine(repository).also { holder ->
                    holder.engine.setVisible(isVisible)
                    holder.engine.setBranchFilter(branchFilterEnabled)
                }
            }
        }

        if (current.isEmpty()) {
            repositoryMissCount++
            _state.value =
                if (repositoryMissCount > PollingSchedule.REPO_GRACE_ROUNDS) {
                    ViewState.NoGitRemote
                } else {
                    ViewState.Loading
                }
        } else {
            repositoryMissCount = 0
            publishAggregatedState()
        }
        // 每轮仓库扫描结束后无条件聚合。requestRefresh() 同步置了 _refreshing=true，
        // 如果没有 engine（NoGitRemote 等）或 engine 已被移除，这里把它清回 false。
        // 有 engine 时 engine 的 collector 也会触发聚合，这里多调一次是幂等的。
        publishRefreshingState()
        logRepositories(current.keys)
    }

    private fun createEngine(repository: WorkspaceRepository): EngineHolder {
        val coordinates = repository.coordinates
        val engine =
            PollingEngine(
                client = client,
                etags = etags,
                repoProvider = { coordinates },
                branchProvider = { repositorySnapshots[coordinates]?.branch },
                expandedRuns = { expanded.toSet() },
            )
        val runJob = scope.launch(Dispatchers.IO) { engine.run() }
        val stateJob =
            scope.launch {
                engine.state.collect { state ->
                    engineStates[coordinates] = state
                    if (state is ViewState.Loaded) lastLoadedStates[coordinates] = state
                    publishAggregatedState()
                }
            }
        val refreshJob =
            scope.launch {
                engine.refreshing.collect { publishRefreshingState() }
            }
        return EngineHolder(engine, listOf(runJob, stateJob, refreshJob))
    }

    /** 仅供测试：在 publishRefreshingState 获取锁之前调用。 */
    @Volatile
    internal var beforePublishRefreshing: (() -> Unit)? = null

    internal fun publishRefreshingState() {
        beforePublishRefreshing?.invoke()
        synchronized(this) {
            _refreshing.value =
                RefreshStatus(
                    refreshStatusGen.incrementAndGet(),
                    aggregateRefreshing(engines.values.map { it.engine }),
                )
        }
    }

    @Synchronized
    private fun publishAggregatedState() {
        val coordinates = repositorySnapshots.keys.sortedBy(RepoCoordinates::toString)
        if (coordinates.isEmpty()) return
        _state.value = aggregateWorkspaceState(coordinates, engineStates, lastLoadedStates)
    }

    /** 仅供测试：在 requestRefresh 的 service=true 和 engine 通知之间调用。 */
    @Volatile
    internal var beforeEngineRefresh: (() -> Unit)? = null

    @Synchronized
    fun requestRefresh() {
        // 与 publishRefreshingState 共用同一把锁。整个方法体必须在锁内：
        // service 级 _refreshing=true 和 engine 级 requestRefresh() 之间如果
        // publishRefreshingState 插入，它会看到所有 engine 仍是 false 并写入
        // 更高 generation 的 false，覆盖掉刚写的 true。
        // engine.requestRefresh() 和 repositoryWake.trySend() 都是非阻塞的，
        // 在锁内不会造成死锁或延迟。
        _refreshing.value = RefreshStatus(refreshStatusGen.incrementAndGet(), true)
        beforeEngineRefresh?.invoke()
        engines.values.forEach { it.engine.requestRefresh() }
        repositoryWake.trySend(Unit)
    }

    fun setBranchFilter(enabled: Boolean) {
        branchFilterEnabled = enabled
        engines.values.forEach { it.engine.setBranchFilter(enabled) }
    }

    fun setVisible(value: Boolean) {
        isVisible = value
        repositoryWake.trySend(Unit)
        engines.values.forEach { it.engine.setVisible(value) }
    }

    fun onExpansionChanged(
        runId: Long,
        isExpanded: Boolean,
    ) {
        val changed = if (isExpanded) expanded.add(runId) else expanded.remove(runId)
        if (changed && isExpanded) engines.values.forEach { it.engine.onExpansionChanged() }
    }

    fun loadMore(
        repository: RepoCoordinates,
        workflowName: String,
        onDone: suspend () -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                engines[repository]?.engine?.loadMore(workflowName)
            } finally {
                onDone()
            }
        }
    }

    private fun logRepositories(resolved: Set<RepoCoordinates>) {
        if (resolved != lastLoggedRepositories) {
            lastLoggedRepositories = resolved
            val description = resolved.joinToString().ifEmpty { "无" }
            LOG.info("解析到 GitHub 仓库: $description")
        }
    }

    internal data class EngineHolder(
        val engine: PollingEngine,
        val jobs: List<Job>,
    )

    private companion object {
        val LOG =
            com.intellij.openapi.diagnostic.Logger
                .getInstance(PollingCoordinator::class.java)
    }
}

/**
 * 带代次的刷新状态。每次 [PollingCoordinator.requestRefresh] 和
 * [PollingCoordinator.publishRefreshingState] 都递增 [generation]，
 * 使 StateFlow 不会因 equals 合并 `false→true→false`。
 */
internal data class RefreshStatus(
    val generation: Long,
    val refreshing: Boolean,
)
