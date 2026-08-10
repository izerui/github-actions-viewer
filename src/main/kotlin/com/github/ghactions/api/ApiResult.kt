package com.github.ghactions.api

import java.time.Instant

/**
 * 一次 API 调用的结果。所有失败模式在类型层面穷举，
 * 调用方的 when 表达式由编译器强制覆盖全部分支。
 */
sealed interface ApiResult<out T> {
    data class Data<T>(val value: T, val rateLimitRemaining: Int?) : ApiResult<T>
    data class NotModified(val rateLimitRemaining: Int?) : ApiResult<Nothing>
    data class RateLimited(val resetAt: Instant) : ApiResult<Nothing>
    data object GhNotInstalled : ApiResult<Nothing>
    data object GhNotLoggedIn : ApiResult<Nothing>
    data class Error(val message: String) : ApiResult<Nothing>
}
