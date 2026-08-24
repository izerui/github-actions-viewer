package com.github.ghactions.poll

import com.github.ghactions.api.EtagCache
import com.github.ghactions.api.GitHubActionsClient
import com.github.ghactions.api.HttpTransport
import com.github.ghactions.api.JdkHttpTransport
import com.github.ghactions.auth.GhCliTokenProvider
import com.github.ghactions.auth.ProcessCommandRunner
import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.model.RepositoryNode
import com.github.ghactions.repo.IdeGitRepoProvider
import com.github.ghactions.repo.WorkspaceRepository
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** Project service that coordinates one polling engine per workspace repository. */
@Service(Service.Level.PROJECT)
class ActionsPollingService(
    project: Project,
    private val scope: CoroutineScope,
) {
    private val etags = EtagCache()
    private val repoProvider = IdeGitRepoProvider(project)
    private val client =
        GitHubActionsClient(
            transport = limitedTransport(JdkHttpTransport(ideProxySelector())),
            tokenProvider = GhCliTokenProvider(ProcessCommandRunner()),
            etags = etags,
        )

    private val expanded: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val engines = ConcurrentHashMap<RepoCoordinates, EngineHolder>()
    private val repositorySnapshots = ConcurrentHashMap<RepoCoordinates, WorkspaceRepository>()
    private val engineStates = ConcurrentHashMap<RepoCoordinates, ViewState>()
    private val lastLoadedStates = ConcurrentHashMap<RepoCoordinates, ViewState.Loaded>()
    private val repositoryWake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow<ViewState>(ViewState.Loading)
    val state: StateFlow<ViewState> = _state.asStateFlow()

    @Volatile private var visible = false

    @Volatile private var branchFilterEnabled = false
    private var repositoryMissCount = 0

    init {
        scope.launch(Dispatchers.IO) {
            while (true) {
                syncRepositories(repoProvider.repositories())
                val scanInterval =
                    when {
                        repositorySnapshots.isEmpty() &&
                            repositoryMissCount <= PollingSchedule.REPO_GRACE_ROUNDS -> PollingSchedule.REPO_PROBE

                        visible -> PollingSchedule.ACTIVE

                        else -> PollingSchedule.IDLE
                    }
                withTimeoutOrNull(scanInterval) { repositoryWake.receive() }
            }
        }
    }

    private fun syncRepositories(repositories: List<WorkspaceRepository>) {
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
                    holder.engine.setVisible(visible)
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
        return EngineHolder(engine, listOf(runJob, stateJob))
    }

    @Synchronized
    private fun publishAggregatedState() {
        val coordinates = repositorySnapshots.keys.sortedBy(RepoCoordinates::toString)
        if (coordinates.isEmpty()) return
        _state.value = aggregateWorkspaceState(coordinates, engineStates, lastLoadedStates)
    }

    fun requestRefresh() {
        repositoryWake.trySend(Unit)
        engines.values.forEach { it.engine.requestRefresh() }
    }

    fun setBranchFilter(enabled: Boolean) {
        branchFilterEnabled = enabled
        engines.values.forEach { it.engine.setBranchFilter(enabled) }
    }

    fun setVisible(value: Boolean) {
        visible = value
        repositoryWake.trySend(Unit)
        engines.values.forEach { it.engine.setVisible(value) }
    }

    private fun limitedTransport(delegate: HttpTransport): HttpTransport {
        val permits = Semaphore(MAX_CONCURRENT_REQUESTS)
        return HttpTransport { url, headers ->
            permits.acquire()
            try {
                delegate.get(url, headers)
            } finally {
                permits.release()
            }
        }
    }

    private fun ideProxySelector(): java.net.ProxySelector? {
        val configured =
            when (
                com.intellij.util.net.ProxySettings
                    .getInstance()
                    .getProxyConfiguration()
            ) {
                is com.intellij.util.net.ProxyConfiguration.StaticProxyConfiguration,
                is com.intellij.util.net.ProxyConfiguration.ProxyAutoConfiguration,
                -> true

                else -> false
            }
        return if (configured) java.net.ProxySelector.getDefault() else null
    }

    private fun logRepositories(resolved: Set<RepoCoordinates>) {
        if (resolved != lastLoggedRepositories) {
            lastLoggedRepositories = resolved
            val description = resolved.joinToString().ifEmpty { "无" }
            LOG.info("解析到 GitHub 仓库: $description")
        }
    }

    private var lastLoggedRepositories: Set<RepoCoordinates> = emptySet()

    fun loadMore(
        repository: RepoCoordinates,
        workflowName: String,
        onDone: () -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                engines[repository]?.engine?.loadMore(workflowName)
            } finally {
                withContext(Dispatchers.EDT) { onDone() }
            }
        }
    }

    fun setExpanded(
        runId: Long,
        isExpanded: Boolean,
    ) {
        val changed = if (isExpanded) expanded.add(runId) else expanded.remove(runId)
        if (changed && isExpanded) engines.values.forEach { it.engine.onExpansionChanged() }
    }

    fun observe(onState: (ViewState) -> Unit) {
        scope.launch(Dispatchers.EDT) {
            state.collect { value ->
                LOG.info("面板状态 -> ${value.javaClass.simpleName}")
                onState(value)
            }
        }
    }

    private data class EngineHolder(
        val engine: PollingEngine,
        val jobs: List<Job>,
    )

    companion object {
        private const val MAX_CONCURRENT_REQUESTS = 4
        private val LOG =
            com.intellij.openapi.diagnostic.Logger
                .getInstance(ActionsPollingService::class.java)

        fun getInstance(project: Project): ActionsPollingService = project.service()
    }
}

internal fun aggregateWorkspaceState(
    coordinates: List<RepoCoordinates>,
    states: Map<RepoCoordinates, ViewState>,
    lastLoadedStates: Map<RepoCoordinates, ViewState.Loaded>,
): ViewState {
    val effectiveLoaded =
        coordinates.mapNotNull { repository ->
            val loaded = states[repository] as? ViewState.Loaded ?: lastLoadedStates[repository]
            loaded?.let { repository to it }
        }

    if (effectiveLoaded.isNotEmpty()) {
        val loadedByRepository = effectiveLoaded.toMap()
        return ViewState.WorkspaceLoaded(
            repositories =
                coordinates.map { repository ->
                    val workflows = loadedByRepository[repository]?.workflows.orEmpty()
                    RepositoryNode(repository, workflows, repositoryStatusMessage(states[repository]))
                },
            lastUpdated = effectiveLoaded.maxOf { it.second.lastUpdated },
            degraded = effectiveLoaded.any { it.second.degraded },
        )
    }

    val currentStates = coordinates.mapNotNull(states::get)
    return currentStates.firstOrNull { it is ViewState.GhNotInstalled }
        ?: currentStates.firstOrNull { it is ViewState.GhNotLoggedIn }
        ?: currentStates.firstOrNull { it is ViewState.RateLimited }
        ?: currentStates.firstOrNull { it is ViewState.Error }
        ?: ViewState.Loading
}

private fun repositoryStatusMessage(state: ViewState?): String? =
    when (state) {
        null, ViewState.Loading -> "正在加载…"
        ViewState.NoGitRemote -> "没有 GitHub remote"
        ViewState.GhNotInstalled -> "未检测到 GitHub CLI"
        ViewState.GhNotLoggedIn -> "尚未登录 GitHub CLI"
        is ViewState.RateLimited -> "API 配额已用尽"
        is ViewState.Error -> "加载失败：${state.message}"
        is ViewState.Loaded, is ViewState.WorkspaceLoaded -> null
    }
