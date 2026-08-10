package com.github.ghactions.ui

import com.github.ghactions.model.WorkflowNode
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * 按稳定身份做差异更新的树模型。
 *
 * 关键约束：状态刷新时**必须复用**已存在的节点对象。只要节点实例没有被
 * remove/insert，JTree 的展开状态与选中状态就天然保持——这是每 5 秒刷新
 * 却不打断用户的唯一办法。
 */
class ActionsTreeModel {

    val root: DefaultMutableTreeNode = DefaultMutableTreeNode("WORKFLOWS")
    val swingModel: DefaultTreeModel = DefaultTreeModel(root)

    fun apply(workflows: List<WorkflowNode>) {
        syncChildren(root, workflows.map { WorkflowItem(it.name) }) { node, index ->
            val workflow = workflows[index]
            syncChildren(node, workflow.runs.map { RunItem(it.run) }) { runNode, runIndex ->
                val jobs = workflow.runs[runIndex].jobs.orEmpty()
                syncChildren(runNode, jobs.map { JobItem(it) }) { jobNode, jobIndex ->
                    val job = jobs[jobIndex]
                    syncChildren(jobNode, job.steps.map { StepItem(job.id, it) }) { _, _ -> }
                }
            }
        }
        swingModel.nodeStructureChanged(root)
    }

    /**
     * 让 [parent] 的子节点与 [items] 对齐：
     * 身份相同则复用节点实例并更新数据，缺失则新建，多余则删除，顺序按 [items] 排列。
     */
    private fun syncChildren(
        parent: DefaultMutableTreeNode,
        items: List<TreeItem>,
        recurse: (DefaultMutableTreeNode, Int) -> Unit,
    ) {
        val existing = LinkedHashMap<String, DefaultMutableTreeNode>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            val item = child.userObject as? TreeItem ?: continue
            existing[item.id] = child
        }

        parent.removeAllChildren()

        items.forEachIndexed { index, item ->
            val node = existing.remove(item.id)?.also { it.userObject = item }
                ?: DefaultMutableTreeNode(item)
            parent.add(node)
            recurse(node, index)
        }
    }
}

/** 从任意节点回溯其所属的 run；workflow 节点与根节点返回 null。 */
fun enclosingRun(node: DefaultMutableTreeNode): RunItem? {
    var current: DefaultMutableTreeNode? = node
    while (current != null) {
        val item = current.userObject
        if (item is RunItem) return item
        current = current.parent as? DefaultMutableTreeNode
    }
    return null
}
