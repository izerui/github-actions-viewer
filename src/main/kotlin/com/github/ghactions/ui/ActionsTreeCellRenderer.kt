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

fun iconForStatus(status: RunStatus?): Icon? =
    when (status) {
        RunStatus.IN_PROGRESS -> AnimatedIcon.Default.INSTANCE
        RunStatus.QUEUED -> AllIcons.RunConfigurations.TestNotRan
        RunStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
        RunStatus.FAILURE -> AllIcons.RunConfigurations.TestFailed
        RunStatus.CANCELLED -> AllIcons.RunConfigurations.TestTerminated
        RunStatus.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
        null -> null
    }

/**
 * 把秒数格式化成紧凑的可读形式：`45s` / `2m 30s` / `1h 5m`。
 * 只保留两级单位——再细的精度对"这步跑了多久"这个问题没有帮助。
 */
internal fun formatDuration(seconds: Long): String =
    when {
        seconds < 60 -> {
            "${seconds}s"
        }

        seconds < 3600 -> {
            val m = seconds / 60
            val s = seconds % 60
            if (s == 0L) "${m}m" else "${m}m ${s}s"
        }

        else -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m == 0L) "${h}h" else "${h}h ${m}m"
        }
    }

/**
 * 节点渲染。主要信息用常规色，附属信息（分支、时间、耗时）用次要色，
 * 使一眼扫过去信息层次分明。
 */
class ActionsTreeCellRenderer : ColoredTreeCellRenderer() {
    /** 正在等待 jobs 到达的 run id，由外层在用户点击展开时立即置上。 */
    var loadingRuns: Set<Long> = emptySet()

    /** 上一次渲染是否画了忙碌图标。 */
    var showedBusyIcon: Boolean = false
        private set

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
            append("REPOSITORIES", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            return
        }

        // 「加载更多」不是一次运行，没有状态可言：整行次要色，加载中就地转圈。
        if (item is LoadMoreItem) {
            showedBusyIcon = item.loading
            icon = if (item.loading) AnimatedIcon.Default.INSTANCE else null
            append(item.label, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return
        }

        // 正在加载 jobs 的 run 自己转圈——忙碌反馈出现在用户点击的那个对象身上，
        // 而不是另起一行告知。运行中的 run 本就是转圈图标，两者语义一致，不冲突。
        showedBusyIcon = item is RunItem && item.run.id in loadingRuns
        icon = if (showedBusyIcon) AnimatedIcon.Default.INSTANCE else iconForStatus(item.status)

        // 失败最需要被一眼看到，用主题的错误色；正在跑的加粗表示"活的"；
        // 取消与跳过退到次要色，不与真正的失败争夺注意力。
        val mainAttributes =
            when {
                item.status == RunStatus.FAILURE -> {
                    SimpleTextAttributes.ERROR_ATTRIBUTES
                }

                item is RepositoryItem || item is WorkflowItem -> {
                    SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                }

                item.status == RunStatus.IN_PROGRESS -> {
                    SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                }

                item.status == RunStatus.CANCELLED || item.status == RunStatus.SKIPPED -> {
                    SimpleTextAttributes.GRAYED_ATTRIBUTES
                }

                else -> {
                    SimpleTextAttributes.REGULAR_ATTRIBUTES
                }
            }
        append(item.label, mainAttributes)

        item.durationSeconds?.let { append("  ${formatDuration(it)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }

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
