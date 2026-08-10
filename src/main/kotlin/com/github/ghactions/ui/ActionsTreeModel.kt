package com.github.ghactions.ui

import com.github.ghactions.model.WorkflowNode
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * 按稳定身份做差异更新的树模型。
 *
 * 关键约束：状态刷新时**必须复用**已存在的节点对象。只要节点实例没有被
 * remove/insert，JTree 的展开状态与选中状态就天然保持——这是每 5 秒刷新
 * 却不打断用户的唯一办法。
 */
class ActionsTreeModel {

    val root: DefaultMutableTreeNode = DefaultMutableTreeNode("WORKFLOWS")

    /**
     * `asksAllowsChildren = true` 让 JTree 按「是否允许有子节点」判定叶子，
     * 而不是看「当前有没有子节点」。
     *
     * 这一点是必需的：run 的 jobs 要等用户展开时才去拉，展开前它没有子节点。
     * 若按默认规则，它会被当成叶子而不显示展开箭头——用户无从展开，也就永远
     * 触发不了拉取，形成死结。
     */
    val swingModel: DefaultTreeModel = DefaultTreeModel(root, true)

    fun apply(workflows: List<WorkflowNode>) {
        syncChildren(root, workflows.map { WorkflowItem(it.name, it.runs.firstOrNull()?.run?.status) }) { node, index ->
            val workflow = workflows[index]
            syncChildren(node, workflow.runs.map { RunItem(it.run) }) { runNode, runIndex ->
                val runData = workflow.runs[runIndex]
                val jobs = runData.jobs
                // jobs 为 null 表示尚未拉取——挂一个「正在加载」占位，
                // 让用户展开后立刻有反馈，而不是对着一片空白猜。
                val children = jobs?.map { JobItem(it) } ?: listOf(LoadingItem(runData.run.id))
                syncChildren(runNode, children) { jobNode, jobIndex ->
                    val job = jobs?.getOrNull(jobIndex) ?: return@syncChildren
                    syncChildren(jobNode, job.steps.map { StepItem(job.id, it) }) { _, _ -> }
                }
            }
        }
    }

    /**
     * 把数据应用到 [tree] 上。
     *
     * 除了 [apply] 的差异更新，还必须确保 root 处于展开状态：树是 `isRootVisible = false`
     * 的，JTree 只有在 root 展开时才会渲染它的子节点。root 初始是叶子节点，通过
     * `nodesWereInserted` 插入第一批子节点并不会让 JTree 自动展开它——于是数据在模型里，
     * 界面上却一行都看不到。
     *
     * （早先 `apply` 末尾的 `nodeStructureChanged(root)` 顺带产生过展开 root 的副作用，
     * 但它同时会清空 JTree 的展开态，已被移除；展开 root 的职责因此需要在这里显式承担。）
     */
    fun applyTo(tree: JTree, workflows: List<WorkflowNode>) {
        apply(workflows)
        tree.expandPath(TreePath(root))
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
                // step 与加载占位是真正的叶子；workflow / run / job 都可能有下一层，
                // 即使此刻尚未加载出来也必须保持可展开（模型已启用 asksAllowsChildren）。
                val node = DefaultMutableTreeNode(item, !item.isLeaf)
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
