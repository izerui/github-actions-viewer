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
            transport = JdkHttpTransport(),
            tokenProvider = GhCliTokenProvider(ProcessCommandRunner()),
            etags = etags,
        ),
        etags = etags,
        repoProvider = { repoProvider.currentRepo() },
        branchProvider = { repoProvider.currentBranch() },
        expandedRuns = { expanded.toSet() },
    )

    init {
        // client 调用是阻塞式的，必须放在 IO 线程上
        scope.launch(Dispatchers.IO) { engine.run() }
    }

    fun setExpanded(runId: Long, isExpanded: Boolean) {
        val changed = if (isExpanded) expanded.add(runId) else expanded.remove(runId)
        if (changed && isExpanded) engine.onExpansionChanged()
    }

    /** 在 EDT 上订阅状态变化。协程随 service 的 scope 一同取消。 */
    fun observe(onState: (ViewState) -> Unit) {
        scope.launch(Dispatchers.EDT) {
            engine.state.collect { onState(it) }
        }
    }

    companion object {
        fun getInstance(project: Project): ActionsPollingService = project.service()
    }
}
