package com.github.ghactions.ui

import com.github.ghactions.model.Job
import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.model.RepositoryNode
import com.github.ghactions.model.RunNode
import com.github.ghactions.model.RunStatus
import com.github.ghactions.model.Step
import com.github.ghactions.model.WorkflowNode
import com.github.ghactions.model.WorkflowRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class ActionsTreeModelTest {
    private fun run(
        id: Long,
        number: Int,
        status: RunStatus,
        name: String = "CI",
    ) = WorkflowRun(
        id = id,
        runNumber = number,
        workflowName = name,
        branch = "main",
        status = status,
        htmlUrl = "https://example.test/$id",
        updatedAt = Instant.EPOCH,
    )

    private fun job(
        id: Long,
        status: RunStatus,
        steps: List<Step> = emptyList(),
    ) = Job(id = id, name = "build", status = status, steps = steps)

    private fun child(
        parent: DefaultMutableTreeNode,
        index: Int,
    ) = parent.getChildAt(index) as DefaultMutableTreeNode

    @Test
    fun `多个仓库各自显示为顶层节点`() {
        val model = ActionsTreeModel()
        val first = RepoCoordinates("modexai", "maas-api")
        val second = RepoCoordinates("izerui", "github-actions-viewer")

        model.applyRepositories(
            listOf(
                RepositoryNode(first, listOf(WorkflowNode("CI", listOf(RunNode(run(1, 10, RunStatus.SUCCESS), null))))),
                RepositoryNode(second, listOf(WorkflowNode("Build", listOf(RunNode(run(2, 20, RunStatus.SUCCESS), null))))),
            ),
        )

        assertEquals(2, model.root.childCount)
        val firstRepository = child(model.root, 0)
        val secondRepository = child(model.root, 1)
        assertEquals(first, (firstRepository.userObject as RepositoryItem).repository)
        assertEquals(second, (secondRepository.userObject as RepositoryItem).repository)
        assertEquals("CI", (child(firstRepository, 0).userObject as WorkflowItem).name)
        assertEquals("Build", (child(secondRepository, 0).userObject as WorkflowItem).name)
    }

    @Test
    fun `同名 workflow 的加载状态按仓库隔离`() {
        val model = ActionsTreeModel()
        val first = RepoCoordinates("one", "repo")
        val second = RepoCoordinates("two", "repo")
        val workflow =
            WorkflowNode(
                "CI",
                listOf(RunNode(run(1, 10, RunStatus.SUCCESS), null)),
                workflowId = 99,
                canLoadMore = true,
            )

        model.applyRepositories(
            listOf(RepositoryNode(first, listOf(workflow)), RepositoryNode(second, listOf(workflow))),
            loadingWorkflows = setOf(model.loadingKey(first, "CI")),
        )

        val firstMore = child(child(child(model.root, 0), 0), 1).userObject as LoadMoreItem
        val secondMore = child(child(child(model.root, 1), 0), 1).userObject as LoadMoreItem
        assertTrue(firstMore.loading)
        assertTrue(!secondMore.loading)
        assertEquals(first, firstMore.repository)
        assertEquals(second, secondMore.repository)
    }

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
        // jobs 尚未加载时没有子节点；忙碌反馈由 run 自身的图标承担（见 ActionsRowRenderer.loadingRuns）
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
        assertTrue(child(runNode, 0).userObject is JobItem, "占位应被真实 job 替换")
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
    fun `刷新后真实 JTree 的展开态得以保持`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(RunNode(run(1, 419, RunStatus.IN_PROGRESS), listOf(job(88, RunStatus.IN_PROGRESS)))),
                ),
            ),
        )

        // 用真实 JTree 承载模型（纯 JVM，headless 下可用），并展开 workflow 与 run。
        val tree = JTree(model.swingModel)
        val workflowNode = child(model.root, 0)
        val runNode = child(workflowNode, 0)
        val workflowPath = TreePath(arrayOf(model.root, workflowNode))
        val runPath = TreePath(arrayOf(model.root, workflowNode, runNode))
        tree.expandPath(workflowPath)
        tree.expandPath(runPath)
        assertTrue(tree.isExpanded(workflowPath), "前置条件：workflow 应已展开")
        assertTrue(tree.isExpanded(runPath), "前置条件：run 应已展开")

        // 模拟一轮 5 秒刷新：状态从 IN_PROGRESS 变为 SUCCESS，节点实例复用。
        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(RunNode(run(1, 419, RunStatus.SUCCESS), listOf(job(88, RunStatus.SUCCESS)))),
                ),
            ),
        )

        // 核心断言：JTree 侧的展开态未被静默清空（旧实现的 nodeStructureChanged(root) 会清空）。
        assertSame(runNode, child(child(model.root, 0), 0))
        assertTrue(tree.isExpanded(workflowPath), "刷新后 workflow 仍应展开")
        assertTrue(tree.isExpanded(runPath), "刷新后 run 仍应展开")
    }

    @Test
    fun `节点换位后视图层兜底恢复展开态`() {
        // 复现节点换位分支：先构造 A、B 两个 workflow，展开 B，再让 B 换到 index 0。
        // ActionsTreeModel 换位走 remove+insert，JTree 会丢弃 B 子树的展开态且不派发
        // treeCollapsed。这里验证 render() 里「apply 前快照、apply 后 expandPath」的兜底能救回。
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode("A", listOf(RunNode(run(1, 1, RunStatus.SUCCESS, "A"), listOf(job(10, RunStatus.SUCCESS))))),
                WorkflowNode("B", listOf(RunNode(run(2, 1, RunStatus.SUCCESS, "B"), listOf(job(20, RunStatus.SUCCESS))))),
            ),
        )

        val tree = JTree(model.swingModel)
        val bNode = child(model.root, 1)
        val bPath = TreePath(arrayOf<Any>(model.root, bNode))
        tree.expandPath(bPath)
        assertTrue(tree.isExpanded(bPath), "前置条件：B 应已展开")

        // 模拟 render()：apply 前快照展开态。
        val expandedPaths =
            (0 until tree.rowCount)
                .mapNotNull { tree.getPathForRow(it) }
                .filter { tree.isExpanded(it) }

        // 让 B 换到 index 0（A、B 顺序互换），触发 ActionsTreeModel 的换位分支。
        model.apply(
            listOf(
                WorkflowNode("B", listOf(RunNode(run(2, 1, RunStatus.SUCCESS, "B"), listOf(job(20, RunStatus.SUCCESS))))),
                WorkflowNode("A", listOf(RunNode(run(1, 1, RunStatus.SUCCESS, "A"), listOf(job(10, RunStatus.SUCCESS))))),
            ),
        )

        // 节点实例被复用，B 现在位于 index 0。
        assertSame(bNode, child(model.root, 0))

        // 视图层兜底：apply 后逐一 expandPath。因节点复用，快照的 TreePath 依旧有效。
        expandedPaths.forEach { tree.expandPath(it) }

        assertTrue(tree.isExpanded(bPath), "换位并兜底后 B 仍应展开")
    }

    @Test
    fun `可加载更多的 workflow 在 run 之后多出一行`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(RunNode(run(1, 419, RunStatus.SUCCESS), null)),
                    workflowId = 98765,
                    canLoadMore = true,
                ),
            ),
        )

        val workflow = child(model.root, 0)
        assertEquals(2, workflow.childCount)
        assertTrue(child(workflow, 0).userObject is RunItem, "run 排在前")
        val more = child(workflow, 1).userObject
        assertTrue(more is LoadMoreItem, "「加载更多」应排在所有 run 之后，实际是 $more")
        assertEquals("CI", (more as LoadMoreItem).workflowName)
    }

    @Test
    fun `已到底的 workflow 不再显示加载更多`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(RunNode(run(1, 419, RunStatus.SUCCESS), null)),
                    workflowId = 98765,
                    canLoadMore = false,
                ),
            ),
        )

        assertEquals(1, child(model.root, 0).childCount)
    }

    @Test
    fun `加载更多的节点在刷新时被复用`() {
        val model = ActionsTreeModel()
        val workflows =
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(RunNode(run(1, 419, RunStatus.IN_PROGRESS), null)),
                    workflowId = 98765,
                    canLoadMore = true,
                ),
            )
        model.apply(workflows)
        val moreNode = child(child(model.root, 0), 1)

        model.apply(workflows)

        // 每轮删了重建会让这一行在刷新瞬间闪烁
        assertSame(moreNode, child(child(model.root, 0), 1))
    }

    @Test
    fun `加载中的标记只更新节点数据，不更换节点实例`() {
        val model = ActionsTreeModel()
        val workflow =
            WorkflowNode(
                "CI",
                listOf(RunNode(run(1, 419, RunStatus.SUCCESS), null)),
                workflowId = 98765,
                canLoadMore = true,
            )
        model.apply(listOf(workflow))
        val moreNode = child(child(model.root, 0), 1)

        model.apply(listOf(workflow), loadingWorkflows = setOf("CI"))

        assertSame(moreNode, child(child(model.root, 0), 1))
        assertTrue((moreNode.userObject as LoadMoreItem).loading, "该行应进入加载中")
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
