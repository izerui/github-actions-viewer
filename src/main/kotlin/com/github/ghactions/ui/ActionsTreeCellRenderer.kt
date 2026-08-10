package com.github.ghactions.ui

import com.github.ghactions.model.RunStatus
import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

fun iconForStatus(status: RunStatus?): Icon? = when (status) {
    RunStatus.IN_PROGRESS -> AnimatedIcon.Default.INSTANCE
    RunStatus.QUEUED -> AllIcons.RunConfigurations.TestNotRan
    RunStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
    RunStatus.FAILURE -> AllIcons.RunConfigurations.TestFailed
    RunStatus.CANCELLED -> AllIcons.RunConfigurations.TestTerminated
    RunStatus.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
    null -> null
}

/**
 * 节点渲染。主要信息用常规色，附属信息（分支、时间）用次要色，
 * 使一眼扫过去信息层次分明。
 */
class ActionsTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val item = node.userObject as? TreeItem

        if (item == null) {
            append("WORKFLOWS", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            return
        }

        icon = iconForStatus(item.status)

        val muted = item.status == RunStatus.CANCELLED || item.status == RunStatus.SKIPPED
        val mainAttributes = when {
            item is WorkflowItem -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            muted -> SimpleTextAttributes.GRAYED_ATTRIBUTES
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(item.label, mainAttributes)

        if (item is RunItem) {
            val run = item.run
            if (run.branch.isNotEmpty()) {
                append("  ·  ${run.branch}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            if (run.updatedAt.epochSecond > 0) {
                val ago = DateFormatUtil.formatBetweenDates(run.updatedAt.toEpochMilli(), System.currentTimeMillis())
                append("  ·  $ago", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            toolTipText = run.htmlUrl.ifEmpty { null }
        }
    }
}
