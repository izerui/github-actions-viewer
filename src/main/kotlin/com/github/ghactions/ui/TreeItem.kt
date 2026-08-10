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
}

data class StepItem(val jobId: Long, val step: Step) : TreeItem {
    override val id: String get() = "s:$jobId:${step.number}"
    override val label: String get() = step.name
    override val status: RunStatus get() = step.status
}
