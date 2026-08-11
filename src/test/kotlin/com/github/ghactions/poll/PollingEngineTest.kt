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

/**
 * 轮询引擎的节奏与状态行为。
 *
 * 关于满篇的 `runCurrent()`：唤醒动作（setVisible / requestRefresh /
 * onExpansionChanged）之后必须补一次，否则被唤醒的协程不会跑到下一个挂起点，
 * 后续的时间推进与断言全部错位。曾尝试移除，clean 构建下立刻挂掉 13 个用例——
 * 当时增量构建复用了缓存的测试结果，给出了「可以移除」的假象。
 * 若要再次尝试精简，务必用 `--rerun-tasks` 或 clean 验证。
 */
class PollingEngineTest {

    private val repo = RepoCoordinates("octocat", "hello-world")
    private val fixedNow = Instant.parse("2026-08-10T08:00:00Z")

    private fun runsJson(status: String, conclusion: String?, branch: String = "main"): String {
        val conclusionJson = conclusion?.let { "\"$it\"" } ?: "null"
        return """
            {"workflow_runs":[{"id":1,"run_number":419,"name":"CI","workflow_id":$CI_WORKFLOW_ID,
            "head_branch":"$branch",
            "status":"$status","conclusion":$conclusionJson,
            "html_url":"https://example.test/1","updated_at":"2026-08-10T07:30:00Z"}]}
        """.trimIndent()
    }

    /** 一页历史运行记录。[ids] 同时用作 run_number，越小越旧。 */
    private fun historyJson(vararg ids: Int, name: String = "CI", branch: String = "main"): String {
        val items = ids.joinToString(",") { id ->
            """
            {"id":$id,"run_number":$id,"name":"$name","workflow_id":$CI_WORKFLOW_ID,
            "head_branch":"$branch","status":"completed","conclusion":"success",
            "html_url":"https://example.test/$id","updated_at":"2026-08-01T07:30:00Z"}
            """.trimIndent()
        }
        return """{"workflow_runs":[$items]}"""
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

        /** 「加载更多」打到的 per-workflow 端点，与轮询的 runs 端点分开计数。 */
        val workflowRunsCalls = AtomicInteger()

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            val isJobs = url.endsWith("/jobs?per_page=100")
            val isWorkflowRuns = url.contains("/actions/workflows/")
            when {
                isJobs -> jobsCalls.incrementAndGet()
                isWorkflowRuns -> workflowRunsCalls.incrementAndGet()
                else -> runsCalls.incrementAndGet()
            }
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
    fun `折叠再展开已完成的 run 不应重新拉取`() = runTest {
        // 已完成 run 的 jobs 是终态、数据量很小，缓存该按「这个 run 还在不在列表里」
        // 清理，而不是按用户有没有展开它。否则每次折叠再展开都要重新干等一轮网络往返。
        var expanded = setOf(1L)
        val transport = RecordingTransport({ runsJson("completed", "success") }, { jobsJson })
        val engine = engineWith(transport, expandedRuns = { expanded })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, transport.jobsCalls.get())

        // 折叠
        expanded = emptySet()
        advanceTimeBy(PollingSchedule.IDLE)
        runCurrent()

        // 再次展开——缓存应当还在，不该再发请求
        expanded = setOf(1L)
        engine.onExpansionChanged()
        advanceUntilIdle()

        assertEquals(1, transport.jobsCalls.get(), "折叠再展开不该重新拉取已是终态的 jobs")
        val loaded = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        assertEquals(1, loaded.workflows[0].runs[0].jobs?.size, "重新展开后应立刻从缓存显示")
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

        // 折叠后停止请求（哪怕它还在运行中），但已拉到的数据保留在缓存里，
        // 这样重新展开是瞬时的，不必再等一轮网络往返。
        assertEquals(1, transport.jobsCalls.get())
        val loaded = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        assertEquals(1, loaded.workflows[0].runs[0].jobs?.size, "折叠不该丢弃已拉到的 jobs")
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

    // ---- 加载更多 ----

    /** 起一轮轮询并等它稳定，返回 transport 供后续断言。 */
    private suspend fun kotlinx.coroutines.test.TestScope.settled(engine: PollingEngine) {
        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        runCurrent()
        advanceUntilIdle()
    }

    private fun loaded(engine: PollingEngine) =
        assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)

    @Test
    fun `加载更多把历史记录追加到该 workflow 末尾`() = runTest {
        val transport = RecordingTransport(
            { runsJson("completed", "success") },
            responder = { url -> if ("/actions/workflows/" in url) HttpResponse(200, historyJson(300, 299), emptyMap()) else null },
        )
        val engine = engineWith(transport)
        settled(engine)
        assertEquals(1, loaded(engine).workflows[0].runs.size)

        engine.loadMore("CI")
        advanceUntilIdle()

        val runs = loaded(engine).workflows[0].runs
        assertEquals(listOf(419, 300, 299), runs.map { it.run.runNumber }, "历史记录排在最近的记录之后")
    }

    @Test
    fun `历史记录不参与后续轮询，也不增加轮询请求数`() = runTest {
        val transport = RecordingTransport(
            { runsJson("in_progress", null) },
            responder = { url -> if ("/actions/workflows/" in url) HttpResponse(200, historyJson(300), emptyMap()) else null },
        )
        val engine = engineWith(transport)
        settled(engine)

        engine.loadMore("CI")
        advanceUntilIdle()
        val runsCallsAfterLoad = transport.runsCalls.get()

        advanceTimeBy(30.seconds)
        runCurrent()

        // 历史记录仍在（没被最近 15 条冲掉）
        assertTrue(
            loaded(engine).workflows[0].runs.any { it.run.runNumber == 300 },
            "轮询不该把已加载的历史记录冲掉",
        )
        // 且轮询期间一次都没再打过 per-workflow 端点
        assertEquals(1, transport.workflowRunsCalls.get(), "历史记录不该跟着轮询刷新")
        assertTrue(transport.runsCalls.get() > runsCallsAfterLoad, "前置条件：轮询确实还在跑")
    }

    @Test
    fun `返回不足一页说明已到底，不再允许加载更多`() = runTest {
        val transport = RecordingTransport(
            { runsJson("completed", "success") },
            responder = { url -> if ("/actions/workflows/" in url) HttpResponse(200, historyJson(300), emptyMap()) else null },
        )
        val engine = engineWith(transport)
        settled(engine)
        assertTrue(loaded(engine).workflows[0].canLoadMore, "前置条件：初始应可加载更多")

        engine.loadMore("CI")
        advanceUntilIdle()

        assertTrue(!loaded(engine).workflows[0].canLoadMore, "只回来 1 条，不足一页，说明没有更多了")
    }

    @Test
    fun `与轮询数据重叠的记录以轮询侧为准`() = runTest {
        // 历史页里也包含 id=1，但状态是过时的 success；轮询侧此刻是 in_progress
        val transport = RecordingTransport(
            { runsJson("in_progress", null) },
            responder = { url -> if ("/actions/workflows/" in url) HttpResponse(200, historyJson(1, 300), emptyMap()) else null },
        )
        val engine = engineWith(transport)
        settled(engine)

        engine.loadMore("CI")
        advanceUntilIdle()

        val runs = loaded(engine).workflows[0].runs
        assertEquals(2, runs.count { it.run.id == 1L || it.run.id == 300L }, "重叠的 run 不该出现两次")
        assertEquals(
            com.github.ghactions.model.RunStatus.IN_PROGRESS,
            runs.first { it.run.id == 1L }.run.status,
            "重叠时应保留轮询侧更新的状态",
        )
    }

    @Test
    fun `加载更多失败不改变状态，可以再次尝试`() = runTest {
        val transport = RecordingTransport(
            { runsJson("completed", "success") },
            responder = { url -> if ("/actions/workflows/" in url) HttpResponse(500, "", emptyMap()) else null },
        )
        val engine = engineWith(transport)
        settled(engine)

        engine.loadMore("CI")
        advanceUntilIdle()

        val workflow = loaded(engine).workflows[0]
        assertEquals(1, workflow.runs.size, "失败不该改变已有数据")
        assertTrue(workflow.canLoadMore, "失败不该被当成已到底，用户得能再点一次")
    }

    @Test
    fun `分支过滤对历史记录同样生效`() = runTest {
        val transport = RecordingTransport(
            { runsJson("completed", "success", branch = "main") },
            responder = { url ->
                if ("/actions/workflows/" in url) {
                    HttpResponse(200, historyJson(300, branch = "feature"), emptyMap())
                } else {
                    null
                }
            },
        )
        val engine = engineWith(transport, branchProvider = { "main" })
        settled(engine)

        engine.loadMore("CI")
        advanceUntilIdle()
        assertTrue(loaded(engine).workflows[0].runs.any { it.run.runNumber == 300 }, "前置条件：历史记录已加载")

        engine.setBranchFilter(true)
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            loaded(engine).workflows[0].runs.none { it.run.runNumber == 300 },
            "feature 分支的历史记录不该绕过分支过滤",
        )
    }

    @Test
    fun `仓库切换后历史记录被清空`() = runTest {
        var currentRepo = repo
        val transport = RecordingTransport(
            { runsJson("completed", "success") },
            responder = { url -> if ("/actions/workflows/" in url) HttpResponse(200, historyJson(300), emptyMap()) else null },
        )
        val engine = engineWith(transport, repoProvider = { currentRepo })
        settled(engine)

        engine.loadMore("CI")
        advanceUntilIdle()
        assertTrue(loaded(engine).workflows[0].runs.any { it.run.runNumber == 300 }, "前置条件：历史记录已加载")

        currentRepo = RepoCoordinates("other", "repo")
        engine.requestRefresh()
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            loaded(engine).workflows[0].runs.none { it.run.runNumber == 300 },
            "上一个仓库的历史记录不该混进新仓库的树里",
        )
    }

    private companion object {
        const val CI_WORKFLOW_ID = 98765L
    }
}
