package com.github.ghactions.ui

import com.github.ghactions.poll.ActionsPollingService
import com.github.ghactions.poll.ViewState
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.PopupHandler
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.hover.TreeHoverListener
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.text.DateFormatUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeSelectionModel

private const val CARD_TREE = "tree"
private const val CARD_EMPTY = "empty"

class ActionsTreePanel(
    private val project: Project,
) : JBPanel<ActionsTreePanel>(BorderLayout()) {
    private val service = ActionsPollingService.getInstance(project)
    private val treeModel = ActionsTreeModel()
    private val tree = Tree(treeModel.swingModel)
    private val rowRenderer = ActionsRowRenderer()

    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val emptyHolder = JPanel(BorderLayout())
    private val statusLabel = JLabel(" ", SwingConstants.LEFT)

    private var branchFilterEnabled = false

    val titleActions: List<AnAction> =
        listOf(
            object : AnAction("刷新", "立即刷新", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT

                override fun actionPerformed(e: AnActionEvent) = service.requestRefresh()
            },
            object : ToggleAction("只看当前分支", "只显示当前分支的运行记录", AllIcons.Vcs.Branch) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT

                override fun isSelected(e: AnActionEvent): Boolean = branchFilterEnabled

                override fun setSelected(
                    e: AnActionEvent,
                    state: Boolean,
                ) {
                    branchFilterEnabled = state
                    service.setBranchFilter(state)
                }
            },
            openInBrowserAction(),
        )

    /**
     * 已展开但还没拿到 jobs 的 run。
     *
     * 必须在用户点击展开的那一刻就置上：轮询要等好几秒才回来，等它回来再标记
     * 「加载中」已经晚了——那时数据已经到手，标记永远不会为真。
     */
    private val pendingRuns = mutableSetOf<Long>()

    /** 已经执行过默认展开的仓库；用户随后手动折叠时不再干预。 */
    private val autoExpandedRepositories = mutableSetOf<com.github.ghactions.model.RepoCoordinates>()

    /** 上一次真正渲染到树上的数据，用于跳过无谓的重建。 */
    private var lastRenderedRepositories: List<com.github.ghactions.model.RepositoryNode>? = null

    /**
     * 正在加载更多的 workflow。
     *
     * 与 [pendingRuns] 同理：网络往返要好几秒，反馈必须在点击的那一刻就出现在
     * 那一行上，而不是等数据回来再说。
     */
    private val loadingWorkflows = mutableSetOf<String>()

    init {
        // 显示 REPOSITORIES 分组标题（渲染器会把根节点画成灰色粗体）
        tree.isRootVisible = true
        tree.showsRootHandles = true
        tree.cellRenderer = rowRenderer
        // 允许在 CellRendererPane 下自我刷新的动画图标（"运行中"转轮）真正转动。
        // AnimatedIcon.getRendererOwner 以此 client property 为闸门，不设则转轮静止。
        tree.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        content.add(JBScrollPane(tree), CARD_TREE)
        content.add(emptyHolder, CARD_EMPTY)

        add(content, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        wireExpansionTracking()
        wireVisibilityTracking()
        wireSpeedSearch()
        wirePopupMenu()
        wireHoverAction()

        // 先同步渲染一次当前状态，再订阅后续变化。
        // observe 提交的 EDT 协程要等到调度器空闲才开始 collect，这中间存在一个窗口期；
        // 若不先渲染，CardLayout 会停在初始的树卡片上，显示 Tree 组件默认的
        // "Nothing to show"，而不是我们的「正在加载…」。
        render(service.state.value)
        service.observe(::render)
    }

    /** 「在浏览器中打开」。标题栏与右键菜单共用同一份定义。 */
    private fun openInBrowserAction(): AnAction =
        object : AnAction("在浏览器中打开", "打开选中运行的 GitHub 页面", AllIcons.General.Web) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedRunUrl() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                selectedRunUrl()?.let(::openInBrowser)
            }
        }

    /** 「复制链接」。 */
    private fun copyLinkAction(): AnAction =
        object : AnAction("复制链接", "复制该次运行的 GitHub 页面地址", AllIcons.Actions.Copy) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedRunUrl() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                selectedRunUrl()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
            }
        }

    /**
     * 悬停时在行右侧浮现「在浏览器中打开」按钮，点它直接跳转。
     * 忙碌与操作反馈都出现在鼠标所指的那一行，不必先选中再去工具栏找按钮。
     */
    private fun wireHoverAction() {
        object : TreeHoverListener() {
            override fun onHover(
                tree: JTree,
                row: Int,
            ) {
                if (rowRenderer.hoveredRow == row) return
                rowRenderer.hoveredRow = row
                tree.repaint()
            }
        }.addTo(tree)

        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = tree.getRowForLocation(e.x, e.y).takeIf { it >= 0 } ?: return
                    val node = tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode ?: return

                    // 「加载更多」整行都是按钮：这一行没有别的可点内容，
                    // 不必像 run 行那样把命中区限定在右端的图标上。
                    val more = node.userObject as? LoadMoreItem
                    if (more != null) {
                        if (!more.loading) requestLoadMore(more)
                        e.consume()
                        return
                    }

                    val bounds = tree.getRowBounds(row) ?: return
                    val actionWidth = rowRenderer.actionWidth()
                    if (actionWidth <= 0) return
                    // 命中判断：按钮贴在该行内容的最右端
                    if (e.x < bounds.x + bounds.width - actionWidth) return

                    val url = (node.userObject as? RunItem)?.run?.htmlUrl?.ifEmpty { null } ?: return
                    openInBrowser(url)
                    e.consume()
                }
            },
        )
    }

    /** 右键菜单。IDEA 里到处都是这种交互，用户不用学。 */
    private fun wirePopupMenu() {
        val group = DefaultActionGroup(openInBrowserAction(), copyLinkAction())
        PopupHandler.installPopupMenu(tree, group, "GitHubActionsViewerPopup")
    }

    /**
     * 输入即搜索：在树上直接打字就能定位到对应的 workflow / run / job / step。
     * 这是 IntelliJ 所有树都具备的标志性手感，用户会下意识地去用。
     */
    private fun wireSpeedSearch() {
        TreeSpeedSearch.installOn(tree, true) { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode
            (node?.userObject as? TreeItem)?.label.orEmpty()
        }
    }

    /** 展开状态直接驱动 jobs 的按需拉取——用户看什么，才请求什么。 */
    private fun wireExpansionTracking() {
        tree.addTreeExpansionListener(
            object : TreeExpansionListener {
                override fun treeExpanded(event: TreeExpansionEvent) = update(event, true)

                override fun treeCollapsed(event: TreeExpansionEvent) = update(event, false)

                private fun update(
                    event: TreeExpansionEvent,
                    expanded: Boolean,
                ) {
                    val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val item = node.userObject as? RunItem ?: return
                    service.setExpanded(item.run.id, expanded)

                    // 展开一个还没有子节点的 run —— 数据要等一轮网络往返才到，
                    // 此刻立即让它转圈，用户才知道自己那一下点生效了。
                    if (expanded && node.childCount == 0) {
                        pendingRuns.add(item.run.id)
                    } else if (!expanded) {
                        pendingRuns.remove(item.run.id)
                    }
                    applyLoadingIndicator()
                }
            },
        )
    }

    /** 面板不在屏幕上显示时彻底暂停轮询，重新显示时立即强刷一次。 */
    private fun wireVisibilityTracking() {
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                service.setVisible(isShowing)
            }
        }
    }

    /**
     * 唤起外部浏览器必须放到后台线程。
     *
     * BrowserUtil.browse 会启动浏览器进程，冷启动可能耗时数秒；在 EDT 上同步调用
     * 会把整个 IDE 的界面线程占住，表现为点击后彻底卡死。EDT 上不做任何可能阻塞
     * 的事——启动外部进程正属此列。
     */
    private fun openInBrowser(url: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            BrowserUtil.browse(url)
        }
    }

    private fun selectedRunUrl(): String? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return enclosingRun(node)?.run?.htmlUrl?.ifEmpty { null }
    }

    /**
     * 发起一次加载更多。重复点击被忽略——一次请求还没回来，再点也只是白发请求。
     * 无论成败都要摘掉转圈标记：失败时引擎不发布新状态，否则那一行会一直转下去。
     */
    private fun requestLoadMore(item: LoadMoreItem) {
        val repository = item.repository ?: return
        val key = treeModel.loadingKey(repository, item.workflowName)
        if (!loadingWorkflows.add(key)) return
        refreshLoadMoreRows()
        service.loadMore(repository, item.workflowName) {
            loadingWorkflows.remove(key)
            refreshLoadMoreRows()
        }
    }

    /** 只重画「加载更多」那几行的状态。节点实例复用，展开态不受影响。 */
    private fun refreshLoadMoreRows() {
        val repositories = lastRenderedRepositories ?: return
        treeModel.applyRepositoriesTo(tree, repositories, loadingWorkflows.toSet())
    }

    private fun applyLoadingIndicator() {
        rowRenderer.loadingRuns = pendingRuns.toSet()
        tree.repaint()
    }

    private fun render(state: ViewState) {
        if (state is ViewState.WorkspaceLoaded && state.repositories.isNotEmpty()) {
            val currentRepositories = state.repositories.mapTo(HashSet()) { it.repository }
            autoExpandedRepositories.retainAll(currentRepositories)
            val repositoriesToExpand = currentRepositories - autoExpandedRepositories

            if (state.repositories != lastRenderedRepositories) {
                val expandedPaths =
                    (0 until tree.rowCount)
                        .mapNotNull { tree.getPathForRow(it) }
                        .filter { tree.isExpanded(it) }

                treeModel.applyRepositoriesTo(tree, state.repositories, loadingWorkflows.toSet())
                expandedPaths.forEach { tree.expandPath(it) }
                treeModel.expandRepositories(tree, repositoriesToExpand)
                autoExpandedRepositories.addAll(repositoriesToExpand)
                lastRenderedRepositories = state.repositories
            }
            val arrived =
                state.repositories
                    .asSequence()
                    .flatMap { it.workflows.asSequence() }
                    .flatMap { it.runs.asSequence() }
                    .filter { it.jobs != null }
                    .map { it.run.id }
                    .toSet()
            if (pendingRuns.removeAll(arrived)) applyLoadingIndicator()
            cards.show(content, CARD_TREE)
            val ago = DateFormatUtil.formatBetweenDates(state.lastUpdated.toEpochMilli(), System.currentTimeMillis())
            statusLabel.text =
                if (state.degraded) {
                    "  最后更新于 $ago · API 配额偏低，已降低刷新频率"
                } else {
                    "  最后更新于 $ago"
                }
        } else {
            autoExpandedRepositories.clear()
            lastRenderedRepositories = null
            emptyHolder.removeAll()
            emptyHolder.add(EmptyStatePanel.forState(state), BorderLayout.CENTER)
            emptyHolder.revalidate()
            emptyHolder.repaint()
            cards.show(content, CARD_EMPTY)
            statusLabel.text = " "
        }
    }
}
