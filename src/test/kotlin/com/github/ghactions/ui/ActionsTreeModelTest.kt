package com.github.ghactions.ui

import com.github.ghactions.model.Job
import com.github.ghactions.model.RunNode
import com.github.ghactions.model.RunStatus
import com.github.ghactions.model.Step
import com.github.ghactions.model.WorkflowNode
import com.github.ghactions.model.WorkflowRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode

class ActionsTreeModelTest {

    private fun run(id: Long, number: Int, status: RunStatus, name: String = "CI") = WorkflowRun(
        id = id,
        runNumber = number,
        workflowName = name,
        branch = "main",
        status = status,
        htmlUrl = "https://example.test/$id",
        updatedAt = Instant.EPOCH,
    )

    private fun job(id: Long, status: RunStatus, steps: List<Step> = emptyList()) =
        Job(id = id, name = "build", status = status, steps = steps)

    private fun child(parent: DefaultMutableTreeNode, index: Int) =
        parent.getChildAt(index) as DefaultMutableTreeNode

    @Test
    fun `构建四层树`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(
                        RunNode(
                            run(1, 419, RunStatus.IN_PROGRESS),
                            listOf(job(88, RunStatus.IN_PROGRESS, listOf(Step(1, "Checkout", RunStatus.SUCCESS)))),
                        ),
                    ),
                ),
            ),
        )

        val workflow = child(model.root, 0)
        assertEquals("CI", (workflow.userObject as WorkflowItem).name)

        val runNode = child(workflow, 0)
        assertEquals(419, (runNode.userObject as RunItem).run.runNumber)

        val jobNode = child(runNode, 0)
        assertEquals(88L, (jobNode.userObject as JobItem).job.id)

        val stepNode = child(jobNode, 0)
        assertEquals("Checkout", (stepNode.userObject as StepItem).step.name)
    }

    @Test
    fun `状态变化时节点对象被复用而非替换`() {
        val model = ActionsTreeModel()
        val before = listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419, RunStatus.IN_PROGRESS), null))))
        model.apply(before)

        val workflowNode = child(model.root, 0)
        val runNode = child(workflowNode, 0)

        val after = listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419, RunStatus.SUCCESS), null))))
        model.apply(after)

        // 同一个节点实例被保留 —— 这正是 JTree 展开态得以保持的机制
        assertSame(workflowNode, child(model.root, 0))
        assertSame(runNode, child(child(model.root, 0), 0))
        // 但承载的数据已经更新
        assertEquals(RunStatus.SUCCESS, (runNode.userObject as RunItem).run.status)
    }

    @Test
    fun `新增 run 插入而不影响既有节点`() {
        val model = ActionsTreeModel()
        model.apply(listOf(WorkflowNode("CI", listOf(RunNode(run(1, 418, RunStatus.SUCCESS), null)))))
        val oldRunNode = child(child(model.root, 0), 0)

        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(
                        RunNode(run(2, 419, RunStatus.IN_PROGRESS), null),
                        RunNode(run(1, 418, RunStatus.SUCCESS), null),
                    ),
                ),
            ),
        )

        val workflow = child(model.root, 0)
        assertEquals(2, workflow.childCount)
        assertEquals(419, (child(workflow, 0).userObject as RunItem).run.runNumber)
        assertSame(oldRunNode, child(workflow, 1))
    }

    @Test
    fun `消失的 run 被移除`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(RunNode(run(1, 418, RunStatus.SUCCESS), null), RunNode(run(2, 419, RunStatus.SUCCESS), null)),
                ),
            ),
        )

        model.apply(listOf(WorkflowNode("CI", listOf(RunNode(run(2, 419, RunStatus.SUCCESS), null)))))

        val workflow = child(model.root, 0)
        assertEquals(1, workflow.childCount)
        assertEquals(419, (child(workflow, 0).userObject as RunItem).run.runNumber)
    }

    @Test
    fun `jobs 从未加载变为已加载时挂上子节点`() {
        val model = ActionsTreeModel()
        model.apply(listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419, RunStatus.IN_PROGRESS), null)))))
        val runNode = child(child(model.root, 0), 0)
        assertEquals(0, runNode.childCount)

        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(RunNode(run(1, 419, RunStatus.IN_PROGRESS), listOf(job(88, RunStatus.SUCCESS)))),
                ),
            ),
        )

        assertSame(runNode, child(child(model.root, 0), 0))
        assertEquals(1, runNode.childCount)
    }

    @Test
    fun `不同 workflow 同名 job 的身份互不冲突`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode("CI", listOf(RunNode(run(1, 1, RunStatus.SUCCESS, "CI"), listOf(job(10, RunStatus.SUCCESS))))),
                WorkflowNode("CD", listOf(RunNode(run(2, 1, RunStatus.SUCCESS, "CD"), listOf(job(20, RunStatus.SUCCESS))))),
            ),
        )

        assertEquals(2, model.root.childCount)
        assertNotSame(
            child(child(child(model.root, 0), 0), 0),
            child(child(child(model.root, 1), 0), 0),
        )
    }

    @Test
    fun `空数据清空整棵树`() {
        val model = ActionsTreeModel()
        model.apply(listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419, RunStatus.SUCCESS), null)))))
        model.apply(emptyList())

        assertEquals(0, model.root.childCount)
    }

    @Test
    fun `enclosingRun 能从各层节点回溯所属 run`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(
                        RunNode(
                            run(1, 419, RunStatus.SUCCESS),
                            listOf(job(88, RunStatus.SUCCESS, listOf(Step(1, "Checkout", RunStatus.SUCCESS)))),
                        ),
                    ),
                ),
            ),
        )

        val workflow = child(model.root, 0)
        val runNode = child(workflow, 0)
        val jobNode = child(runNode, 0)
        val stepNode = child(jobNode, 0)

        assertEquals(1L, enclosingRun(runNode)?.run?.id)
        assertEquals(1L, enclosingRun(jobNode)?.run?.id)
        assertEquals(1L, enclosingRun(stepNode)?.run?.id)
        assertNull(enclosingRun(workflow))
        assertNull(enclosingRun(model.root))
    }
}
