package com.github.ghactions.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EtagCacheTest {

    @Test
    fun `存取 etag`() {
        val cache = EtagCache()
        cache.put("runs", "\"abc\"")
        assertEquals("\"abc\"", cache.get("runs"))
    }

    @Test
    fun `未知键返回 null`() {
        assertNull(EtagCache().get("nope"))
    }

    @Test
    fun `put null 会移除条目`() {
        val cache = EtagCache()
        cache.put("runs", "\"abc\"")
        cache.put("runs", null)
        assertNull(cache.get("runs"))
    }

    @Test
    fun `clear 清空全部`() {
        val cache = EtagCache()
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun `响应头查询大小写不敏感`() {
        val response = HttpResponse(200, "", mapOf("ETag" to "\"x\"", "x-ratelimit-remaining" to "42"))
        assertEquals("\"x\"", response.header("etag"))
        assertEquals("\"x\"", response.header("ETAG"))
        assertEquals("42", response.header("X-RateLimit-Remaining"))
        assertNull(response.header("missing"))
    }
}
