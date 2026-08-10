package com.github.ghactions.api

import java.util.concurrent.ConcurrentHashMap

/**
 * 按端点缓存 ETag。命中 304 的响应不计入 GitHub 配额，
 * 这是高频轮询得以成立的前提。
 */
class EtagCache {

    private val entries = ConcurrentHashMap<String, String>()

    fun get(key: String): String? = entries[key]

    fun put(key: String, etag: String?) {
        if (etag == null) entries.remove(key) else entries[key] = etag
    }

    fun clear() = entries.clear()
}
