package com.github.ghactions.ui

import com.github.ghactions.model.RunStatus
import com.github.ghactions.model.WorkflowRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class ActionsRowRendererTest {

    private val renderer = ActionsRowRenderer()
    private val tree = JTree()

    private fun runNode(url: String = "https://example.test/1") = DefaultMutableTreeNode(
        RunItem(
            WorkflowRun(
                id = 1,
                runNumber = 419,
                workflowName = "CI",
                branch = "main",
                status = RunStatus.SUCCESS,
                htmlUrl = url,
                updatedAt = Instant.EPOCH,
            ),
        ),
    )

    private fun render(node: DefaultMutableTreeNode, row: Int) =
        renderer.getTreeCellRendererComponent(tree, node, false, false, false, row, false)

    @Test
    fun `标记为加载中的 run 显示忙碌图标`() {
        val node = runNode()

        renderer.loadingRuns = setOf(1L)
        render(node, 0)
        assertTrue(renderer.isShowingBusyIcon, "点击展开的那一刻就该转圈，不能等数据回来才转")

        renderer.loadingRuns = emptySet()
        render(node, 0)
        assertTrue(!renderer.isShowingBusyIcon, "数据到达后应恢复为状态图标")
    }

    @Test
    fun `悬停与否不应改变行宽`() {
        val node = runNode()

        renderer.hoveredRow = -1
        render(node, 0)
        val widthWithoutHover = renderer.preferredSize.width

        renderer.hoveredRow = 0
        render(node, 0)
        val widthWithHover = renderer.preferredSize.width

        // 若按钮只在悬停时才占位，鼠标划过树时行宽会忽宽忽窄、整棵树横向抖动
        assertEquals(
            widthWithoutHover,
            widthWithHover,
            "悬停不应改变行宽，否则鼠标划过时整棵树会抖动",
        )
    }

    @Test
    fun `悬停在 run 行时按钮占据可点击宽度`() {
        val node = runNode()

        renderer.hoveredRow = 0
        render(node, 0)

        assertTrue(renderer.actionWidth() > 0, "run 行应有可点击的按钮区域")
    }

    @Test
    fun `没有链接的 run 不显示按钮`() {
        val node = runNode(url = "")

        renderer.hoveredRow = 0
        render(node, 0)

        assertEquals(0, renderer.actionWidth(), "没有链接就不该给出可点击区域")
    }

    @Test
    fun `截断提示行不带状态图标也不占按钮宽度`() {
        val node = DefaultMutableTreeNode(TruncationNoticeItem(15, "https://github.com/o/r/actions"))

        renderer.hoveredRow = 0
        val cellRenderer = ActionsTreeCellRenderer()
        cellRenderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false)
        render(node, 0)

        assertEquals(null, cellRenderer.icon, "提示行不是一次运行，不该有状态图标")
        assertEquals(0, renderer.actionWidth(), "提示行整行可点，不需要右侧按钮")
    }

    @Test
    fun `非 run 行不占用按钮宽度`() {
        val node = DefaultMutableTreeNode(WorkflowItem("CI", RunStatus.SUCCESS))

        renderer.hoveredRow = 0
        render(node, 0)

        assertEquals(0, renderer.actionWidth(), "只有 run 行需要这个按钮")
    }
}
