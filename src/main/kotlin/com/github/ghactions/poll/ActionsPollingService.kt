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
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val coordinator = PollingCoordinator(client, etags, scope, repoProvider::repositories)

    val state: StateFlow<ViewState> get() = coordinator.state

    init {
        coordinator.start()
    }

    fun requestRefresh() = coordinator.requestRefresh()

    fun setBranchFilter(enabled: Boolean) = coordinator.setBranchFilter(enabled)

    fun setVisible(value: Boolean) = coordinator.setVisible(value)

    fun setExpanded(runId: Long, isExpanded: Boolean) = coordinator.onExpansionChanged(runId, isExpanded)

    fun loadMore(
        repository: RepoCoordinates,
        workflowName: String,
        onDone: () -> Unit,
    ) {
        coordinator.loadMore(repository, workflowName) {
            withContext(Dispatchers.EDT) { onDone() }
        }
    }

    fun observe(onState: (ViewState) -> Unit) {
        scope.launch(Dispatchers.EDT) {
            state.collect { value ->
                LOG.info("面板状态 -> ${value.javaClass.simpleName}")
                onState(value)
            }
        }
    }

    fun observeRefreshing(onRefreshing: (Boolean) -> Unit) {
        scope.launch(Dispatchers.EDT) {
            coordinator.refreshStatus.collect { status -> onRefreshing(status.refreshing) }
        }
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
        val errors = coordinates.mapNotNull { repository ->
            val state = states[repository]
            when {
                state is ViewState.Error -> "${repository}: ${state.message}"
                state is ViewState.RateLimited -> "${repository}: API 配额已用尽"
                else -> null
            }
        }
        return ViewState.WorkspaceLoaded(
            repositories =
                coordinates.map { repository ->
                    val workflows = loadedByRepository[repository]?.workflows.orEmpty()
                    RepositoryNode(repository, workflows, repositoryStatusMessage(states[repository]))
                },
            lastUpdated = effectiveLoaded.maxOf { it.second.lastUpdated },
            degraded = effectiveLoaded.any { it.second.degraded },
            refreshError = errors.firstOrNull(),
        )
    }

    val currentStates = coordinates.mapNotNull(states::get)
    return currentStates.firstOrNull { it is ViewState.GhNotInstalled }
        ?: currentStates.firstOrNull { it is ViewState.GhNotLoggedIn }
        ?: currentStates.firstOrNull { it is ViewState.RateLimited }
        ?: currentStates.firstOrNull { it is ViewState.Error }
        ?: ViewState.Loading
}

internal fun aggregateRefreshing(engines: Iterable<PollingEngine>): Boolean =
    engines.any { it.refreshing.value }

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
