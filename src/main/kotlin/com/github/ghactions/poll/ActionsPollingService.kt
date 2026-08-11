package com.github.ghactions.poll

import com.github.ghactions.api.EtagCache
import com.github.ghactions.api.GitHubActionsClient
import com.github.ghactions.api.JdkHttpTransport
import com.github.ghactions.auth.GhCliTokenProvider
import com.github.ghactions.auth.ProcessCommandRunner
import com.github.ghactions.repo.IdeGitRepoProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 项目级 service，负责组装依赖并驱动轮询循环。
 * 注入的 CoroutineScope 在项目关闭时自动取消。
 */
@Service(Service.Level.PROJECT)
class ActionsPollingService(project: Project, private val scope: CoroutineScope) {

    private val etags = EtagCache()
    private val repoProvider = IdeGitRepoProvider(project)

    /** 当前树上处于展开状态的 run，决定了哪些 run 需要拉取 jobs。 */
    private val expanded: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    val engine: PollingEngine = PollingEngine(
        client = GitHubActionsClient(
            transport = JdkHttpTransport(ideProxySelector()),
            tokenProvider = GhCliTokenProvider(ProcessCommandRunner()),
            etags = etags,
        ),
        etags = etags,
        repoProvider = { repoProvider.currentRepo().also { logRepo(it) } },
        branchProvider = { repoProvider.currentBranch() },
        expandedRuns = { expanded.toSet() },
    )

    /**
     * 只有用户**显式**配置了代理时才走代理，否则直连。
     *
     * 不能直接用 ProxySelector.getDefault()：IntelliJ 装在那里的选择器默认是
     * 「自动检测系统代理」——那是 IDE 的出厂默认值，不代表用户想走代理。
     * 照单全收会把请求塞进用户根本没打算使用的系统代理里。
     */
    private fun ideProxySelector(): java.net.ProxySelector? {
        val configured = when (com.intellij.util.net.ProxySettings.getInstance().getProxyConfiguration()) {
            is com.intellij.util.net.ProxyConfiguration.StaticProxyConfiguration,
            is com.intellij.util.net.ProxyConfiguration.ProxyAutoConfiguration,
            -> true

            else -> false
        }
        return if (configured) java.net.ProxySelector.getDefault() else null
    }

    /** 仓库解析结果只在变化时记录一次，避免每轮刷屏。排查「面板空着」时这是第一手线索。 */
    private fun logRepo(resolved: com.github.ghactions.model.RepoCoordinates?) {
        if (resolved != lastLoggedRepo) {
            lastLoggedRepo = resolved
            LOG.info("解析到 GitHub 仓库: ${resolved ?: "无（当前项目没有指向 github.com 的 remote，或 Git 尚未初始化完成）"}")
        }
    }

    private var lastLoggedRepo: com.github.ghactions.model.RepoCoordinates? = null

    init {
        // client 调用是阻塞式的，必须放在 IO 线程上
        scope.launch(Dispatchers.IO) { engine.run() }
    }

    /**
     * 为某个 workflow 再加载一页历史记录。client 调用阻塞，同样丢到 IO 线程。
     *
     * [onDone] 在 EDT 上回调，且**无论成败都会执行**——失败时引擎不会发布新状态，
     * 若只在成功时回调，那一行会永远转圈。
     */
    fun loadMore(workflowName: String, onDone: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                engine.loadMore(workflowName)
            } finally {
                withContext(Dispatchers.EDT) { onDone() }
            }
        }
    }

    fun setExpanded(runId: Long, isExpanded: Boolean) {
        val changed = if (isExpanded) expanded.add(runId) else expanded.remove(runId)
        if (changed && isExpanded) engine.onExpansionChanged()
    }

    /** 在 EDT 上订阅状态变化。协程随 service 的 scope 一同取消。 */
    fun observe(onState: (ViewState) -> Unit) {
        scope.launch(Dispatchers.EDT) {
            engine.state.collect { state ->
                LOG.info("面板状态 -> ${state.javaClass.simpleName}")
                onState(state)
            }
        }
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ActionsPollingService::class.java)

        fun getInstance(project: Project): ActionsPollingService = project.service()
    }
}
