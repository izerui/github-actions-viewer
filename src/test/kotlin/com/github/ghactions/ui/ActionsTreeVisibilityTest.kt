package com.github.ghactions.ui

import com.github.ghactions.model.Job
import com.github.ghactions.model.RunNode
import com.github.ghactions.model.RunStatus
import com.github.ghactions.model.Step
import com.github.ghactions.model.WorkflowNode
import com.github.ghactions.model.WorkflowRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.swing.JTree

/**
 * 树在真实 JTree 上是否**看得见**。
 *
 * 这一格此前是测试矩阵的盲区：ActionsTreeModelTest 只验证 DefaultTreeModel 侧的
 * 节点复用，全绿也无法说明界面上真的渲染出了东西。真机验证时表现为
 * 「日志显示 Loaded、界面却一行没有」。
 */
class ActionsTreeVisibilityTest {

    private fun run(id: Long, number: Int, status: RunStatus = RunStatus.SUCCESS) = WorkflowRun(
        id = id,
        runNumber = number,
        workflowName = "CI",
        branch = "main",
        status = status,
        htmlUrl = "https://example.test/$id",
        updatedAt = Instant.EPOCH,
    )

    private fun treeWithModel(): Pair<JTree, ActionsTreeModel> {
        val model = ActionsTreeModel()
        val tree = JTree(model.swingModel)
        // 故意设为不可见：这样 rowCount > 0 才能严格证明「root 被展开了」。
        // 面板本身现在显示 WORKFLOWS 根节点，但 applyTo 展开 root 的职责与该配置无关，
        // 用 false 能让断言更严格——root 若未展开，rowCount 会是 0 而非 1。
        tree.isRootVisible = false
        return tree to model
    }

    @Test
    fun `首次填充后树上必须有可见的行`() {
        val (tree, model) = treeWithModel()

        model.applyTo(tree, listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419), null)))))

        // root 是 isRootVisible=false 的叶子节点，仅靠 nodesWereInserted 不会让 JTree
        // 展开它——不显式展开的话数据在模型里但界面上一行都看不到。
        assertTrue(tree.rowCount > 0, "树填充后应有可见行，实际 rowCount=${tree.rowCount}")
    }

    @Test
    fun `jobs 尚未加载的 run 仍然可以展开`() {
        val (tree, model) = treeWithModel()

        // jobs = null 表示还没加载——它正是「等用户展开时才去拉」的初始状态
        model.applyTo(tree, listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419), null)))))

        val workflowNode = model.root.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode
        val runNode = workflowNode.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode

        // JTree 默认按「有没有子节点」判定叶子。run 的 jobs 要展开后才拉，
        // 展开前没有子节点，于是被当成叶子、不显示展开箭头——用户根本无从展开，
        // 也就永远触发不了拉取。这是个死结，必须让 run 始终可展开。
        assertTrue(
            !model.swingModel.isLeaf(runNode),
            "jobs 未加载的 run 必须仍可展开，否则用户没有展开箭头可点",
        )
    }

    @Test
    fun `step 是叶子不应显示展开箭头`() {
        val (tree, model) = treeWithModel()
        val jobs = listOf(Job(88, "build", RunStatus.SUCCESS, listOf(Step(1, "Checkout", RunStatus.SUCCESS))))

        model.applyTo(tree, listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419), jobs)))))

        val workflowNode = model.root.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode
        val runNode = workflowNode.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode
        val jobNode = runNode.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode
        val stepNode = jobNode.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode

        // 启用 asksAllowsChildren 后，若不区分 step，所有节点都会带上展开箭头
        assertTrue(model.swingModel.isLeaf(stepNode), "step 之下没有层级，不该显示展开箭头")
        assertTrue(!model.swingModel.isLeaf(jobNode), "job 之下有 step，应可展开")
    }

    @Test
    fun `workflow 与其下的 run 都应可见`() {
        val (tree, model) = treeWithModel()

        model.applyTo(
            tree,
            listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419), null), RunNode(run(2, 418), null)))),
        )
        // 展开 workflow 节点后，两个 run 都应出现
        tree.expandRow(0)

        assertEquals(3, tree.rowCount, "应为 1 个 workflow + 2 个 run")
    }

    @Test
    fun `多轮刷新后树始终保持可见`() {
        val (tree, model) = treeWithModel()

        repeat(3) { round ->
            model.applyTo(tree, listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419 + round), null)))))
            assertTrue(tree.rowCount > 0, "第 ${round + 1} 轮刷新后树不应变为不可见")
        }
    }

    @Test
    fun `刷新不会打断已展开的节点`() {
        val (tree, model) = treeWithModel()
        val jobs = listOf(Job(88, "build", RunStatus.SUCCESS, listOf(Step(1, "Checkout", RunStatus.SUCCESS))))

        model.applyTo(tree, listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419, RunStatus.IN_PROGRESS), jobs)))))
        tree.expandRow(0)
        val runPath = tree.getPathForRow(1)
        tree.expandPath(runPath)
        assertTrue(tree.isExpanded(runPath))

        // 模拟下一轮轮询：状态变了，但展开态必须保持
        model.applyTo(tree, listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419, RunStatus.SUCCESS), jobs)))))

        assertTrue(tree.isExpanded(runPath), "刷新后展开态丢失——盯流水线的核心场景会被打断")
    }
}
