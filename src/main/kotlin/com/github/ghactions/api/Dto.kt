package com.github.ghactions.api

import com.github.ghactions.model.Job
import com.github.ghactions.model.RunStatus
import com.github.ghactions.model.Step
import com.github.ghactions.model.WorkflowRun
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.time.Instant

private val GSON = Gson()

private const val UNNAMED_WORKFLOW = "(未命名工作流)"

// Gson 通过反射构造对象，不保证 Kotlin 的非空契约，
// 因此所有 DTO 字段一律 nullable，由映射函数负责收敛。

internal class RunsResponseDto {
    @SerializedName("workflow_runs")
    var workflowRuns: List<RunDto>? = null
}

internal class RunDto {
    @SerializedName("id") var id: Long? = null
    @SerializedName("run_number") var runNumber: Int? = null
    @SerializedName("name") var name: String? = null
    @SerializedName("head_branch") var headBranch: String? = null
    @SerializedName("status") var status: String? = null
    @SerializedName("conclusion") var conclusion: String? = null
    @SerializedName("html_url") var htmlUrl: String? = null
    @SerializedName("updated_at") var updatedAt: String? = null
}

internal class JobsResponseDto {
    @SerializedName("jobs")
    var jobs: List<JobDto>? = null
}

internal class JobDto {
    @SerializedName("id") var id: Long? = null
    @SerializedName("name") var name: String? = null
    @SerializedName("status") var status: String? = null
    @SerializedName("conclusion") var conclusion: String? = null
    @SerializedName("steps") var steps: List<StepDto>? = null
}

internal class StepDto {
    @SerializedName("number") var number: Int? = null
    @SerializedName("name") var name: String? = null
    @SerializedName("status") var status: String? = null
    @SerializedName("conclusion") var conclusion: String? = null
}

private fun parseInstant(raw: String?): Instant =
    try {
        if (raw == null) Instant.EPOCH else Instant.parse(raw)
    } catch (e: Exception) {
        Instant.EPOCH
    }

private fun RunDto.toModel(): WorkflowRun? {
    val runId = id ?: return null
    return WorkflowRun(
        id = runId,
        runNumber = runNumber ?: 0,
        workflowName = name?.takeIf { it.isNotBlank() } ?: UNNAMED_WORKFLOW,
        branch = headBranch.orEmpty(),
        status = RunStatus.from(status, conclusion),
        htmlUrl = htmlUrl.orEmpty(),
        updatedAt = parseInstant(updatedAt),
    )
}

private fun StepDto.toModel(): Step? {
    val stepNumber = number ?: return null
    return Step(
        number = stepNumber,
        name = name.orEmpty(),
        status = RunStatus.from(status, conclusion),
    )
}

private fun JobDto.toModel(): Job? {
    val jobId = id ?: return null
    return Job(
        id = jobId,
        name = name.orEmpty(),
        status = RunStatus.from(status, conclusion),
        steps = steps.orEmpty().mapNotNull { it.toModel() },
    )
}

internal fun parseRuns(json: String): List<WorkflowRun> =
    GSON.fromJson(json, RunsResponseDto::class.java)
        ?.workflowRuns.orEmpty()
        .mapNotNull { it.toModel() }

internal fun parseJobs(json: String): List<Job> =
    GSON.fromJson(json, JobsResponseDto::class.java)
        ?.jobs.orEmpty()
        .mapNotNull { it.toModel() }
