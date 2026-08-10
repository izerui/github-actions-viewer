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
    }

    /**
     * 让 [parent] 的子节点与 [items] 对齐：
     * 身份相同则复用节点实例并更新数据，缺失则新建，多余则删除，顺序按 [items] 排列。
     *
     * 关键：**不能**用 [DefaultTreeModel.nodeStructureChanged]。该事件源自节点自身时，
     * `JTree` 会调用 `clearToggledPaths()` 静默清空所有展开态——用户展开的 run 每次刷新
     * 都被折叠，且不派发 `treeCollapsed`，导致轮询侧 expanded 集合只增不减。
     * 因此这里按真实差异派发精确事件：更新用 [DefaultTreeModel.nodeChanged]，
     * 新增用 [DefaultTreeModel.nodesWereInserted]，删除用 [DefaultTreeModel.nodesWereRemoved]。
     */
    private fun syncChildren(
        parent: DefaultMutableTreeNode,
        items: List<TreeItem>,
        recurse: (DefaultMutableTreeNode, Int) -> Unit,
    ) {
        // 先按身份索引现有子节点，同时记录被移除者的原始索引与实例（nodesWereRemoved 所需）。
        val existing = LinkedHashMap<String, DefaultMutableTreeNode>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            val item = child.userObject as? TreeItem ?: continue
            existing[item.id] = child
        }

        val wantedIds = items.mapTo(HashSet()) { it.id }
        val removedIndices = ArrayList<Int>()
        val removedNodes = ArrayList<DefaultMutableTreeNode>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            val item = child.userObject as? TreeItem
            if (item == null || item.id !in wantedIds) {
                removedIndices.add(i)
                removedNodes.add(child)
            }
        }

        // 先摘除多余节点。必须在移除动作发生「之后」用原始索引派发 nodesWereRemoved。
        if (removedNodes.isNotEmpty()) {
            for (node in removedNodes) parent.remove(node)
            swingModel.nodesWereRemoved(parent, removedIndices.toIntArray(), removedNodes.toTypedArray())
        }

        // 再按目标顺序对齐：复用节点做增量数据更新（nodeChanged），缺失者新建后插入（nodesWereInserted）。
        items.forEachIndexed { index, item ->
            val reused = existing[item.id]
            if (reused != null) {
                val dataChanged = reused.userObject != item
                if (reused.userObject != item) reused.userObject = item
                // 复用节点当前所在位置（删除后可能左移），需要时移动到目标位置。
                val currentIndex = parent.getIndex(reused)
                if (currentIndex != index) {
                    parent.remove(reused)
                    swingModel.nodesWereRemoved(parent, intArrayOf(currentIndex), arrayOf<Any>(reused))
                    parent.insert(reused, index)
                    swingModel.nodesWereInserted(parent, intArrayOf(index))
                } else if (dataChanged) {
                    swingModel.nodeChanged(reused)
                }
                recurse(reused, index)
            } else {
                val node = DefaultMutableTreeNode(item)
                parent.insert(node, index)
                swingModel.nodesWereInserted(parent, intArrayOf(index))
                recurse(node, index)
            }
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
