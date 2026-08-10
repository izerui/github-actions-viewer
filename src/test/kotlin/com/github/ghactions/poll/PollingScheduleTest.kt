package com.github.ghactions.poll

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PollingScheduleTest {

    @Test
    fun `常量取值符合设计`() {
        assertEquals(5.seconds, PollingSchedule.ACTIVE)
        assertEquals(60.seconds, PollingSchedule.IDLE)
        assertEquals(5.minutes, PollingSchedule.DEGRADED)
        assertEquals(100, PollingSchedule.LOW_QUOTA_THRESHOLD)
    }

    @Test
    fun `不可见时暂停`() {
        assertNull(PollingSchedule.intervalFor(visible = false, hasRunning = true, rateLimitRemaining = 5000))
        assertNull(PollingSchedule.intervalFor(visible = false, hasRunning = false, rateLimitRemaining = null))
    }

    @Test
    fun `有运行中的 run 用快节奏`() {
        assertEquals(
            5.seconds,
            PollingSchedule.intervalFor(visible = true, hasRunning = true, rateLimitRemaining = 5000),
        )
    }

    @Test
    fun `全部完成用慢节奏`() {
        assertEquals(
            60.seconds,
            PollingSchedule.intervalFor(visible = true, hasRunning = false, rateLimitRemaining = 5000),
        )
    }

    @Test
    fun `配额偏低时降级 优先级高于运行中状态`() {
        assertEquals(
            5.minutes,
            PollingSchedule.intervalFor(visible = true, hasRunning = true, rateLimitRemaining = 99),
        )
        assertEquals(
            5.minutes,
            PollingSchedule.intervalFor(visible = true, hasRunning = false, rateLimitRemaining = 0),
        )
    }

    @Test
    fun `恰好等于阈值不算低配额`() {
        assertEquals(
            5.seconds,
            PollingSchedule.intervalFor(visible = true, hasRunning = true, rateLimitRemaining = 100),
        )
    }

    @Test
    fun `配额未知时按正常节奏处理`() {
        assertEquals(
            5.seconds,
            PollingSchedule.intervalFor(visible = true, hasRunning = true, rateLimitRemaining = null),
        )
    }
}
