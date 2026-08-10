package com.github.ghactions.ui

import com.github.ghactions.poll.ActionsPollingService
import com.github.ghactions.poll.ViewState
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.text.DateFormatUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeSelectionModel

private const val CARD_TREE = "tree"
private const val CARD_EMPTY = "empty"

class ActionsTreePanel(private val project: Project) : JBPanel<ActionsTreePanel>(BorderLayout()) {

    private val service = ActionsPollingService.getInstance(project)
    private val treeModel = ActionsTreeModel()
    private val tree = Tree(treeModel.swingModel)

    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val emptyHolder = JPanel(BorderLayout())
    private val statusLabel = JLabel(" ", SwingConstants.LEFT)

    private var branchFilterEnabled = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ActionsTreeCellRenderer()
        // 允许在 CellRendererPane 下自我刷新的动画图标（"运行中"转轮）真正转动。
        // AnimatedIcon.getRendererOwner 以此 client property 为闸门，不设则转轮静止。
        tree.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        content.add(JBScrollPane(tree), CARD_TREE)
        content.add(emptyHolder, CARD_EMPTY)

        add(createToolbar(), BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        wireExpansionTracking()
        wireDoubleClick()
        wireVisibilityTracking()

        // 先同步渲染一次当前状态，再订阅后续变化。
        // observe 提交的 EDT 协程要等到调度器空闲才开始 collect，这中间存在一个窗口期；
        // 若不先渲染，CardLayout 会停在初始的树卡片上，显示 Tree 组件默认的
        // "Nothing to show"，而不是我们的「正在加载…」。
        render(service.engine.state.value)
        service.observe(::render)
    }

    private fun createToolbar(): JPanel {
        val group = DefaultActionGroup(
            object : AnAction("刷新", "立即刷新", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = service.engine.requestRefresh()
            },
            object : ToggleAction("只看当前分支", "只显示当前分支的运行记录", AllIcons.Vcs.Branch) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent): Boolean = branchFilterEnabled
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    branchFilterEnabled = state
                    service.engine.setBranchFilter(state)
                }
            },
            object : AnAction("在浏览器中打开", "打开选中运行的 GitHub 页面", AllIcons.General.Web) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedRunUrl() != null
                }
                override fun actionPerformed(e: AnActionEvent) {
                    selectedRunUrl()?.let(BrowserUtil::browse)
                }
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("GitHubActionsViewer", group, true)
        toolbar.targetComponent = tree
        return JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.WEST) }
    }

    /** 展开状态直接驱动 jobs 的按需拉取——用户看什么，才请求什么。 */
    private fun wireExpansionTracking() {
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = update(event, true)
            override fun treeCollapsed(event: TreeExpansionEvent) = update(event, false)

            private fun update(event: TreeExpansionEvent, expanded: Boolean) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val item = node.userObject
                if (item is RunItem) service.setExpanded(item.run.id, expanded)
            }
        })
    }

    private fun wireDoubleClick() {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val url = selectedRunUrl() ?: return false
                BrowserUtil.browse(url)
                return true
            }
        }.installOn(tree)
    }

    /** 面板不在屏幕上显示时彻底暂停轮询，重新显示时立即强刷一次。 */
    private fun wireVisibilityTracking() {
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                service.engine.setVisible(isShowing)
            }
        }
    }

    private fun selectedRunUrl(): String? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return enclosingRun(node)?.run?.htmlUrl?.ifEmpty { null }
    }

    private fun render(state: ViewState) {
        if (state is ViewState.Loaded && state.workflows.isNotEmpty()) {
            // 视图层通用兜底：apply 前快照展开态，apply 后逐一恢复。
            // 正常路径下节点实例复用，展开态本就保持，这里是无害的幂等操作；
            // 但它同时覆盖了 ActionsTreeModel 里节点换位分支（先 remove 后 insert 会让
            // JTree 丢弃该子树展开态且不派发 treeCollapsed）以及未来任何类似的结构事件路径。
            // 因为节点实例被复用，快照下来的 TreePath 在 apply 之后依然有效，expandPath 幂等。
            val expandedPaths = (0 until tree.rowCount)
                .mapNotNull { tree.getPathForRow(it) }
                .filter { tree.isExpanded(it) }

            treeModel.apply(state.workflows)

            expandedPaths.forEach { tree.expandPath(it) }
            cards.show(content, CARD_TREE)
            val ago = DateFormatUtil.formatBetweenDates(state.lastUpdated.toEpochMilli(), System.currentTimeMillis())
            statusLabel.text = if (state.degraded) {
                "  最后更新于 $ago · API 配额偏低，已降低刷新频率"
            } else {
                "  最后更新于 $ago"
            }
        } else {
            emptyHolder.removeAll()
            emptyHolder.add(EmptyStatePanel.forState(state), BorderLayout.CENTER)
            emptyHolder.revalidate()
            emptyHolder.repaint()
            cards.show(content, CARD_EMPTY)
            statusLabel.text = " "
        }
    }
}
