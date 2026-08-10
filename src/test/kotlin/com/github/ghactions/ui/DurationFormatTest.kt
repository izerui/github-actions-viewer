package com.github.ghactions.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DurationFormatTest {

    @Test
    fun `不足一分钟只显示秒`() {
        assertEquals("0s", formatDuration(0))
        assertEquals("45s", formatDuration(45))
        assertEquals("59s", formatDuration(59))
    }

    @Test
    fun `分钟级显示分与秒`() {
        assertEquals("1m", formatDuration(60))
        assertEquals("2m 30s", formatDuration(150))
        assertEquals("59m 59s", formatDuration(3599))
    }

    @Test
    fun `小时级只保留到分钟`() {
        assertEquals("1h", formatDuration(3600))
        assertEquals("1h 5m", formatDuration(3900))
        assertEquals("2h 30m", formatDuration(9000))
    }
}
