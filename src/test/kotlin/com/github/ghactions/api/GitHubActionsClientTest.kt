package com.github.ghactions.api

import com.github.ghactions.auth.CommandOutput
import com.github.ghactions.auth.CommandRunner
import com.github.ghactions.auth.GhCliTokenProvider
import com.github.ghactions.model.RepoCoordinates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class GitHubActionsClientTest {

    private val repo = RepoCoordinates("octocat", "hello-world")

    private val runsJson = """
        {"workflow_runs":[{"id":1,"run_number":419,"name":"CI","head_branch":"main",
        "status":"completed","conclusion":"success",
        "html_url":"https://github.com/octocat/hello-world/actions/runs/1",
        "updated_at":"2026-08-10T07:30:00Z"}]}
    """.trimIndent()

    private fun client(
        transport: HttpTransport,
        etags: EtagCache = EtagCache(),
        token: CommandOutput? = CommandOutput(0, "gho_test", ""),
    ) = GitHubActionsClient(transport, GhCliTokenProvider(CommandRunner { token }), etags)

    @Test
    fun `成功返回解析后的 runs`() {
        val transport = HttpTransport { _, _ ->
            HttpResponse(200, runsJson, mapOf("ETag" to "\"v1\"", "X-RateLimit-Remaining" to "4999"))
        }

        val result = client(transport).listRuns(repo)

        val data = assertInstanceOf(ApiResult.Data::class.java, result)
        @Suppress("UNCHECKED_CAST")
        val runs = data.value as List<com.github.ghactions.model.WorkflowRun>
        assertEquals(1, runs.size)
        assertEquals(419, runs[0].runNumber)
        assertEquals(4999, data.rateLimitRemaining)
    }

    @Test
    fun `请求带上必需的 GitHub 头与认证`() {
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null
        val transport = HttpTransport { url, headers ->
            capturedUrl = url
            capturedHeaders = headers
            HttpResponse(200, runsJson, emptyMap())
        }

        client(transport).listRuns(repo, limit = 30)

        assertEquals(
            "https://api.github.com/repos/octocat/hello-world/actions/runs?per_page=30",
            capturedUrl,
        )
        val headers = capturedHeaders!!
        assertEquals("Bearer gho_test", headers["Authorization"])
        assertEquals("application/vnd.github+json", headers["Accept"])
        assertEquals("2022-11-28", headers["X-GitHub-Api-Version"])
    }

    @Test
    fun `默认只取最近 15 条`() {
        var capturedUrl: String? = null
        val transport = HttpTransport { url, _ ->
            capturedUrl = url
            HttpResponse(200, runsJson, emptyMap())
        }

        // 每条 run 约 12KB，其中七成是用不到的 repository 字段且无法筛选，
        // 取 30 条要传 437KB。默认值直接决定首次加载要等多久。
        client(transport).listRuns(repo)

        assertEquals(
            "https://api.github.com/repos/octocat/hello-world/actions/runs?per_page=15",
            capturedUrl,
        )
    }

    @Test
    fun `首次请求不带 If-None-Match 后续请求带上缓存的 etag`() {
        val etags = EtagCache()
        val seen = mutableListOf<String?>()
        val transport = HttpTransport { _, headers ->
            seen += headers["If-None-Match"]
            HttpResponse(200, runsJson, mapOf("ETag" to "\"v1\""))
        }

        val c = client(transport, etags)
        c.listRuns(repo)
        c.listRuns(repo)

        assertNull(seen[0])
        assertEquals("\"v1\"", seen[1])
    }

    @Test
    fun `304 返回 NotModified`() {
        val transport = HttpTransport { _, _ ->
            HttpResponse(304, "", mapOf("X-RateLimit-Remaining" to "4000"))
        }

        val result = client(transport).listRuns(repo)

        val notModified = assertInstanceOf(ApiResult.NotModified::class.java, result)
        assertEquals(4000, notModified.rateLimitRemaining)
    }

    @Test
    fun `403 且配额耗尽返回 RateLimited`() {
        val resetEpoch = 1_786_000_000L
        val transport = HttpTransport { _, _ ->
            HttpResponse(
                403, "",
                mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to resetEpoch.toString()),
            )
        }

        val result = client(transport).listRuns(repo)

        val limited = assertInstanceOf(ApiResult.RateLimited::class.java, result)
        assertEquals(Instant.ofEpochSecond(resetEpoch), limited.resetAt)
    }

    @Test
    fun `403 但配额充足返回 Error`() {
        val transport = HttpTransport { _, _ ->
            HttpResponse(403, "forbidden", mapOf("X-RateLimit-Remaining" to "4000"))
        }

        assertInstanceOf(ApiResult.Error::class.java, client(transport).listRuns(repo))
    }

    @Test
    fun `401 视为未登录`() {
        val transport = HttpTransport { _, _ -> HttpResponse(401, "", emptyMap()) }
        assertSame(ApiResult.GhNotLoggedIn, client(transport).listRuns(repo))
    }

    @Test
    fun `404 返回 Error 且消息不含 token`() {
        val transport = HttpTransport { _, _ -> HttpResponse(404, "Not Found", emptyMap()) }

        val error = assertInstanceOf(ApiResult.Error::class.java, client(transport).listRuns(repo))
        assertTrue(error.message.contains("404"))
        assertTrue(!error.message.contains("gho_test"))
    }

    @Test
    fun `gh 未安装时不发起请求`() {
        var called = false
        val transport = HttpTransport { _, _ ->
            called = true
            HttpResponse(200, runsJson, emptyMap())
        }

        val result = client(transport, token = null).listRuns(repo)

        assertSame(ApiResult.GhNotInstalled, result)
        assertTrue(!called)
    }

    @Test
    fun `传输层抛异常时返回 Error 而非崩溃`() {
        val transport = HttpTransport { _, _ -> throw java.io.IOException("network down") }

        val error = assertInstanceOf(ApiResult.Error::class.java, client(transport).listRuns(repo))
        assertTrue(error.message.contains("network down"))
    }

    @Test
    fun `listJobs 请求正确的地址并解析 steps`() {
        var capturedUrl: String? = null
        val jobsJson = """
            {"jobs":[{"id":88,"name":"build","status":"completed","conclusion":"success",
            "steps":[{"number":1,"name":"Checkout","status":"completed","conclusion":"success"}]}]}
        """.trimIndent()
        val transport = HttpTransport { url, _ ->
            capturedUrl = url
            HttpResponse(200, jobsJson, emptyMap())
        }

        val result = client(transport).listJobs(repo, 12345L)

        assertEquals(
            "https://api.github.com/repos/octocat/hello-world/actions/runs/12345/jobs?per_page=100",
            capturedUrl,
        )
        val data = assertInstanceOf(ApiResult.Data::class.java, result)
        @Suppress("UNCHECKED_CAST")
        val jobs = data.value as List<com.github.ghactions.model.Job>
        assertEquals(1, jobs.single().steps.size)
    }

    @Test
    fun `runs 与 jobs 使用互不干扰的 etag 键`() {
        val etags = EtagCache()
        val transport = HttpTransport { _, _ -> HttpResponse(200, runsJson, mapOf("ETag" to "\"r\"")) }
        client(transport, etags).listRuns(repo)

        val jobsSeen = mutableListOf<String?>()
        val jobsTransport = HttpTransport { _, headers ->
            jobsSeen += headers["If-None-Match"]
            HttpResponse(200, """{"jobs":[]}""", emptyMap())
        }
        client(jobsTransport, etags).listJobs(repo, 999L)

        assertNull(jobsSeen.single())
    }

    // ---- 额外测试：token 缓存（已批准的偏离设计） ----

    @Test
    fun `token 只获取一次并被复用`() {
        val runnerCalls = AtomicInteger(0)
        val runner = CommandRunner {
            runnerCalls.incrementAndGet()
            CommandOutput(0, "gho_test", "")
        }
        val transport = HttpTransport { _, _ -> HttpResponse(200, runsJson, emptyMap()) }
        val client = GitHubActionsClient(transport, GhCliTokenProvider(runner), EtagCache())

        client.listRuns(repo)
        client.listRuns(repo)
        client.listRuns(repo)

        assertEquals(1, runnerCalls.get())
    }

    @Test
    fun `401 后会重新获取 token`() {
        val runnerCalls = AtomicInteger(0)
        val runner = CommandRunner {
            runnerCalls.incrementAndGet()
            CommandOutput(0, "gho_test", "")
        }
        val statuses = listOf(200, 401, 200).iterator()
        val transport = HttpTransport { _, _ ->
            when (statuses.next()) {
                200 -> HttpResponse(200, runsJson, emptyMap())
                else -> HttpResponse(401, "", emptyMap())
            }
        }
        val client = GitHubActionsClient(transport, GhCliTokenProvider(runner), EtagCache())

        client.listRuns(repo) // 200，取一次 token 并缓存
        client.listRuns(repo) // 401，token 失效，清缓存
        client.listRuns(repo) // 200，重新取 token

        assertEquals(2, runnerCalls.get())
    }
}
