package com.github.ghactions.model

/**
 * 运行状态。GitHub API 用 status + conclusion 两个字段表达状态，
 * 此枚举将其收敛为单一取值，上层无需再做二次判断。
 */
enum class RunStatus {
    QUEUED,
    IN_PROGRESS,
    SUCCESS,
    FAILURE,
    CANCELLED,
    SKIPPED;

    /** 是否处于「尚未结束」的状态，用于决定轮询节奏。 */
    val isRunning: Boolean
        get() = this == QUEUED || this == IN_PROGRESS

    companion object {
        fun from(status: String?, conclusion: String?): RunStatus = when (conclusion) {
            "success" -> SUCCESS
            "failure", "timed_out", "startup_failure", "action_required" -> FAILURE
            "cancelled" -> CANCELLED
            "skipped", "neutral" -> SKIPPED
            else -> when (status) {
                "in_progress" -> IN_PROGRESS
                "queued", "waiting", "pending", "requested" -> QUEUED
                // completed 却没有 conclusion 属于畸形数据。
                // 以中性的 SKIPPED 呈现，不误报为失败。
                "completed" -> SKIPPED
                else -> QUEUED
            }
        }
    }
}
