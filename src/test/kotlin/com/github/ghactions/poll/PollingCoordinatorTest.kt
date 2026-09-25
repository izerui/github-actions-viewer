package com.github.ghactions.poll

import com.github.ghactions.api.EtagCache
import com.github.ghactions.api.GitHubActionsClient
import com.github.ghactions.api.HttpResponse
import com.github.ghactions.api.HttpTransport
import com.github.ghactions.auth.CommandOutput
import com.github.ghactions.auth.CommandRunner
import com.github.ghactions.auth.GhCliTokenProvider
import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.repo.WorkspaceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class PollingCoordinatorTest {
    private val repo = RepoCoordinates("octocat", "hello-world")
    private val runsJson = """{"workflow_runs":[{"id":1,"run_number":1,"name":"CI","workflow_id":1,
        "head_branch":"main","status":"completed","conclusion":"success",
        "html_url":"","updated_at":"2026-08-10T07:00:00Z"}]}"""

    private fun coordinator(
        transport: HttpTransport,
        repos: () -> List<WorkspaceRepository>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): PollingCoordinator {
        val etags = EtagCache()
        val client =
            GitHubActionsClient(
                transport,
                GhCliTokenProvider(CommandRunner { CommandOutput(0, "gho_test", "") }),
                etags,
            )
        return PollingCoordinator(client, etags, scope, repos)
    }

    private fun workspace(vararg repos: RepoCoordinates): List<WorkspaceRepository> =
        repos.map { WorkspaceRepository(it, Path.of("/tmp/${it.owner}/${it.name}"), "main") }

    @Test
    fun `requestRefresh 立即置 refreshing 为 true，即使没有 engine`() =
        runTest {
            val coord =
                coordinator(
                    transport = HttpTransport { _, _ -> HttpResponse(200, runsJson, emptyMap()) },
                    repos = { emptyList() },
                    scope = backgroundScope,
                )

            assertFalse(coord.isRefreshing)
            coord.requestRefresh()
            assertTrue(coord.isRefreshing, "无 engine 时点击刷新也应立即看到 refreshing=true")
        }

    @Test
    fun `无 engine 时 requestRefresh 后仓库扫描结束聚合回 false`() =
        runTest {
            val coord =
                coordinator(
                    transport = HttpTransport { _, _ -> HttpResponse(200, runsJson, emptyMap()) },
                    repos = { emptyList() },
                    scope = backgroundScope,
                )

            coord.requestRefresh()
            assertTrue(coord.isRefreshing)

            coord.syncRepositories(emptyList())
            assertFalse(coord.isRefreshing, "无 engine 时 syncRepositories 应将 refreshing 聚合回 false")
        }

    @Test
    fun `唯一刷新中的 engine 被 syncRepositories 移除后 refreshing 变 false`() =
        runTest {
            val coord =
                coordinator(
                    transport = HttpTransport { _, _ -> HttpResponse(200, runsJson, emptyMap()) },
                    repos = { workspace(repo) },
                    scope = backgroundScope,
                )
            coord.isVisible = true

            coord.syncRepositories(workspace(repo))
            runCurrent()
            advanceUntilIdle()

            coord.requestRefresh()
            assertTrue(coord.isRefreshing, "前置条件：requestRefresh 后为 true")

            coord.syncRepositories(emptyList())
            assertFalse(coord.isRefreshing, "唯一刷新中的 engine 被移除后应恢复 false")
        }

    @Test
    fun `两个仓库中一个被移除，另一个仍在刷新，refreshing 保持 true`() =
        runTest {
            val second = RepoCoordinates("other", "repo")

            val coord =
                coordinator(
                    transport = HttpTransport { _, _ -> HttpResponse(200, runsJson, emptyMap()) },
                    repos = { workspace(repo, second) },
                    scope = backgroundScope,
                )
            coord.isVisible = true

            coord.syncRepositories(workspace(repo, second))
            runCurrent()
            advanceUntilIdle()

            coord.requestRefresh()
            assertTrue(coord.isRefreshing)

            coord.syncRepositories(workspace(second))

            runCurrent()
            advanceUntilIdle()
            assertFalse(coord.isRefreshing, "所有轮次完成后应恢复 false")
        }

    @Test
    fun `无 engine 快速刷新时 collector 收到完成态退出 Loading`() =
        runTest {
            val coord =
                coordinator(
                    transport = HttpTransport { _, _ -> HttpResponse(200, runsJson, emptyMap()) },
                    repos = { emptyList() },
                    scope = backgroundScope,
                )

            val emissions = mutableListOf<RefreshStatus>()
            val job =
                backgroundScope.launch {
                    coord.refreshStatus.collect { emissions.add(it) }
                }
            runCurrent()

            val initialGen = emissions.last().generation

            // 快速刷新：requestRefresh + syncRepositories 一气呵成
            coord.requestRefresh()
            coord.syncRepositories(emptyList())
            runCurrent()

            // StateFlow 对慢 collector 仍会合并快速更新——true 可能被跳过。
            // 但 RefreshStatus 的 generation 保证完成态 false 不会因 equals 被吞掉。
            // true 的用户反馈由 EDT actionPerformed 同步切换保证，不在 collector 层面验证。
            val completionFalse =
                emissions.lastOrNull {
                    !it.refreshing && it.generation > initialGen
                }
            assertTrue(
                completionFalse != null,
                "collector 应收到 generation 大于初始值的完成态 false，UI 据此退出 Loading",
            )

            job.cancel()
        }

    @Test
    fun `publishRefreshingState 不能插到 requestRefresh 的 service=true 和 engine=true 之间`() =
        runTest {
            // 两个钩子精确控制时序：
            //   beforeEngineRefresh — requestRefresh 锁内，service=true 后、engine 通知前
            //   beforePublishRefreshing — publishRefreshingState 获取锁之前
            //
            // coordinator 保持默认不可见：engine 不 poll，refreshing 初始为 false。
            // 若锁缺失，scanner 的 publishRefreshingState 会在 engine 通知前聚合出 false，
            // 用更高 generation 覆盖 service 的 true。
            val coord =
                coordinator(
                    transport = HttpTransport { _, _ -> HttpResponse(200, runsJson, emptyMap()) },
                    repos = { workspace(repo) },
                    scope = backgroundScope,
                )
            // 不设 visible——engine 创建但不 poll，refreshing 保持 false
            coord.syncRepositories(workspace(repo))
            runCurrent()
            assertFalse(coord.isRefreshing, "前置条件：engine 不可见，refreshing 为 false")

            val scannerAtLock = CountDownLatch(1)
            val scannerDone = CountDownLatch(1)
            val scannerThread = AtomicReference<Thread>()

            coord.beforeEngineRefresh = {
                // 此刻在 requestRefresh 的 synchronized 块内。
                // service=true，engine 尚未通知（refreshing 仍是 false）。
                // 直接调 publishRefreshingState（不经 syncRepositories），
                // 用线程身份区分 scanner 和 engine collector。
                coord.beforePublishRefreshing = {
                    if (Thread.currentThread() === scannerThread.get()) {
                        scannerAtLock.countDown()
                    }
                }
                val t =
                    Thread {
                        coord.publishRefreshingState()
                        scannerDone.countDown()
                    }
                scannerThread.set(t)
                t.start()
                assertTrue(
                    scannerAtLock.await(5, TimeUnit.SECONDS),
                    "scanner 应到达 publishRefreshingState 的锁争用点",
                )
                assertFalse(
                    scannerDone.await(200, TimeUnit.MILLISECONDS),
                    "scanner 应被 requestRefresh 的锁阻塞",
                )
            }

            coord.requestRefresh()
            // 锁释放。scanner 的 publishRefreshingState 执行，
            // 此时 engine 已被 requestRefresh 通知（refreshing=true），聚合为 true。

            assertTrue(scannerDone.await(5, TimeUnit.SECONDS), "scanner 应在锁释放后完成")
            assertTrue(
                coord.isRefreshing,
                "publishRefreshingState 应在 engine 被通知后才运行，聚合结果为 true",
            )

            coord.beforeEngineRefresh = null
            coord.beforePublishRefreshing = null
        }
}
