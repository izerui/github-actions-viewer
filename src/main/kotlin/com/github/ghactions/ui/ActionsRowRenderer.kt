package com.github.ghactions.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * 整行渲染：左侧是状态图标与文本，右侧是鼠标悬停时出现的「在浏览器中打开」按钮。
 *
 * 之所以需要这层包装：[ActionsTreeCellRenderer] 继承自 SimpleColoredComponent，
 * 而后者只有一个图标槽位，已经被状态图标占用，无法再在右侧放第二个图标。
 *
 * 关键细节：按钮位置**始终占位**，悬停时只切换图标显不显示。否则鼠标划过时行宽
 * 会忽宽忽窄，整棵树跟着横向抖动。
 */
class ActionsRowRenderer : JPanel(BorderLayout()), TreeCellRenderer {

    private val text = ActionsTreeCellRenderer()
    private val actionIcon = JLabel(EMPTY)

    /** 当前鼠标所在行，由 TreeHoverListener 更新。-1 表示鼠标不在树上。 */
    var hoveredRow: Int = -1

    init {
        isOpaque = false
        actionIcon.border = JBUI.Borders.empty(0, 6)
        add(text, BorderLayout.CENTER)
        add(actionIcon, BorderLayout.EAST)
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

    /** 右侧按钮占据的宽度，供命中判断使用；该行没有按钮时为 0。 */
    fun actionWidth(): Int = if (actionIcon.isVisible) actionIcon.preferredSize.width else 0

    private companion object {
        val EMPTY: javax.swing.Icon = EmptyIcon.ICON_16
    }
}
