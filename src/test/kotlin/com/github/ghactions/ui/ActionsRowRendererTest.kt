package com.github.ghactions.ui

import com.github.ghactions.model.RunStatus
import com.github.ghactions.model.WorkflowRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.time.Instant
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ActionsRowRendererTest {
    private val renderer = ActionsRowRenderer()
    private val tree = JTree()

    private fun runNode(url: String = "https://example.test/1") =
        DefaultMutableTreeNode(
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

    private fun render(
        node: DefaultMutableTreeNode,
        row: Int,
    ) = renderer.getTreeCellRendererComponent(tree, node, false, false, false, row, false)

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
    fun `打开按钮紧跟文字且命中区域与图标一致`() {
        val node = runNode()

        renderer.hoveredRow = 0
        render(node, 0)

        val actionStart = renderer.actionOffset()
        val actionWidth = renderer.actionWidth()
        assertEquals(
            renderer.preferredSize.width - actionWidth,
            actionStart,
            "按钮应紧跟文字，而不是被推到整行最右侧",
        )
        assertTrue(!renderer.isActionAt(actionStart - 1), "文字区域不应触发打开链接")
        assertTrue(renderer.isActionAt(actionStart), "按钮左边界应可点击")
        assertTrue(renderer.isActionAt(actionStart + actionWidth - 1), "按钮右边界内应可点击")
        assertTrue(!renderer.isActionAt(actionStart + actionWidth), "按钮右边界外不应触发打开链接")
    }

    @Test
    fun `真实树中的打开按钮可命中并打开对应链接`() {
        SwingUtilities.invokeAndWait {
            val url = "https://example.test/1"
            val node = runNode(url)
            val trailingNode = DefaultMutableTreeNode(WorkflowItem("trailing", RunStatus.SUCCESS))
            val root =
                DefaultMutableTreeNode("root").apply {
                    add(node)
                    add(trailingNode)
                }
            val actualRenderer = ActionsRowRenderer()
            val actualTree =
                JTree(DefaultTreeModel(root)).apply {
                    isRootVisible = false
                    cellRenderer = actualRenderer
                    setSize(600, 200)
                    doLayout()
                }
            val row = 0
            actualRenderer.hoveredRow = row
            val image = BufferedImage(actualTree.width, actualTree.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                actualTree.paint(graphics)
            } finally {
                graphics.dispose()
            }

            assertEquals(0, actualRenderer.actionWidth(), "真实绘制结束后 renderer 应停在后续非 run 行")
            val rowBounds = requireNotNull(actualTree.getRowBounds(row))
            val iconCenterX = rowBounds.x + rowBounds.width - actualRenderer.actionSlotWidth() / 2
            val iconCenterY = rowBounds.y + rowBounds.height / 2

            assertTrue(iconCenterX in rowBounds.x until rowBounds.x + rowBounds.width, "图标中心必须位于真实行边界内")
            assertEquals(row, actualTree.getRowForLocation(iconCenterX, iconCenterY), "JTree 应能在图标坐标命中该行")

            var openedUrl: String? = null
            actualTree.addMouseListener(
                createTreeActionMouseListener(
                    tree = actualTree,
                    rowRenderer = actualRenderer,
                    openInBrowser = { openedUrl = it },
                    requestLoadMore = { error("run 链接点击不应触发加载更多") },
                ),
            )
            actualTree.dispatchEvent(
                MouseEvent(
                    actualTree,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    iconCenterX,
                    iconCenterY,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )

            assertEquals(url, openedUrl, "点击图标应把该 run 的 URL 交给浏览器回调")
        }
    }

    @Test
    fun `没有链接的 run 不显示按钮`() {
        val node = runNode(url = "")

        renderer.hoveredRow = 0
        render(node, 0)

        assertEquals(0, renderer.actionWidth(), "没有链接就不该给出可点击区域")
    }

    @Test
    fun `加载中的加载更多行显示忙碌图标`() {
        val cellRenderer = ActionsTreeCellRenderer()

        cellRenderer.getTreeCellRendererComponent(
            tree,
            DefaultMutableTreeNode(LoadMoreItem("CI", loading = false)),
            false,
            false,
            true,
            0,
            false,
        )
        assertEquals(null, cellRenderer.icon, "空闲时不占图标位")

        cellRenderer.getTreeCellRendererComponent(
            tree,
            DefaultMutableTreeNode(LoadMoreItem("CI", loading = true)),
            false,
            false,
            true,
            0,
            false,
        )
        // 点击到数据回来要好几秒，反馈必须出现在用户点的那一行
        assertTrue(cellRenderer.icon != null, "加载中应有忙碌图标")
    }

    @Test
    fun `加载更多行不占用按钮宽度`() {
        val node = DefaultMutableTreeNode(LoadMoreItem("CI", loading = false))

        renderer.hoveredRow = 0
        render(node, 0)

        assertEquals(0, renderer.actionWidth(), "整行可点，不需要右侧按钮")
    }

    @Test
    fun `非 run 行不占用按钮宽度`() {
        val node = DefaultMutableTreeNode(WorkflowItem("CI", RunStatus.SUCCESS))

        renderer.hoveredRow = 0
        render(node, 0)

        assertEquals(0, renderer.actionWidth(), "只有 run 行需要这个按钮")
    }
}
