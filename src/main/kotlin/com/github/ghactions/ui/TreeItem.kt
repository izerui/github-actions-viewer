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
 * 树末尾的截断提示，与 workflow 分组平级挂在根下。
 *
 * 每轮只拉取最近 [limit] 条运行记录，列表末尾若什么都不说，下方那片空白会让人
 * 以为数据没加载全。[id] 恒定，因此在差异更新中被复用而非每轮重建。
 */
data class TruncationNoticeItem(val limit: Int, val actionsUrl: String) : TreeItem {
    override val isLeaf: Boolean get() = true
    override val id: String get() = "notice"
    override val label: String get() = "仅显示最近 $limit 次运行，在 GitHub 上查看全部 ›"
    override val status: RunStatus? get() = null
}

data class StepItem(val jobId: Long, val step: Step) : TreeItem {
    override val isLeaf: Boolean get() = true
    override val id: String get() = "s:$jobId:${step.number}"
    override val label: String get() = step.name
    override val status: RunStatus get() = step.status
    override val durationSeconds: Long? get() = step.durationSeconds
}
