package com.github.ghactions.model

import java.time.Instant

/** GitHub 仓库坐标。 */
data class RepoCoordinates(val owner: String, val name: String) {
    override fun toString(): String = "$owner/$name"
}

/** 一次工作流运行。 */
data class WorkflowRun(
    val id: Long,
    val runNumber: Int,
    val workflowName: String,
    val branch: String,
    val status: RunStatus,
    val htmlUrl: String,
    val updatedAt: Instant,
)

/** 运行中的一个步骤。 */
data class Step(
    val number: Int,
    val name: String,
    val status: RunStatus,
)

/** 运行中的一个作业。 */
data class Job(
    val id: Long,
    val name: String,
    val status: RunStatus,
    val steps: List<Step>,
)

/** 树上的一个 run 节点。jobs 为 null 表示尚未加载（该 run 未展开）。 */
data class RunNode(
    val run: WorkflowRun,
    val jobs: List<Job>?,
)

/** 树上的一个 workflow 分组。 */
data class WorkflowNode(
    val name: String,
    val runs: List<RunNode>,
)
