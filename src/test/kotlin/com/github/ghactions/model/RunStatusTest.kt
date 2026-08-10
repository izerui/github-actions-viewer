package com.github.ghactions.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RunStatusTest {

    @Test
    fun `conclusion 优先于 status`() {
        assertEquals(RunStatus.SUCCESS, RunStatus.from("completed", "success"))
        assertEquals(RunStatus.FAILURE, RunStatus.from("completed", "failure"))
        assertEquals(RunStatus.CANCELLED, RunStatus.from("completed", "cancelled"))
    }

    @Test
    fun `失败的各种别名都归为 FAILURE`() {
        assertEquals(RunStatus.FAILURE, RunStatus.from("completed", "timed_out"))
        assertEquals(RunStatus.FAILURE, RunStatus.from("completed", "startup_failure"))
        assertEquals(RunStatus.FAILURE, RunStatus.from("completed", "action_required"))
    }

    @Test
    fun `跳过与中性归为 SKIPPED`() {
        assertEquals(RunStatus.SKIPPED, RunStatus.from("completed", "skipped"))
        assertEquals(RunStatus.SKIPPED, RunStatus.from("completed", "neutral"))
    }

    @Test
    fun `未完成时按 status 判定`() {
        assertEquals(RunStatus.QUEUED, RunStatus.from("queued", null))
        assertEquals(RunStatus.QUEUED, RunStatus.from("waiting", null))
        assertEquals(RunStatus.QUEUED, RunStatus.from("pending", null))
        assertEquals(RunStatus.QUEUED, RunStatus.from("requested", null))
        assertEquals(RunStatus.IN_PROGRESS, RunStatus.from("in_progress", null))
    }

    @Test
    fun `completed 但无 conclusion 视为 SKIPPED 而非失败`() {
        // 这是 GitHub 偶发的畸形数据。以中性灰呈现，避免误报红色失败引起恐慌。
        assertEquals(RunStatus.SKIPPED, RunStatus.from("completed", null))
    }

    @Test
    fun `完全未知的输入不抛异常`() {
        assertEquals(RunStatus.QUEUED, RunStatus.from(null, null))
        assertEquals(RunStatus.QUEUED, RunStatus.from("something_new", null))
    }

    @Test
    fun `isRunning 只对排队中与进行中为真`() {
        assertTrue(RunStatus.QUEUED.isRunning)
        assertTrue(RunStatus.IN_PROGRESS.isRunning)
        assertFalse(RunStatus.SUCCESS.isRunning)
        assertFalse(RunStatus.FAILURE.isRunning)
        assertFalse(RunStatus.CANCELLED.isRunning)
        assertFalse(RunStatus.SKIPPED.isRunning)
    }
}
