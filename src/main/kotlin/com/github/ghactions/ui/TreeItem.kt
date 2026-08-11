package com.github.ghactions.ui

import com.github.ghactions.model.Job
import com.github.ghactions.model.RunStatus
import com.github.ghactions.model.Step
import com.github.ghactions.model.WorkflowRun

/**
 * 树节点承载的数据。[id] 是节点的稳定身份，
 * 差异更新据此判断「同一个节点」，从而复用节点对象、保持展开态。
 */
sealed interface TreeItem {
    val id: String
    val label: String
    val status: RunStatus?

    /** 执行耗时（秒）。尚未结束或数据缺失时为 null，渲染时不显示。 */
    val durationSeconds: Long? get() = null

    /** 之下不会再有层级。非叶子即使当前没有子节点也保持可展开。 */
    val isLeaf: Boolean get() = false
}

/**
 * workflow 分组。[latestStatus] 是它最近一次运行的状态——折叠着也能一眼看出红绿，
 * 不必展开逐个查看。它不参与 [id]，所以状态变化只会更新节点、不会重建它。
 */
data class WorkflowItem(val name: String, val latestStatus: RunStatus? = null) : TreeItem {
    override val id: String get() = "w:$name"
    override val label: String get() = name
    override val status: RunStatus? get() = latestStatus
}

data class RunItem(val run: WorkflowRun) : TreeItem {
    override val id: String get() = "r:${run.id}"
    override val label: String get() = "#${run.runNumber}"
    override val status: RunStatus get() = run.status
}

data class JobItem(val job: Job) : TreeItem {
    override val id: String get() = "j:${job.id}"
    override val label: String get() = job.name
    override val status: RunStatus get() = job.status
    override val durationSeconds: Long? get() = job.durationSeconds
}

/**
 * 挂在每个 workflow 的 run 之后的「加载更多」。
 *
 * [id] 只由 [workflowName] 决定，[loading] 变化因此走的是节点数据更新而非重建，
 * 这一行不会在点击瞬间闪烁。
 */
data class LoadMoreItem(val workflowName: String, val loading: Boolean) : TreeItem {
    override val isLeaf: Boolean get() = true
    override val id: String get() = "more:$workflowName"
    override val label: String get() = if (loading) "加载中…" else "加载更多"
    override val status: RunStatus? get() = null
}

data class StepItem(val jobId: Long, val step: Step) : TreeItem {
    override val isLeaf: Boolean get() = true
    override val id: String get() = "s:$jobId:${step.number}"
    override val label: String get() = step.name
    override val status: RunStatus get() = step.status
    override val durationSeconds: Long? get() = step.durationSeconds
}
