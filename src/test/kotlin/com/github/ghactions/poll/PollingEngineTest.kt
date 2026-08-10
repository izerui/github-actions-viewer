package com.github.ghactions.poll

import com.github.ghactions.api.EtagCache
import com.github.ghactions.api.GitHubActionsClient
import com.github.ghactions.api.HttpResponse
import com.github.ghactions.api.HttpTransport
import com.github.ghactions.auth.CommandOutput
import com.github.ghactions.auth.CommandRunner
import com.github.ghactions.auth.GhCliTokenProvider
import com.github.ghactions.model.RepoCoordinates
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PollingEngineTest {

    private val repo = RepoCoordinates("octocat", "hello-world")
    private val fixedNow = Instant.parse("2026-08-10T08:00:00Z")

    private fun runsJson(status: String, conclusion: String?, branch: String = "main"): String {
        val conclusionJson = conclusion?.let { "\"$it\"" } ?: "null"
        return """
            {"workflow_runs":[{"id":1,"run_number":419,"name":"CI","head_branch":"$branch",
            "status":"$status","conclusion":$conclusionJson,
            "html_url":"https://example.test/1","updated_at":"2026-08-10T07:30:00Z"}]}
        """.trimIndent()
    }

    private val jobsJson = """
        {"jobs":[{"id":88,"name":"build","status":"completed","conclusion":"success",
        "steps":[{"number":1,"name":"Checkout","status":"completed","conclusion":"success"}]}]}
    """.trimIndent()

    /** 记录每个端点被调用的次数，并按 URL 返回相应响应。 */
    private class RecordingTransport(
        private val runsBody: () -> String,
        private val jobsBody: () -> String = { "" },
        private val responder: ((String) -> HttpResponse?)? = null,
    ) : HttpTransport {
        val runsCalls = AtomicInteger()
        val jobsCalls = AtomicInteger()

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            val isJobs = url.endsWith("/jobs?per_page=100")
            if (isJobs) jobsCalls.incrementAndGet() else runsCalls.incrementAndGet()
            responder?.invoke(url)?.let { return it }
            return if (isJobs) {
                HttpResponse(200, jobsBody(), emptyMap())
            } else {
                HttpResponse(200, runsBody(), emptyMap())
            }
        }
    }

    private fun engineWith(
        transport: HttpTransport,
        repoProvider: () -> RepoCoordinates? = { repo },
        branchProvider: () -> String? = { "main" },
        expandedRuns: () -> Set<Long> = { emptySet() },
    ): PollingEngine {
        val etags = EtagCache()
        val client = GitHubActionsClient(
            transport,
            GhCliTokenProvider(CommandRunner { CommandOutput(0, "gho_test", "") }),
            etags,
        )
        return PollingEngine(client, etags, repoProvider, branchProvider, expandedRuns) { fixedNow }
    }

    @Test
    fun `不可见时完全不发请求`() = runTest {
        val transport = RecordingTransport({ runsJson("completed", "success") })
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        runCurrent()
        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(0, transport.runsCalls.get())
        assertSame(ViewState.Loading, engine.state.value)
    }

    @Test
    fun `变为可见时立即拉取一次`() = runTest {
        val transport = RecordingTransport({ runsJson("completed", "success") })
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, transport.runsCalls.get())
        val loaded = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        assertEquals(1, loaded.workflows.size)
        assertEquals("CI", loaded.workflows[0].name)
        assertEquals(419, loaded.workflows[0].runs[0].run.runNumber)
    }

    @Test
    fun `刚拉取完不会因残留唤醒信号而重复拉取`() = runTest {
        val transport = RecordingTransport({ runsJson("completed", "success") })
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(1, transport.runsCalls.get())
    }

    @Test
    fun `有运行中的 run 时每 5 秒拉取一次`() = runTest {
        val transport = RecordingTransport({ runsJson("in_progress", null) })
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, transport.runsCalls.get())

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(2, transport.runsCalls.get())

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(3, transport.runsCalls.get())
    }

    @Test
    fun `全部完成时 60 秒才拉取一次`() = runTest {
        val transport = RecordingTransport({ runsJson("completed", "success") })
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        advanceTimeBy(59.seconds)
        runCurrent()
        assertEquals(1, transport.runsCalls.get())

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, transport.runsCalls.get())
    }

    @Test
    fun `变为不可见后停止拉取`() = runTest {
        val transport = RecordingTransport({ runsJson("in_progress", null) })
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()
        val before = transport.runsCalls.get()

        engine.setVisible(false)
        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals(before, transport.runsCalls.get())
    }

    @Test
    fun `requestRefresh 立即触发一轮`() = runTest {
        val transport = RecordingTransport({ runsJson("completed", "success") })
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, transport.runsCalls.get())

        engine.requestRefresh()
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, transport.runsCalls.get())
    }

    @Test
    fun `只为展开的 run 拉取 jobs`() = runTest {
        var expanded = emptySet<Long>()
        val transport = RecordingTransport({ runsJson("completed", "success") }, { jobsJson })
        val engine = engineWith(transport, expandedRuns = { expanded })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        assertEquals(0, transport.jobsCalls.get())
        val collapsed = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        assertEquals(null, collapsed.workflows[0].runs[0].jobs)

        expanded = setOf(1L)
        engine.onExpansionChanged()
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, transport.jobsCalls.get())
        val loaded = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        val jobs = loaded.workflows[0].runs[0].jobs!!
        assertEquals(1, jobs.size)
        assertEquals("build", jobs[0].name)
        assertEquals(1, jobs[0].steps.size)
    }

    @Test
    fun `已完成的 run 只拉一次 jobs 之后不再重复请求`() = runTest {
        // 已完成 run 的 jobs/steps 是终态，不会再变化，拉一次缓存即可。
        // 每轮重拉即便命中 ETag 304 也仍要走完整的网络往返——省了配额，省不了延迟。
        val transport = RecordingTransport({ runsJson("completed", "success") }, { jobsJson })
        val engine = engineWith(transport, expandedRuns = { setOf(1L) })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, transport.jobsCalls.get())

        // 再过几轮，不应再请求这个已完成 run 的 jobs
        repeat(3) {
            advanceTimeBy(PollingSchedule.IDLE)
            runCurrent()
        }

        assertEquals(1, transport.jobsCalls.get())
        // 缓存仍在，树上依然挂着 jobs
        val loaded = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        assertEquals(1, loaded.workflows[0].runs[0].jobs?.size)
    }

    @Test
    fun `run 由运行中转为完成时应再拉一次 jobs 取终态`() = runTest {
        // 边界：run 刚跑完的那一轮，它同时满足「已完成」和「已缓存」，
        // 若据此跳过拉取，jobs 就永远停留在运行中的最后一帧——
        // run 那行显示绿勾，展开后里面的 step 还在转圈。
        var runStatus = "in_progress"
        var runConclusion: String? = null
        val transport = RecordingTransport({ runsJson(runStatus, runConclusion) }, { jobsJson })
        val engine = engineWith(transport, expandedRuns = { setOf(1L) })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, transport.jobsCalls.get())

        // 构建完成
        runStatus = "completed"
        runConclusion = "success"
        advanceTimeBy(PollingSchedule.ACTIVE)
        runCurrent()

        assertEquals(2, transport.jobsCalls.get(), "状态刚转为完成时必须再取一次终态")

        // 此后状态不再变化，应停止拉取
        repeat(3) {
            advanceTimeBy(PollingSchedule.IDLE)
            runCurrent()
        }
        assertEquals(2, transport.jobsCalls.get(), "终态确定后不应再重复拉取")
    }

    @Test
    fun `运行中的 run 每轮都刷新 jobs`() = runTest {
        val transport = RecordingTransport({ runsJson("in_progress", null) }, { jobsJson })
        val engine = engineWith(transport, expandedRuns = { setOf(1L) })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, transport.jobsCalls.get())

        advanceTimeBy(PollingSchedule.ACTIVE)
        runCurrent()
        assertEquals(2, transport.jobsCalls.get())

        advanceTimeBy(PollingSchedule.ACTIVE)
        runCurrent()
        assertEquals(3, transport.jobsCalls.get())
    }

    @Test
    fun `折叠后不再拉取该 run 的 jobs`() = runTest {
        var expanded = setOf(1L)
        val transport = RecordingTransport({ runsJson("in_progress", null) }, { jobsJson })
        val engine = engineWith(transport, expandedRuns = { expanded })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, transport.jobsCalls.get())

        expanded = emptySet()
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(1, transport.jobsCalls.get())
        val loaded = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        assertEquals(null, loaded.workflows[0].runs[0].jobs)
    }

    @Test
    fun `分支过滤开启后只保留当前分支的 run`() = runTest {
        val transport = RecordingTransport({ runsJson("completed", "success", branch = "feature/x") })
        val engine = engineWith(transport, branchProvider = { "main" })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, (engine.state.value as ViewState.Loaded).workflows.size)

        engine.setBranchFilter(true)
        runCurrent()
        advanceUntilIdle()
        assertTrue((engine.state.value as ViewState.Loaded).workflows.isEmpty())
    }

    @Test
    fun `仓库尚未就绪时保持加载中并快速探测`() = runTest {
        // IDE 启动初期 GitRepositoryManager 尚未初始化完，repoProvider 会短暂返回 null。
        // 这是「还没准备好」而非「确实没有 GitHub remote」：宽限期内必须保持 Loading，
        // 否则用户会对着误导性的「当前项目没有 GitHub remote」以为自己配置错了。
        var currentRepo: RepoCoordinates? = null
        val transport = RecordingTransport({ runsJson("completed", "success") })
        val engine = engineWith(transport, repoProvider = { currentRepo })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        assertSame(ViewState.Loading, engine.state.value)
        assertEquals(0, transport.runsCalls.get())

        // Git 仓库在宽限期内完成初始化
        currentRepo = repo
        advanceTimeBy(PollingSchedule.REPO_PROBE)
        runCurrent()

        assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        assertEquals(1, transport.runsCalls.get())
    }

    @Test
    fun `连续多轮拿不到仓库才认定为没有 GitHub remote`() = runTest {
        val transport = RecordingTransport({ runsJson("completed", "success") })
        val engine = engineWith(transport, repoProvider = { null })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        assertSame(ViewState.Loading, engine.state.value)

        // 熬过宽限期
        repeat(PollingSchedule.REPO_GRACE_ROUNDS) {
            advanceTimeBy(PollingSchedule.REPO_PROBE)
            runCurrent()
        }

        assertSame(ViewState.NoGitRemote, engine.state.value)
        assertEquals(0, transport.runsCalls.get())
    }

    @Test
    fun `401 映射为 GhNotLoggedIn`() = runTest {
        val transport = RecordingTransport(
            { "" },
            responder = { HttpResponse(401, "", emptyMap()) },
        )
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        assertSame(ViewState.GhNotLoggedIn, engine.state.value)
    }

    @Test
    fun `配额耗尽时进入 RateLimited 并等到重置时刻`() = runTest {
        val resetAt = fixedNow.plusSeconds(600)
        val transport = RecordingTransport(
            { "" },
            responder = {
                HttpResponse(
                    403, "",
                    mapOf(
                        "X-RateLimit-Remaining" to "0",
                        "X-RateLimit-Reset" to resetAt.epochSecond.toString(),
                    ),
                )
            },
        )
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        val limited = assertInstanceOf(ViewState.RateLimited::class.java, engine.state.value)
        assertEquals(resetAt, limited.resetAt)

        val callsAfterFirst = transport.runsCalls.get()
        advanceTimeBy(599.seconds)
        runCurrent()
        assertEquals(callsAfterFirst, transport.runsCalls.get())
    }

    @Test
    fun `配额偏低时标记降级并放慢到 5 分钟`() = runTest {
        val transport = RecordingTransport(
            { "" },
            responder = { url ->
                if (url.endsWith("/jobs?per_page=100")) null
                else HttpResponse(
                    200,
                    """{"workflow_runs":[{"id":1,"run_number":1,"name":"CI","head_branch":"main",
                       "status":"in_progress","conclusion":null,"html_url":"","updated_at":""}]}""",
                    mapOf("X-RateLimit-Remaining" to "42"),
                )
            },
        )
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        assertTrue((engine.state.value as ViewState.Loaded).degraded)

        advanceTimeBy(4.minutes)
        runCurrent()
        assertEquals(1, transport.runsCalls.get())
    }

    @Test
    fun `304 时复用上一轮的 runs`() = runTest {
        var first = true
        val transport = object : HttpTransport {
            val calls = AtomicInteger()
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                calls.incrementAndGet()
                return if (first) {
                    first = false
                    HttpResponse(200, runsJson("in_progress", null), mapOf("ETag" to "\"v1\""))
                } else {
                    HttpResponse(304, "", emptyMap())
                }
            }
        }
        val engine = engineWith(transport)

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()

        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(2, transport.calls.get())
        val loaded = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        assertEquals(419, loaded.workflows[0].runs[0].run.runNumber)
    }
}
