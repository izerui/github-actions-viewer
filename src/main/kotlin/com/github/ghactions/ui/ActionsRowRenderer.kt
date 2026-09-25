package com.github.ghactions.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * 行内渲染：左侧是状态图标与文本，后面紧跟鼠标悬停时出现的「在浏览器中打开」按钮。
 *
 * 之所以需要这层包装：[ActionsTreeCellRenderer] 继承自 SimpleColoredComponent，
 * 而后者只有一个图标槽位，已经被状态图标占用，无法再在文本后放第二个图标。
 *
 * 关键细节：按钮位置**始终占位**，悬停时只切换图标显不显示。否则鼠标划过时行宽
 * 会忽宽忽窄，整棵树跟着横向抖动。
 */
class ActionsRowRenderer :
    JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)),
    TreeCellRenderer {
    private val text = ActionsTreeCellRenderer()
    private val actionIcon = JLabel(EMPTY)

    /** 当前鼠标所在行，由 TreeHoverListener 更新。-1 表示鼠标不在树上。 */
    var hoveredRow: Int = -1

    /**
     * 正在等待 jobs 到达的 run。
     *
     * 由 UI 在用户点击展开的那一刻立即置上，而不是等轮询回来告知——轮询回来时
     * 要等的事情已经结束了，那个标记永远不会为真。
     */
    var loadingRuns: Set<Long> = emptySet()
        set(value) {
            field = value
            text.loadingRuns = value
        }

    init {
        isOpaque = false
        actionIcon.border = JBUI.Borders.empty(0, 6)
        add(text)
        add(actionIcon)
    }

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        text.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

        val item = (value as? DefaultMutableTreeNode)?.userObject
        val openable = item is RunItem && item.run.htmlUrl.isNotEmpty()
        // 只有 run 行需要这个按钮，也只有它保留占位——其余行不必平白多出一段空白。
        actionIcon.isVisible = openable
        actionIcon.icon = if (openable && row == hoveredRow) AllIcons.General.Web else EMPTY

        return this
    }

    /** 上一次渲染是否画的是忙碌图标。供测试断言用。 */
    val isShowingBusyIcon: Boolean get() = text.showedBusyIcon

    /** 按钮在渲染器内的横向起点；图标紧跟在文本组件后面。 */
    fun actionOffset(): Int = text.preferredSize.width

    /** 横向坐标是否落在指定节点的按钮区域内。点击判断不得依赖 renderer 上一次绘制的行。 */
    fun isActionAt(
        tree: JTree,
        node: DefaultMutableTreeNode,
        row: Int,
        horizontalOffset: Int,
    ): Boolean {
        getTreeCellRendererComponent(
            tree,
            node,
            tree.isRowSelected(row),
            tree.isExpanded(row),
            node.isLeaf,
            row,
            tree.hasFocus(),
        )
        return isActionAt(horizontalOffset)
    }

    /** 横向坐标是否落在当前已配置行的按钮区域内。 */
    fun isActionAt(horizontalOffset: Int): Boolean {
        val width = actionWidth()
        return width > 0 && horizontalOffset >= actionOffset() && horizontalOffset < actionOffset() + width
    }

    /** 图标槽固定宽度，不受当前 renderer 正在表示哪一行影响。 */
    fun actionSlotWidth(): Int = actionIcon.preferredSize.width

    /** 按钮占据的宽度；当前已配置行没有按钮时为 0。 */
    fun actionWidth(): Int = if (actionIcon.isVisible) actionSlotWidth() else 0

    private companion object {
        val EMPTY: javax.swing.Icon = EmptyIcon.ICON_16
    }
}
