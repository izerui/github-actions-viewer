package com.github.ghactions.api

import com.github.ghactions.auth.GhCliTokenProvider
import com.github.ghactions.auth.TokenResult
import com.github.ghactions.model.Job
import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.model.WorkflowRun
import java.time.Instant

/**
 * GitHub Actions API 客户端。全项目唯一进行 HTTP 通信的地方。
 * 所有方法均为阻塞调用，由调用方负责放到合适的线程上执行。
 *
 * token 缓存：token 只在首次需要时通过 tokenProvider 获取一次（会 fork gh 子进程），
 * 之后复用内存中的值。收到 HTTP 401 时清空缓存，下次请求重新获取。
 * 这样轮询循环里的高频调用不会反复 fork 子进程。
 * token 只在内存中传递，绝不写入日志或异常消息。
 */
class GitHubActionsClient(
    private val transport: HttpTransport,
    private val tokenProvider: GhCliTokenProvider,
    private val etags: EtagCache,
) {

    @Volatile
    private var cachedToken: String? = null

    /**
     * 默认只取最近 [DEFAULT_RUN_LIMIT] 条。
     *
     * GitHub 的 runs 接口每条 run 约 12KB，其中 repository 与 head_repository
     * 两个我们完全用不到的字段就占了七成，且 REST 接口无法筛选字段。取 30 条要传
     * 437KB、在慢网络下要好几秒；而用户真正关心的就是最近几条。
     */
    fun listRuns(repo: RepoCoordinates, limit: Int = DEFAULT_RUN_LIMIT): ApiResult<List<WorkflowRun>> =
        fetch(
            cacheKey = "runs:$repo",
            url = "$API_BASE/repos/${repo.owner}/${repo.name}/actions/runs?per_page=$limit",
            parse = ::parseRuns,
        )

    fun listJobs(repo: RepoCoordinates, runId: Long): ApiResult<List<Job>> =
        fetch(
            cacheKey = "jobs:$repo:$runId",
            url = "$API_BASE/repos/${repo.owner}/${repo.name}/actions/runs/$runId/jobs?per_page=100",
            parse = ::parseJobs,
        )

    private fun <T> fetch(cacheKey: String, url: String, parse: (String) -> T): ApiResult<T> {
        // 缓存命中则复用；否则取一次 token 并缓存，避免每次请求都 fork gh 子进程。
        val token = cachedToken ?: when (val result = tokenProvider.token()) {
            is TokenResult.Success -> result.token.also { cachedToken = it }
            TokenResult.GhNotInstalled -> return ApiResult.GhNotInstalled
            TokenResult.GhNotLoggedIn -> return ApiResult.GhNotLoggedIn
        }

        val headers = buildMap {
            put("Authorization", "Bearer $token")
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            etags.get(cacheKey)?.let { put("If-None-Match", it) }
        }

        val response = try {
            transport.get(url, headers)
        } catch (e: Exception) {
            // 异常消息可能来自网络栈，绝不会包含 token，但仍只取 message 而非整个栈
            return ApiResult.Error(e.message ?: e.javaClass.simpleName)
        }

        val remaining = response.header(HEADER_REMAINING)?.toIntOrNull()

        return when {
            response.statusCode == 200 -> {
                etags.put(cacheKey, response.header("ETag"))
                try {
                    ApiResult.Data(parse(response.body), remaining)
                } catch (e: Exception) {
                    ApiResult.Error("响应解析失败：${e.message ?: e.javaClass.simpleName}")
                }
            }

            response.statusCode == 304 -> ApiResult.NotModified(remaining)

            response.statusCode == 401 -> {
                // token 已失效，清空缓存，下次请求重新获取
                cachedToken = null
                ApiResult.GhNotLoggedIn
            }

            response.statusCode == 403 || response.statusCode == 429 -> {
                if (remaining == 0) {
                    val reset = response.header(HEADER_RESET)?.toLongOrNull()
                    ApiResult.RateLimited(
                        reset?.let(Instant::ofEpochSecond) ?: Instant.now().plusSeconds(60),
                    )
                } else {
                    ApiResult.Error("请求被拒绝（HTTP ${response.statusCode}）")
                }
            }

            else -> ApiResult.Error("请求失败（HTTP ${response.statusCode}）")
        }
    }

    internal companion object {
        /** UI 侧的截断提示（[com.github.ghactions.ui.TruncationNoticeItem]）也读它，文案与实际拉取量因此不会脱节。 */
        const val DEFAULT_RUN_LIMIT = 15
        const val API_BASE = "https://api.github.com"
        const val HEADER_REMAINING = "X-RateLimit-Remaining"
        const val HEADER_RESET = "X-RateLimit-Reset"
    }
}
