# GitHub Actions Viewer 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 IntelliJ IDEA 插件，在侧边栏以 Workflow → Run → Job → Step 四层树实时展示当前项目的 GitHub Actions 执行状态。

**Architecture:** 六个单向依赖的模块（model / auth / repo / api / poll / ui）。`api`、`auth`、`repo`、`model`、`poll` 不依赖 Swing，其中 `api`、`auth`、`model`、`poll` 不依赖 IDE 环境，可用纯 JVM 单测覆盖。轮询逻辑封装在无 IDE 依赖的 `PollingEngine` 中，用协程虚拟时间测试；IDE 侧只保留一层极薄的 service 外壳。

**Tech Stack:** Kotlin 2.0.21 · Gradle + IntelliJ Platform Gradle Plugin 2.1.0 · IntelliJ IDEA Community 2024.2.5 · Kotlin Coroutines · Swing · Gson · JUnit 5

## Global Constraints

- 包根：`com.github.ghactions`。插件 ID：`com.github.ghactions.viewer`
- 目标平台：IntelliJ IDEA Community `2024.2.5`，`sinceBuild = "242"`，`untilBuild = "252.*"`
- JVM toolchain：**21**
- JSON 解析一律用 **Gson**（平台捆绑）。禁止引入 kotlinx.serialization——它需要 Kotlin 编译器插件且运行时版本须与平台捆绑版严格匹配
- 除 `junit-jupiter:5.10.2` 与 `kotlinx-coroutines-test:1.8.0`（均为 `testImplementation`）外，**不引入任何第三方运行时依赖**。Gson、Coroutines 均由平台提供
- 测试框架统一 JUnit 5（`useJUnitPlatform()`）。**不编写** `BasePlatformTestCase` 平台测试
- 所有 API 请求必须带 `Accept: application/vnd.github+json` 与 `X-GitHub-Api-Version: 2022-11-28`
- 轮询间隔常量：运行中 **5 秒**、空闲 **60 秒**、配额降级 **5 分钟**；配额告警阈值 **剩余 < 100**
- runs 列表每轮取最近 **30** 条
- token 只存在于内存，**禁止**写入磁盘、日志或异常消息
- 每个任务结束时提交，提交信息用中文，遵循 conventional commits

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `settings.gradle.kts` | 项目名与插件仓库 |
| `build.gradle.kts` | 构建配置、平台依赖、测试配置 |
| `gradle.properties` | Gradle JVM 参数 |
| `src/main/resources/META-INF/plugin.xml` | 插件描述符、ToolWindow 注册 |
| `model/RunStatus.kt` | 状态枚举 + GitHub 双字段收敛 |
| `model/Domain.kt` | `WorkflowRun` / `Job` / `Step` / `WorkflowNode` / `RunNode` / `RepoCoordinates` |
| `repo/GitRemoteParser.kt` | remote URL → `RepoCoordinates` 纯函数 |
| `repo/IdeGitRepoProvider.kt` | 从 IDE 读取 remote 与当前分支（薄适配层） |
| `auth/CommandRunner.kt` | 进程执行抽象 + 真实实现 |
| `auth/GhCliTokenProvider.kt` | `gh auth token` → `TokenResult` |
| `api/HttpTransport.kt` | HTTP 抽象 + JDK 实现 |
| `api/ApiResult.kt` | 请求结果密封类型 |
| `api/EtagCache.kt` | ETag 缓存 |
| `api/Dto.kt` | Gson DTO（全 nullable）+ 映射到领域模型 |
| `api/GitHubActionsClient.kt` | runs / jobs 端点封装 |
| `poll/PollingSchedule.kt` | 间隔决策（纯函数） |
| `poll/ViewState.kt` | UI 状态密封类型 |
| `poll/PollingEngine.kt` | 轮询循环与状态汇聚（无 IDE 依赖） |
| `poll/ActionsPollingService.kt` | 项目级 service 外壳 |
| `ui/TreeItem.kt` | 树节点身份类型 |
| `ui/ActionsTreeModel.kt` | 差异更新树模型 |
| `ui/ActionsTreeCellRenderer.kt` | 节点渲染 |
| `ui/EmptyStatePanel.kt` | 各空状态界面 |
| `ui/ActionsTreePanel.kt` | 面板装配、工具栏、事件 |
| `ui/ActionsToolWindowFactory.kt` | ToolWindow 入口 |

---

## Task 1: 项目骨架与空 ToolWindow

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `src/main/resources/META-INF/plugin.xml`
- Create: `src/main/kotlin/com/github/ghactions/ui/ActionsToolWindowFactory.kt`

**Interfaces:**
- Consumes: 无
- Produces: 可构建的 Gradle 项目；`com.github.ghactions.ui.ActionsToolWindowFactory` 类

本任务无自动化测试——它的交付物就是「能构建成功」，由 `./gradlew buildPlugin` 验证。

- [ ] **Step 1: 生成 Gradle wrapper**

若本机没有 gradle，先 `brew install gradle`。

```bash
cd /Users/liuyuhua/github/github-actions-viewer
gradle wrapper --gradle-version 8.10
```

- [ ] **Step 2: 写 `settings.gradle.kts`**

```kotlin
rootProject.name = "github-actions-viewer"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}
```

- [ ] **Step 3: 写 `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m
org.gradle.caching=true
kotlin.stdlib.default.dependency=false
```

`kotlin.stdlib.default.dependency=false` 是必须的：平台已提供 Kotlin 标准库，重复引入会导致运行期版本冲突。

- [ ] **Step 4: 写 `build.gradle.kts`**

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.github.ghactions"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.5")
        bundledPlugin("Git4Idea")
        instrumentationTools()
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "252.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 5: 写 `src/main/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
    <id>com.github.ghactions.viewer</id>
    <name>GitHub Actions Viewer</name>
    <vendor>serv</vendor>
    <description><![CDATA[
        实时查看当前项目的 GitHub Actions 执行状态。
        以 Workflow → Run → Job → Step 四层树形结构展示，自动刷新。
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>Git4Idea</depends>

    <extensions defaultExtensionNs="com.intellij">
        <toolWindow id="GitHub Actions"
                    anchor="right"
                    icon="AllIcons.Vcs.Vendors.Github"
                    factoryClass="com.github.ghactions.ui.ActionsToolWindowFactory"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 6: 写 `ActionsToolWindowFactory.kt`**

```kotlin
package com.github.ghactions.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import javax.swing.JLabel

class ActionsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = toolWindow.contentManager.factory
            .createContent(JLabel("GitHub Actions Viewer"), null, false)
        toolWindow.contentManager.addContent(content)
    }
}
```

- [ ] **Step 7: 构建验证**

```bash
./gradlew buildPlugin
```

期望：`BUILD SUCCESSFUL`。首次运行会下载 IDEA 发行版，耗时数分钟属正常。

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "feat: 搭建 IntelliJ 插件项目骨架与空 ToolWindow"
```

---

## Task 2: RunStatus —— 收敛 GitHub 的双字段状态

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/model/RunStatus.kt`
- Test: `src/test/kotlin/com/github/ghactions/model/RunStatusTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum class RunStatus { QUEUED, IN_PROGRESS, SUCCESS, FAILURE, CANCELLED, SKIPPED }`
  - `val RunStatus.isRunning: Boolean`
  - `RunStatus.Companion.from(status: String?, conclusion: String?): RunStatus`

GitHub 用 `status` + `conclusion` 两个字段表达状态，组合繁杂且存在畸形数据。此处一次性收敛，让上层只面对单一枚举。

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.ghactions.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RunStatusTest {

    @Test
    fun `conclusion 优先于 status`() {
        assertEquals(RunStatus.SUCCESS, RunStatus.from("completed", "success"))
        assertEquals(RunStatus.FAILURE, RunStatus.from("completed", "failure"))
        assertEquals(RunStatus.CANCELLED, RunStatus.from("completed", "cancelled"))
    }

    @Test
    fun `失败的各种别名都归为 FAILURE`() {
        assertEquals(RunStatus.FAILURE, RunStatus.from("completed", "timed_out"))
        assertEquals(RunStatus.FAILURE, RunStatus.from("completed", "startup_failure"))
        assertEquals(RunStatus.FAILURE, RunStatus.from("completed", "action_required"))
    }

    @Test
    fun `跳过与中性归为 SKIPPED`() {
        assertEquals(RunStatus.SKIPPED, RunStatus.from("completed", "skipped"))
        assertEquals(RunStatus.SKIPPED, RunStatus.from("completed", "neutral"))
    }

    @Test
    fun `未完成时按 status 判定`() {
        assertEquals(RunStatus.QUEUED, RunStatus.from("queued", null))
        assertEquals(RunStatus.QUEUED, RunStatus.from("waiting", null))
        assertEquals(RunStatus.QUEUED, RunStatus.from("pending", null))
        assertEquals(RunStatus.QUEUED, RunStatus.from("requested", null))
        assertEquals(RunStatus.IN_PROGRESS, RunStatus.from("in_progress", null))
    }

    @Test
    fun `completed 但无 conclusion 视为 SKIPPED 而非失败`() {
        // 这是 GitHub 偶发的畸形数据。以中性灰呈现，避免误报红色失败引起恐慌。
        assertEquals(RunStatus.SKIPPED, RunStatus.from("completed", null))
    }

    @Test
    fun `完全未知的输入不抛异常`() {
        assertEquals(RunStatus.QUEUED, RunStatus.from(null, null))
        assertEquals(RunStatus.QUEUED, RunStatus.from("something_new", null))
    }

    @Test
    fun `isRunning 只对排队中与进行中为真`() {
        assertTrue(RunStatus.QUEUED.isRunning)
        assertTrue(RunStatus.IN_PROGRESS.isRunning)
        assertFalse(RunStatus.SUCCESS.isRunning)
        assertFalse(RunStatus.FAILURE.isRunning)
        assertFalse(RunStatus.CANCELLED.isRunning)
        assertFalse(RunStatus.SKIPPED.isRunning)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew test --tests '*RunStatusTest*'
```

期望：编译失败，`Unresolved reference: RunStatus`。

- [ ] **Step 3: 写实现**

```kotlin
package com.github.ghactions.model

/**
 * 运行状态。GitHub API 用 status + conclusion 两个字段表达状态，
 * 此枚举将其收敛为单一取值，上层无需再做二次判断。
 */
enum class RunStatus {
    QUEUED,
    IN_PROGRESS,
    SUCCESS,
    FAILURE,
    CANCELLED,
    SKIPPED;

    /** 是否处于「尚未结束」的状态，用于决定轮询节奏。 */
    val isRunning: Boolean
        get() = this == QUEUED || this == IN_PROGRESS

    companion object {
        fun from(status: String?, conclusion: String?): RunStatus = when (conclusion) {
            "success" -> SUCCESS
            "failure", "timed_out", "startup_failure", "action_required" -> FAILURE
            "cancelled" -> CANCELLED
            "skipped", "neutral" -> SKIPPED
            else -> when (status) {
                "in_progress" -> IN_PROGRESS
                "queued", "waiting", "pending", "requested" -> QUEUED
                // completed 却没有 conclusion 属于畸形数据。
                // 以中性的 SKIPPED 呈现，不误报为失败。
                "completed" -> SKIPPED
                else -> QUEUED
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew test --tests '*RunStatusTest*'
```

期望：全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(model): 添加 RunStatus 并收敛 GitHub 双字段状态"
```

---

## Task 3: 领域模型与 Gson DTO 映射

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/model/Domain.kt`
- Create: `src/main/kotlin/com/github/ghactions/api/Dto.kt`
- Test: `src/test/kotlin/com/github/ghactions/api/DtoTest.kt`

**Interfaces:**
- Consumes: `RunStatus`（Task 2）
- Produces:
  - `data class RepoCoordinates(val owner: String, val name: String)`
  - `data class WorkflowRun(val id: Long, val runNumber: Int, val workflowName: String, val branch: String, val status: RunStatus, val htmlUrl: String, val updatedAt: Instant)`
  - `data class Step(val number: Int, val name: String, val status: RunStatus)`
  - `data class Job(val id: Long, val name: String, val status: RunStatus, val steps: List<Step>)`
  - `data class RunNode(val run: WorkflowRun, val jobs: List<Job>?)`
  - `data class WorkflowNode(val name: String, val runs: List<RunNode>)`
  - `internal fun parseRuns(json: String): List<WorkflowRun>`
  - `internal fun parseJobs(json: String): List<Job>`

DTO 字段一律 nullable —— Gson 走反射，不保证非空契约。缺少关键字段的条目在映射阶段直接丢弃，畸形数据不会渗入领域层。

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.ghactions.api

import com.github.ghactions.model.RunStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DtoTest {

    @Test
    fun `解析 runs 响应`() {
        val json = """
        {
          "workflow_runs": [
            {
              "id": 419001,
              "run_number": 419,
              "name": "Build and Push Docker Image",
              "head_branch": "main",
              "status": "in_progress",
              "conclusion": null,
              "html_url": "https://github.com/o/r/actions/runs/419001",
              "updated_at": "2026-08-10T07:30:00Z"
            }
          ]
        }
        """.trimIndent()

        val runs = parseRuns(json)

        assertEquals(1, runs.size)
        val run = runs[0]
        assertEquals(419001L, run.id)
        assertEquals(419, run.runNumber)
        assertEquals("Build and Push Docker Image", run.workflowName)
        assertEquals("main", run.branch)
        assertEquals(RunStatus.IN_PROGRESS, run.status)
        assertEquals("https://github.com/o/r/actions/runs/419001", run.htmlUrl)
    }

    @Test
    fun `缺少 id 的条目被丢弃`() {
        val json = """
        {"workflow_runs":[{"run_number":1,"name":"x","status":"completed","conclusion":"success"}]}
        """.trimIndent()

        assertTrue(parseRuns(json).isEmpty())
    }

    @Test
    fun `缺少可选字段时使用安全默认值`() {
        val json = """
        {"workflow_runs":[{"id":7,"status":"completed","conclusion":"success"}]}
        """.trimIndent()

        val run = parseRuns(json).single()
        assertEquals(7L, run.id)
        assertEquals(0, run.runNumber)
        assertEquals("(未命名工作流)", run.workflowName)
        assertEquals("", run.branch)
        assertEquals("", run.htmlUrl)
    }

    @Test
    fun `空响应与空数组都返回空列表`() {
        assertTrue(parseRuns("{}").isEmpty())
        assertTrue(parseRuns("""{"workflow_runs":[]}""").isEmpty())
    }

    @Test
    fun `解析 jobs 响应含 steps`() {
        val json = """
        {
          "jobs": [
            {
              "id": 88001,
              "name": "build-and-push",
              "status": "in_progress",
              "conclusion": null,
              "steps": [
                {"number": 1, "name": "Set up job", "status": "completed", "conclusion": "success"},
                {"number": 2, "name": "Build and push", "status": "in_progress", "conclusion": null}
              ]
            }
          ]
        }
        """.trimIndent()

        val jobs = parseJobs(json)

        assertEquals(1, jobs.size)
        val job = jobs[0]
        assertEquals(88001L, job.id)
        assertEquals("build-and-push", job.name)
        assertEquals(RunStatus.IN_PROGRESS, job.status)
        assertEquals(2, job.steps.size)
        assertEquals("Set up job", job.steps[0].name)
        assertEquals(RunStatus.SUCCESS, job.steps[0].status)
        assertEquals(RunStatus.IN_PROGRESS, job.steps[1].status)
    }

    @Test
    fun `jobs 中缺少 steps 时为空列表`() {
        val json = """{"jobs":[{"id":1,"name":"j","status":"queued"}]}"""
        assertTrue(parseJobs(json).single().steps.isEmpty())
    }

    @Test
    fun `非法时间戳降级为纪元时间而不抛异常`() {
        val json = """
        {"workflow_runs":[{"id":1,"status":"queued","updated_at":"not-a-date"}]}
        """.trimIndent()

        assertEquals(java.time.Instant.EPOCH, parseRuns(json).single().updatedAt)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew test --tests '*DtoTest*'
```

期望：编译失败，`Unresolved reference: parseRuns`。

- [ ] **Step 3: 写 `model/Domain.kt`**

```kotlin
package com.github.ghactions.model

import java.time.Instant

/** GitHub 仓库坐标。 */
data class RepoCoordinates(val owner: String, val name: String) {
    override fun toString(): String = "$owner/$name"
}

/** 一次工作流运行。 */
data class WorkflowRun(
    val id: Long,
    val runNumber: Int,
    val workflowName: String,
    val branch: String,
    val status: RunStatus,
    val htmlUrl: String,
    val updatedAt: Instant,
)

/** 运行中的一个步骤。 */
data class Step(
    val number: Int,
    val name: String,
    val status: RunStatus,
)

/** 运行中的一个作业。 */
data class Job(
    val id: Long,
    val name: String,
    val status: RunStatus,
    val steps: List<Step>,
)

/** 树上的一个 run 节点。jobs 为 null 表示尚未加载（该 run 未展开）。 */
data class RunNode(
    val run: WorkflowRun,
    val jobs: List<Job>?,
)

/** 树上的一个 workflow 分组。 */
data class WorkflowNode(
    val name: String,
    val runs: List<RunNode>,
)
```

- [ ] **Step 4: 写 `api/Dto.kt`**

```kotlin
package com.github.ghactions.api

import com.github.ghactions.model.Job
import com.github.ghactions.model.RunStatus
import com.github.ghactions.model.Step
import com.github.ghactions.model.WorkflowRun
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.time.Instant

private val GSON = Gson()

private const val UNNAMED_WORKFLOW = "(未命名工作流)"

// Gson 通过反射构造对象，不保证 Kotlin 的非空契约，
// 因此所有 DTO 字段一律 nullable，由映射函数负责收敛。

internal class RunsResponseDto {
    @SerializedName("workflow_runs")
    var workflowRuns: List<RunDto>? = null
}

internal class RunDto {
    @SerializedName("id") var id: Long? = null
    @SerializedName("run_number") var runNumber: Int? = null
    @SerializedName("name") var name: String? = null
    @SerializedName("head_branch") var headBranch: String? = null
    @SerializedName("status") var status: String? = null
    @SerializedName("conclusion") var conclusion: String? = null
    @SerializedName("html_url") var htmlUrl: String? = null
    @SerializedName("updated_at") var updatedAt: String? = null
}

internal class JobsResponseDto {
    @SerializedName("jobs")
    var jobs: List<JobDto>? = null
}

internal class JobDto {
    @SerializedName("id") var id: Long? = null
    @SerializedName("name") var name: String? = null
    @SerializedName("status") var status: String? = null
    @SerializedName("conclusion") var conclusion: String? = null
    @SerializedName("steps") var steps: List<StepDto>? = null
}

internal class StepDto {
    @SerializedName("number") var number: Int? = null
    @SerializedName("name") var name: String? = null
    @SerializedName("status") var status: String? = null
    @SerializedName("conclusion") var conclusion: String? = null
}

private fun parseInstant(raw: String?): Instant =
    try {
        if (raw == null) Instant.EPOCH else Instant.parse(raw)
    } catch (e: Exception) {
        Instant.EPOCH
    }

private fun RunDto.toModel(): WorkflowRun? {
    val runId = id ?: return null
    return WorkflowRun(
        id = runId,
        runNumber = runNumber ?: 0,
        workflowName = name?.takeIf { it.isNotBlank() } ?: UNNAMED_WORKFLOW,
        branch = headBranch.orEmpty(),
        status = RunStatus.from(status, conclusion),
        htmlUrl = htmlUrl.orEmpty(),
        updatedAt = parseInstant(updatedAt),
    )
}

private fun StepDto.toModel(): Step? {
    val stepNumber = number ?: return null
    return Step(
        number = stepNumber,
        name = name.orEmpty(),
        status = RunStatus.from(status, conclusion),
    )
}

private fun JobDto.toModel(): Job? {
    val jobId = id ?: return null
    return Job(
        id = jobId,
        name = name.orEmpty(),
        status = RunStatus.from(status, conclusion),
        steps = steps.orEmpty().mapNotNull { it.toModel() },
    )
}

internal fun parseRuns(json: String): List<WorkflowRun> =
    GSON.fromJson(json, RunsResponseDto::class.java)
        ?.workflowRuns.orEmpty()
        .mapNotNull { it.toModel() }

internal fun parseJobs(json: String): List<Job> =
    GSON.fromJson(json, JobsResponseDto::class.java)
        ?.jobs.orEmpty()
        .mapNotNull { it.toModel() }
```

- [ ] **Step 5: 运行测试确认通过**

```bash
./gradlew test --tests '*DtoTest*'
```

期望：全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(model): 添加领域模型与 Gson DTO 映射"
```

---

## Task 4: Git remote 解析

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/repo/GitRemoteParser.kt`
- Create: `src/main/kotlin/com/github/ghactions/repo/IdeGitRepoProvider.kt`
- Test: `src/test/kotlin/com/github/ghactions/repo/GitRemoteParserTest.kt`

**Interfaces:**
- Consumes: `RepoCoordinates`（Task 3）
- Produces:
  - `object GitRemoteParser { fun parse(url: String): RepoCoordinates? }`
  - `class IdeGitRepoProvider(project: Project) { fun currentRepo(): RepoCoordinates?; fun currentBranch(): String? }`

解析逻辑是纯函数，完整测试；IDE 适配层极薄且无分支逻辑，不单独测试，由手动验证覆盖。

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.ghactions.repo

import com.github.ghactions.model.RepoCoordinates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitRemoteParserTest {

    private val expected = RepoCoordinates("octocat", "hello-world")

    @Test
    fun `scp 风格 ssh 地址`() {
        assertEquals(expected, GitRemoteParser.parse("git@github.com:octocat/hello-world.git"))
        assertEquals(expected, GitRemoteParser.parse("git@github.com:octocat/hello-world"))
    }

    @Test
    fun `ssh 协议地址`() {
        assertEquals(expected, GitRemoteParser.parse("ssh://git@github.com/octocat/hello-world.git"))
        assertEquals(expected, GitRemoteParser.parse("ssh://git@github.com:22/octocat/hello-world.git"))
    }

    @Test
    fun `https 地址`() {
        assertEquals(expected, GitRemoteParser.parse("https://github.com/octocat/hello-world.git"))
        assertEquals(expected, GitRemoteParser.parse("https://github.com/octocat/hello-world"))
        assertEquals(expected, GitRemoteParser.parse("https://github.com/octocat/hello-world/"))
    }

    @Test
    fun `https 地址带用户名`() {
        assertEquals(expected, GitRemoteParser.parse("https://token@github.com/octocat/hello-world.git"))
    }

    @Test
    fun `首尾空白被忽略`() {
        assertEquals(expected, GitRemoteParser.parse("  https://github.com/octocat/hello-world.git  "))
    }

    @Test
    fun `非 github 主机返回 null`() {
        assertNull(GitRemoteParser.parse("https://gitlab.com/octocat/hello-world.git"))
        assertNull(GitRemoteParser.parse("git@bitbucket.org:octocat/hello-world.git"))
        assertNull(GitRemoteParser.parse("https://github.company.com/octocat/hello-world.git"))
    }

    @Test
    fun `无法识别的输入返回 null`() {
        assertNull(GitRemoteParser.parse(""))
        assertNull(GitRemoteParser.parse("   "))
        assertNull(GitRemoteParser.parse("not a url at all"))
        assertNull(GitRemoteParser.parse("https://github.com/onlyowner"))
    }

    @Test
    fun `仓库名中的点号被保留`() {
        assertEquals(
            RepoCoordinates("octocat", "hello.world"),
            GitRemoteParser.parse("https://github.com/octocat/hello.world.git"),
        )
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew test --tests '*GitRemoteParserTest*'
```

期望：编译失败，`Unresolved reference: GitRemoteParser`。

- [ ] **Step 3: 写 `GitRemoteParser.kt`**

```kotlin
package com.github.ghactions.repo

import com.github.ghactions.model.RepoCoordinates

/**
 * 将 git remote URL 解析为仓库坐标。纯函数，无任何 IDE 依赖。
 * 仅接受 github.com，其余主机一律返回 null。
 */
object GitRemoteParser {

    private const val GITHUB_HOST = "github.com"

    // git@github.com:owner/repo(.git)
    private val SCP_STYLE = Regex("""^[^@]+@([^:]+):([^/]+)/(.+?)(?:\.git)?/?$""")

    // ssh://git@github.com(:22)/owner/repo(.git)
    private val SSH_URL = Regex("""^ssh://(?:[^@/]+@)?([^/:]+)(?::\d+)?/([^/]+)/(.+?)(?:\.git)?/?$""")

    // https://(token@)github.com(:443)/owner/repo(.git)
    private val HTTP_URL = Regex("""^https?://(?:[^@/]+@)?([^/:]+)(?::\d+)?/([^/]+)/(.+?)(?:\.git)?/?$""")

    fun parse(url: String): RepoCoordinates? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        val match = SSH_URL.find(trimmed)
            ?: HTTP_URL.find(trimmed)
            ?: SCP_STYLE.find(trimmed)
            ?: return null

        val (host, owner, name) = match.destructured
        if (!host.equals(GITHUB_HOST, ignoreCase = true)) return null
        if (owner.isBlank() || name.isBlank()) return null
        if (name.contains('/')) return null

        return RepoCoordinates(owner, name)
    }
}
```

注意匹配顺序：`SSH_URL` 与 `HTTP_URL` 必须先于 `SCP_STYLE` 尝试，否则
`ssh://git@github.com/o/r` 会被 scp 风格的正则错误匹配。

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew test --tests '*GitRemoteParserTest*'
```

期望：全部 PASS。

- [ ] **Step 5: 写 `IdeGitRepoProvider.kt`**

```kotlin
package com.github.ghactions.repo

import com.github.ghactions.model.RepoCoordinates
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * 从 IDE 的 Git 集成读取当前项目的仓库坐标与分支。
 * 仅做取值与委托，判断逻辑全在 GitRemoteParser 中。
 */
class IdeGitRepoProvider(private val project: Project) {

    fun currentRepo(): RepoCoordinates? {
        val repository = GitRepositoryManager.getInstance(project).repositories.firstOrNull() ?: return null
        val remote = repository.remotes.firstOrNull { it.name == "origin" }
            ?: repository.remotes.firstOrNull()
            ?: return null
        val url = remote.urls.firstOrNull() ?: return null
        return GitRemoteParser.parse(url)
    }

    fun currentBranch(): String? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName
}
```

- [ ] **Step 6: 编译验证**

```bash
./gradlew compileKotlin
```

期望：`BUILD SUCCESSFUL`。这一步确认 `Git4Idea` 依赖配置正确、`git4idea` 包可解析。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(repo): 添加 git remote 解析与 IDE 仓库适配层"
```

---

## Task 5: gh CLI 认证

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/auth/CommandRunner.kt`
- Create: `src/main/kotlin/com/github/ghactions/auth/GhCliTokenProvider.kt`
- Test: `src/test/kotlin/com/github/ghactions/auth/GhCliTokenProviderTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class CommandOutput(val exitCode: Int, val stdout: String, val stderr: String)`
  - `fun interface CommandRunner { fun run(command: List<String>): CommandOutput? }`（返回 null 表示进程无法启动）
  - `class ProcessCommandRunner : CommandRunner`
  - `sealed interface TokenResult`，成员：`Success(token: String)` / `GhNotInstalled` / `GhNotLoggedIn`
  - `class GhCliTokenProvider(runner: CommandRunner) { fun token(): TokenResult }`

**关键陷阱：** 从 Dock/Launchpad 启动的 IDEA，其进程 `PATH` 极小，不含
`/opt/homebrew/bin` 等常见安装位置，直接 `ProcessBuilder` 会找不到 `gh`。
必须用 `EnvironmentUtil.getEnvironmentMap()` —— IDEA 启动时会加载用户
登录 shell 的完整环境，这是唯一可靠的解法。

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.ghactions.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GhCliTokenProviderTest {

    private fun provider(output: CommandOutput?): GhCliTokenProvider =
        GhCliTokenProvider(CommandRunner { output })

    @Test
    fun `正常输出返回 token`() {
        val result = provider(CommandOutput(0, "gho_abc123\n", "")).token()
        assertEquals(TokenResult.Success("gho_abc123"), result)
    }

    @Test
    fun `进程无法启动视为未安装`() {
        assertSame(TokenResult.GhNotInstalled, provider(null).token())
    }

    @Test
    fun `非零退出码视为未登录`() {
        val output = CommandOutput(1, "", "gh: To get started with GitHub CLI, please run: gh auth login")
        assertSame(TokenResult.GhNotLoggedIn, provider(output).token())
    }

    @Test
    fun `退出码为零但输出为空视为未登录`() {
        assertSame(TokenResult.GhNotLoggedIn, provider(CommandOutput(0, "   \n", "")).token())
    }

    @Test
    fun `调用的是 gh auth token`() {
        var captured: List<String>? = null
        val runner = CommandRunner { cmd ->
            captured = cmd
            CommandOutput(0, "t", "")
        }
        GhCliTokenProvider(runner).token()
        assertEquals(listOf("gh", "auth", "token"), captured)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew test --tests '*GhCliTokenProviderTest*'
```

期望：编译失败，`Unresolved reference: GhCliTokenProvider`。

- [ ] **Step 3: 写 `CommandRunner.kt`**

```kotlin
package com.github.ghactions.auth

import com.intellij.util.EnvironmentUtil
import java.util.concurrent.TimeUnit

data class CommandOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/** 进程执行抽象。返回 null 表示进程根本无法启动（可执行文件不存在）。 */
fun interface CommandRunner {
    fun run(command: List<String>): CommandOutput?
}

/**
 * 真实进程执行器。
 *
 * 必须使用 EnvironmentUtil.getEnvironmentMap()：从 Dock 启动的 IDEA
 * 其自身 PATH 不含 /opt/homebrew/bin 等目录，直接执行会找不到 gh。
 * IDEA 启动时会加载用户登录 shell 的完整环境并由该方法提供。
 */
class ProcessCommandRunner(
    private val timeoutSeconds: Long = 10,
) : CommandRunner {

    override fun run(command: List<String>): CommandOutput? = try {
        val builder = ProcessBuilder(command)
        builder.environment().putAll(EnvironmentUtil.getEnvironmentMap())
        val process = builder.start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            CommandOutput(process.exitValue(), stdout, stderr)
        }
    } catch (e: Exception) {
        // 可执行文件不存在、权限不足、线程被中断等，一律按「无法启动」处理
        null
    }
}
```

- [ ] **Step 4: 写 `GhCliTokenProvider.kt`**

```kotlin
package com.github.ghactions.auth

sealed interface TokenResult {
    data class Success(val token: String) : TokenResult
    data object GhNotInstalled : TokenResult
    data object GhNotLoggedIn : TokenResult
}

/**
 * 通过 `gh auth token` 获取 GitHub token。
 * token 只在内存中传递，禁止写入磁盘、日志或异常消息。
 */
class GhCliTokenProvider(private val runner: CommandRunner) {

    fun token(): TokenResult {
        val output = runner.run(listOf("gh", "auth", "token")) ?: return TokenResult.GhNotInstalled
        if (output.exitCode != 0) return TokenResult.GhNotLoggedIn
        val token = output.stdout.trim()
        return if (token.isEmpty()) TokenResult.GhNotLoggedIn else TokenResult.Success(token)
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
./gradlew test --tests '*GhCliTokenProviderTest*'
```

期望：全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(auth): 通过 gh CLI 获取 GitHub token"
```

---

## Task 6: HTTP 抽象、请求结果与 ETag 缓存

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/api/HttpTransport.kt`
- Create: `src/main/kotlin/com/github/ghactions/api/ApiResult.kt`
- Create: `src/main/kotlin/com/github/ghactions/api/EtagCache.kt`
- Test: `src/test/kotlin/com/github/ghactions/api/EtagCacheTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class HttpResponse(val statusCode: Int, val body: String, val headers: Map<String, String>) { fun header(name: String): String? }`
  - `fun interface HttpTransport { fun get(url: String, headers: Map<String, String>): HttpResponse }`
  - `class JdkHttpTransport : HttpTransport`
  - `sealed interface ApiResult<out T>`，成员：`Data<T>(value: T, rateLimitRemaining: Int?)` / `NotModified(rateLimitRemaining: Int?)` / `RateLimited(resetAt: Instant)` / `GhNotInstalled` / `GhNotLoggedIn` / `Error(message: String)`
  - `class EtagCache { fun get(key: String): String?; fun put(key: String, etag: String?); fun clear() }`

`header()` 必须大小写不敏感——HTTP 头名大小写不固定，JDK 客户端会原样保留服务端拼写。

- [ ] **Step 1: 写失败的测试**

```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew test --tests '*EtagCacheTest*'
```

期望：编译失败，`Unresolved reference: EtagCache`。

- [ ] **Step 3: 写 `HttpTransport.kt`**

```kotlin
package com.github.ghactions.api

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>,
) {
    /** HTTP 头名大小写不固定，此处做大小写不敏感查询。 */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

fun interface HttpTransport {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

class JdkHttpTransport : HttpTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }

        val response = client.send(builder.build(), BodyHandlers.ofString())
        val flatHeaders = response.headers().map()
            .mapValues { (_, values) -> values.firstOrNull().orEmpty() }

        return HttpResponse(response.statusCode(), response.body().orEmpty(), flatHeaders)
    }
}
```

- [ ] **Step 4: 写 `ApiResult.kt`**

```kotlin
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
```

- [ ] **Step 5: 写 `EtagCache.kt`**

```kotlin
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
```

- [ ] **Step 6: 运行测试确认通过**

```bash
./gradlew test --tests '*EtagCacheTest*'
```

期望：全部 PASS。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(api): 添加 HTTP 抽象、请求结果类型与 ETag 缓存"
```

---

## Task 7: GitHubActionsClient

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/api/GitHubActionsClient.kt`
- Test: `src/test/kotlin/com/github/ghactions/api/GitHubActionsClientTest.kt`

**Interfaces:**
- Consumes: `HttpTransport` / `HttpResponse` / `ApiResult` / `EtagCache`（Task 6）、`GhCliTokenProvider` / `TokenResult`（Task 5）、`parseRuns` / `parseJobs`（Task 3）、`RepoCoordinates` / `WorkflowRun` / `Job`（Task 3）
- Produces:
  - `class GitHubActionsClient(transport: HttpTransport, tokenProvider: GhCliTokenProvider, etags: EtagCache)`
  - `fun listRuns(repo: RepoCoordinates, limit: Int = 30): ApiResult<List<WorkflowRun>>`
  - `fun listJobs(repo: RepoCoordinates, runId: Long): ApiResult<List<Job>>`

状态码映射规则：`200` → `Data`；`304` → `NotModified`；`401` → `GhNotLoggedIn`（token 失效）；`403`/`429` 且 `X-RateLimit-Remaining == "0"` → `RateLimited`，否则 `Error`；其余 → `Error`。

- [ ] **Step 1: 写失败的测试**

```kotlin
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
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew test --tests '*GitHubActionsClientTest*'
```

期望：编译失败，`Unresolved reference: GitHubActionsClient`。

- [ ] **Step 3: 写实现**

```kotlin
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
 */
class GitHubActionsClient(
    private val transport: HttpTransport,
    private val tokenProvider: GhCliTokenProvider,
    private val etags: EtagCache,
) {

    fun listRuns(repo: RepoCoordinates, limit: Int = 30): ApiResult<List<WorkflowRun>> =
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
        val token = when (val result = tokenProvider.token()) {
            is TokenResult.Success -> result.token
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

            response.statusCode == 401 -> ApiResult.GhNotLoggedIn

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

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val HEADER_REMAINING = "X-RateLimit-Remaining"
        const val HEADER_RESET = "X-RateLimit-Reset"
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew test --tests '*GitHubActionsClientTest*'
```

期望：全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(api): 添加 GitHub Actions 客户端并支持 ETag 与限流识别"
```

---

## Task 8: 轮询间隔决策

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/poll/PollingSchedule.kt`
- Test: `src/test/kotlin/com/github/ghactions/poll/PollingScheduleTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `object PollingSchedule`，含 `ACTIVE: Duration`、`IDLE: Duration`、`DEGRADED: Duration`、`LOW_QUOTA_THRESHOLD: Int`
  - `fun intervalFor(visible: Boolean, hasRunning: Boolean, rateLimitRemaining: Int?): Duration?`（返回 null 表示暂停轮询）

把节奏决策抽成纯函数，使其能被穷举测试，而不必启动协程或 IDE。

- [ ] **Step 1: 写失败的测试**

```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew test --tests '*PollingScheduleTest*'
```

期望：编译失败，`Unresolved reference: PollingSchedule`。

- [ ] **Step 3: 写实现**

```kotlin
package com.github.ghactions.poll

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 轮询节奏决策。纯函数，无状态，便于穷举测试。
 */
object PollingSchedule {

    /** 存在未结束的 run 时的间隔。 */
    val ACTIVE: Duration = 5.seconds

    /** 全部 run 已结束时的间隔。 */
    val IDLE: Duration = 60.seconds

    /** API 配额偏低时的降级间隔。 */
    val DEGRADED: Duration = 5.minutes

    /** 剩余配额低于此值即进入降级。 */
    const val LOW_QUOTA_THRESHOLD: Int = 100

    /**
     * @return 下一轮的等待时长；null 表示暂停轮询。
     */
    fun intervalFor(visible: Boolean, hasRunning: Boolean, rateLimitRemaining: Int?): Duration? = when {
        !visible -> null
        rateLimitRemaining != null && rateLimitRemaining < LOW_QUOTA_THRESHOLD -> DEGRADED
        hasRunning -> ACTIVE
        else -> IDLE
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
./gradlew test --tests '*PollingScheduleTest*'
```

期望：全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(poll): 添加自适应轮询间隔决策"
```

---

## Task 9: ViewState 与 PollingEngine

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/poll/ViewState.kt`
- Create: `src/main/kotlin/com/github/ghactions/poll/PollingEngine.kt`
- Test: `src/test/kotlin/com/github/ghactions/poll/PollingEngineTest.kt`

**Interfaces:**
- Consumes: `PollingSchedule`（Task 8）、`GitHubActionsClient` / `ApiResult` / `EtagCache`（Task 6、7）、`WorkflowNode` / `RunNode` / `RepoCoordinates` / `WorkflowRun` / `Job`（Task 3）
- Produces:
  - `sealed interface ViewState`，成员：`Loading` / `NoGitRemote` / `GhNotInstalled` / `GhNotLoggedIn` / `RateLimited(resetAt: Instant)` / `Error(message: String)` / `Loaded(workflows: List<WorkflowNode>, lastUpdated: Instant, degraded: Boolean)`
  - `class PollingEngine(client, etags, repoProvider, branchProvider, expandedRuns, now)`
  - `val state: StateFlow<ViewState>`
  - `suspend fun run()` —— 无限轮询循环
  - `fun setVisible(value: Boolean)` / `fun setBranchFilter(enabled: Boolean)` / `fun requestRefresh()` / `fun onExpansionChanged()`

这是本项目最复杂的一块。关键在于它**完全不依赖 IDE 与 Swing**，因此整个节奏行为可以用协程虚拟时间在毫秒内穷举验证。

**两个易错点：**
1. `setVisible(true)` 会投递一个唤醒信号；若不在每轮开始处排空该信号，`withTimeoutOrNull` 会立刻收到它，导致刚拉完又立即重拉。
2. `advanceTimeBy(d)` 只执行 `[now, now+d)` 区间的任务，**不含终点**。因此断言前必须补一次 `runCurrent()`。

- [ ] **Step 1: 写失败的测试**

```kotlin
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
            responder?.invoke(url)?.let { return it }
            return if (url.endsWith("/jobs?per_page=100")) {
                jobsCalls.incrementAndGet()
                HttpResponse(200, jobsBody(), emptyMap())
            } else {
                runsCalls.incrementAndGet()
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
        advanceUntilIdle()
        assertEquals(1, transport.runsCalls.get())

        engine.requestRefresh()
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
        advanceUntilIdle()

        assertEquals(0, transport.jobsCalls.get())
        val collapsed = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        assertEquals(null, collapsed.workflows[0].runs[0].jobs)

        expanded = setOf(1L)
        engine.onExpansionChanged()
        advanceUntilIdle()

        assertEquals(1, transport.jobsCalls.get())
        val loaded = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        val jobs = loaded.workflows[0].runs[0].jobs!!
        assertEquals(1, jobs.size)
        assertEquals("build", jobs[0].name)
        assertEquals(1, jobs[0].steps.size)
    }

    @Test
    fun `折叠后不再拉取该 run 的 jobs`() = runTest {
        var expanded = setOf(1L)
        val transport = RecordingTransport({ runsJson("in_progress", null) }, { jobsJson })
        val engine = engineWith(transport, expandedRuns = { expanded })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
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
        advanceUntilIdle()
        assertEquals(1, (engine.state.value as ViewState.Loaded).workflows.size)

        engine.setBranchFilter(true)
        advanceUntilIdle()
        assertTrue((engine.state.value as ViewState.Loaded).workflows.isEmpty())
    }

    @Test
    fun `没有 GitHub remote 时报告 NoGitRemote 且不发请求`() = runTest {
        val transport = RecordingTransport({ runsJson("completed", "success") })
        val engine = engineWith(transport, repoProvider = { null })

        backgroundScope.launch { engine.run() }
        engine.setVisible(true)
        advanceUntilIdle()

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
        advanceUntilIdle()

        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(2, transport.calls.get())
        val loaded = assertInstanceOf(ViewState.Loaded::class.java, engine.state.value)
        assertEquals(419, loaded.workflows[0].runs[0].run.runNumber)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew test --tests '*PollingEngineTest*'
```

期望：编译失败，`Unresolved reference: ViewState`。

- [ ] **Step 3: 写 `ViewState.kt`**

```kotlin
package com.github.ghactions.poll

import com.github.ghactions.model.WorkflowNode
import java.time.Instant

/**
 * 面板的完整状态。所有失败模式在类型层面穷举，
 * UI 必须为每个分支提供画面，不存在「空白且无解释」的情况。
 */
sealed interface ViewState {
    data object Loading : ViewState
    data object NoGitRemote : ViewState
    data object GhNotInstalled : ViewState
    data object GhNotLoggedIn : ViewState
    data class RateLimited(val resetAt: Instant) : ViewState
    data class Error(val message: String) : ViewState
    data class Loaded(
        val workflows: List<WorkflowNode>,
        val lastUpdated: Instant,
        val degraded: Boolean,
    ) : ViewState
}
```

- [ ] **Step 4: 写 `PollingEngine.kt`**

```kotlin
package com.github.ghactions.poll

import com.github.ghactions.api.ApiResult
import com.github.ghactions.api.EtagCache
import com.github.ghactions.api.GitHubActionsClient
import com.github.ghactions.model.Job
import com.github.ghactions.model.RepoCoordinates
import com.github.ghactions.model.RunNode
import com.github.ghactions.model.WorkflowNode
import com.github.ghactions.model.WorkflowRun
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 轮询循环与状态汇聚。不依赖 IDE 与 Swing，因此整个节奏行为
 * 可以用协程虚拟时间完整验证。
 *
 * 所有 client 调用均为阻塞式，调用方须在 IO 线程上启动 [run]。
 */
class PollingEngine(
    private val client: GitHubActionsClient,
    private val etags: EtagCache,
    private val repoProvider: () -> RepoCoordinates?,
    private val branchProvider: () -> String?,
    private val expandedRuns: () -> Set<Long>,
    private val now: () -> Instant = Instant::now,
) {

    private val _state = MutableStateFlow<ViewState>(ViewState.Loading)
    val state: StateFlow<ViewState> = _state.asStateFlow()

    private val visible = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var branchFilterEnabled = false

    private var lastRuns: List<WorkflowRun> = emptyList()
    private val lastJobs = mutableMapOf<Long, List<Job>>()

    fun setVisible(value: Boolean) {
        val previous = visible.value
        visible.value = value
        if (!previous && value) requestRefresh()
    }

    /** 强制刷新：丢弃 ETag 并立即唤醒循环。 */
    fun requestRefresh() {
        etags.clear()
        wake.trySend(Unit)
    }

    /** 切换分支过滤。不清 ETag——数据没变，只是展示范围变了。 */
    fun setBranchFilter(enabled: Boolean) {
        branchFilterEnabled = enabled
        wake.trySend(Unit)
    }

    /** 树的展开状态变化后调用，使新展开的 run 立刻加载 jobs。 */
    fun onExpansionChanged() {
        wake.trySend(Unit)
    }

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            if (!visible.value) {
                visible.first { it }
                continue
            }
            // 丢弃积压的唤醒信号，否则刚拉完就会被自己触发的信号立即重拉
            wake.tryReceive()

            val interval = pollOnce() ?: continue
            withTimeoutOrNull(interval) { wake.receive() }
        }
    }

    private fun pollOnce(): Duration? {
        val repo = repoProvider()
        if (repo == null) {
            _state.value = ViewState.NoGitRemote
            return PollingSchedule.intervalFor(visible.value, hasRunning = false, rateLimitRemaining = null)
        }

        var remaining: Int? = null

        when (val result = client.listRuns(repo)) {
            is ApiResult.Data -> {
                lastRuns = result.value
                remaining = result.rateLimitRemaining
            }

            is ApiResult.NotModified -> remaining = result.rateLimitRemaining

            is ApiResult.RateLimited -> {
                _state.value = ViewState.RateLimited(result.resetAt)
                return untilReset(result.resetAt)
            }

            ApiResult.GhNotInstalled -> {
                _state.value = ViewState.GhNotInstalled
                return PollingSchedule.IDLE
            }

            ApiResult.GhNotLoggedIn -> {
                _state.value = ViewState.GhNotLoggedIn
                return PollingSchedule.IDLE
            }

            is ApiResult.Error -> {
                _state.value = ViewState.Error(result.message)
                return PollingSchedule.IDLE
            }
        }

        val currentBranch = branchProvider()
        val visibleRuns = lastRuns
            .filter { !branchFilterEnabled || it.branch == currentBranch }
            .sortedByDescending { it.runNumber }

        val expanded = expandedRuns()
        for (run in visibleRuns) {
            if (run.id !in expanded) continue
            when (val jobsResult = client.listJobs(repo, run.id)) {
                is ApiResult.Data -> {
                    lastJobs[run.id] = jobsResult.value
                    jobsResult.rateLimitRemaining?.let { remaining = it }
                }

                is ApiResult.NotModified -> jobsResult.rateLimitRemaining?.let { remaining = it }

                is ApiResult.RateLimited -> {
                    _state.value = ViewState.RateLimited(jobsResult.resetAt)
                    return untilReset(jobsResult.resetAt)
                }

                // jobs 拉取失败不应清空已有的树，保留上一轮数据继续展示
                else -> Unit
            }
        }
        // 折叠的 run 释放缓存，避免长期占用内存
        lastJobs.keys.retainAll(expanded)

        val workflows = visibleRuns
            .groupBy { it.workflowName }
            .map { (name, runs) -> WorkflowNode(name, runs.map { RunNode(it, lastJobs[it.id]) }) }
            .sortedBy { it.name }

        val quota = remaining
        _state.value = ViewState.Loaded(
            workflows = workflows,
            lastUpdated = now(),
            degraded = quota != null && quota < PollingSchedule.LOW_QUOTA_THRESHOLD,
        )

        return PollingSchedule.intervalFor(
            visible = visible.value,
            hasRunning = visibleRuns.any { it.status.isRunning },
            rateLimitRemaining = quota,
        )
    }

    private fun untilReset(resetAt: Instant): Duration {
        val seconds = java.time.Duration.between(now(), resetAt).seconds
        return maxOf(seconds, 60L).seconds
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
./gradlew test --tests '*PollingEngineTest*'
```

期望：全部 PASS。若个别用例因时间推进边界失败，检查是否遗漏了断言前的 `runCurrent()`。

- [ ] **Step 6: 运行全部测试**

```bash
./gradlew test
```

期望：全部 PASS。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(poll): 添加轮询引擎与面板状态类型"
```

---

## Task 10: 项目级 Service 外壳

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/poll/ActionsPollingService.kt`

**Interfaces:**
- Consumes: `PollingEngine`（Task 9）、`GitHubActionsClient` / `JdkHttpTransport` / `EtagCache`（Task 6、7）、`GhCliTokenProvider` / `ProcessCommandRunner`（Task 5）、`IdeGitRepoProvider`（Task 4）
- Produces:
  - `@Service(Service.Level.PROJECT) class ActionsPollingService`
  - `val engine: PollingEngine`
  - `fun setExpanded(runId: Long, isExpanded: Boolean)`
  - `companion object { fun getInstance(project: Project): ActionsPollingService }`

这是纯装配层，没有分支逻辑，因此不写单测——它的正确性由编译期类型检查与 Task 13 的手动验证覆盖。

`@Service` 注解的 service **无需**在 `plugin.xml` 中注册。构造函数注入的 `CoroutineScope` 由平台提供，项目关闭时自动取消，不会有游离协程。

- [ ] **Step 1: 写实现**

```kotlin
package com.github.ghactions.poll

import com.github.ghactions.api.EtagCache
import com.github.ghactions.api.GitHubActionsClient
import com.github.ghactions.api.JdkHttpTransport
import com.github.ghactions.auth.GhCliTokenProvider
import com.github.ghactions.auth.ProcessCommandRunner
import com.github.ghactions.repo.IdeGitRepoProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 项目级 service，负责组装依赖并驱动轮询循环。
 * 注入的 CoroutineScope 在项目关闭时自动取消。
 */
@Service(Service.Level.PROJECT)
class ActionsPollingService(project: Project, scope: CoroutineScope) {

    private val etags = EtagCache()
    private val repoProvider = IdeGitRepoProvider(project)

    /** 当前树上处于展开状态的 run，决定了哪些 run 需要拉取 jobs。 */
    private val expanded: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    val engine: PollingEngine = PollingEngine(
        client = GitHubActionsClient(
            transport = JdkHttpTransport(),
            tokenProvider = GhCliTokenProvider(ProcessCommandRunner()),
            etags = etags,
        ),
        etags = etags,
        repoProvider = { repoProvider.currentRepo() },
        branchProvider = { repoProvider.currentBranch() },
        expandedRuns = { expanded.toSet() },
    )

    init {
        // client 调用是阻塞式的，必须放在 IO 线程上
        scope.launch(Dispatchers.IO) { engine.run() }
    }

    fun setExpanded(runId: Long, isExpanded: Boolean) {
        val changed = if (isExpanded) expanded.add(runId) else expanded.remove(runId)
        if (changed && isExpanded) engine.onExpansionChanged()
    }

    companion object {
        fun getInstance(project: Project): ActionsPollingService = project.service()
    }
}
```

展开时才唤醒轮询（需要立刻加载 jobs）；折叠只更新集合，不必打扰正在等待的循环。

- [ ] **Step 2: 编译验证**

```bash
./gradlew compileKotlin
```

期望：`BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "feat(poll): 添加项目级轮询 service"
```

---

## Task 11: 树模型与差异更新

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/ui/TreeItem.kt`
- Create: `src/main/kotlin/com/github/ghactions/ui/ActionsTreeModel.kt`
- Test: `src/test/kotlin/com/github/ghactions/ui/ActionsTreeModelTest.kt`

**Interfaces:**
- Consumes: `WorkflowNode` / `RunNode` / `WorkflowRun` / `Job` / `Step` / `RunStatus`（Task 2、3）
- Produces:
  - `sealed interface TreeItem { val id: String }`，成员：`WorkflowItem(name)` / `RunItem(run)` / `JobItem(job)` / `StepItem(jobId, step)`
  - `class ActionsTreeModel { val swingModel: DefaultTreeModel; val root: DefaultMutableTreeNode; fun apply(workflows: List<WorkflowNode>) }`
  - `fun enclosingRun(node: DefaultMutableTreeNode): RunItem?`

**这是全项目最关键的一处正确性要求。** 每 5 秒重建树时，如果整棵子树被替换，用户展开的节点会折叠、选中会丢失、滚动条会跳走——盯着流水线跑的时候这是灾难。

解决办法是给每个节点稳定身份，按身份**复用节点对象**：只要 `DefaultMutableTreeNode` 实例没被 remove/insert，`JTree` 的展开与选中状态就天然保持。因此测试断言的是**实例同一性**（`assertSame`）——这正是展开态得以保持的底层机制，比启动沙箱 IDE 手工点击更本质。

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.ghactions.ui

import com.github.ghactions.model.Job
import com.github.ghactions.model.RunNode
import com.github.ghactions.model.RunStatus
import com.github.ghactions.model.Step
import com.github.ghactions.model.WorkflowNode
import com.github.ghactions.model.WorkflowRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode

class ActionsTreeModelTest {

    private fun run(id: Long, number: Int, status: RunStatus, name: String = "CI") = WorkflowRun(
        id = id,
        runNumber = number,
        workflowName = name,
        branch = "main",
        status = status,
        htmlUrl = "https://example.test/$id",
        updatedAt = Instant.EPOCH,
    )

    private fun job(id: Long, status: RunStatus, steps: List<Step> = emptyList()) =
        Job(id = id, name = "build", status = status, steps = steps)

    private fun child(parent: DefaultMutableTreeNode, index: Int) =
        parent.getChildAt(index) as DefaultMutableTreeNode

    @Test
    fun `构建四层树`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(
                        RunNode(
                            run(1, 419, RunStatus.IN_PROGRESS),
                            listOf(job(88, RunStatus.IN_PROGRESS, listOf(Step(1, "Checkout", RunStatus.SUCCESS)))),
                        ),
                    ),
                ),
            ),
        )

        val workflow = child(model.root, 0)
        assertEquals("CI", (workflow.userObject as WorkflowItem).name)

        val runNode = child(workflow, 0)
        assertEquals(419, (runNode.userObject as RunItem).run.runNumber)

        val jobNode = child(runNode, 0)
        assertEquals(88L, (jobNode.userObject as JobItem).job.id)

        val stepNode = child(jobNode, 0)
        assertEquals("Checkout", (stepNode.userObject as StepItem).step.name)
    }

    @Test
    fun `状态变化时节点对象被复用而非替换`() {
        val model = ActionsTreeModel()
        val before = listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419, RunStatus.IN_PROGRESS), null))))
        model.apply(before)

        val workflowNode = child(model.root, 0)
        val runNode = child(workflowNode, 0)

        val after = listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419, RunStatus.SUCCESS), null))))
        model.apply(after)

        // 同一个节点实例被保留 —— 这正是 JTree 展开态得以保持的机制
        assertSame(workflowNode, child(model.root, 0))
        assertSame(runNode, child(child(model.root, 0), 0))
        // 但承载的数据已经更新
        assertEquals(RunStatus.SUCCESS, (runNode.userObject as RunItem).run.status)
    }

    @Test
    fun `新增 run 插入而不影响既有节点`() {
        val model = ActionsTreeModel()
        model.apply(listOf(WorkflowNode("CI", listOf(RunNode(run(1, 418, RunStatus.SUCCESS), null)))))
        val oldRunNode = child(child(model.root, 0), 0)

        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(
                        RunNode(run(2, 419, RunStatus.IN_PROGRESS), null),
                        RunNode(run(1, 418, RunStatus.SUCCESS), null),
                    ),
                ),
            ),
        )

        val workflow = child(model.root, 0)
        assertEquals(2, workflow.childCount)
        assertEquals(419, (child(workflow, 0).userObject as RunItem).run.runNumber)
        assertSame(oldRunNode, child(workflow, 1))
    }

    @Test
    fun `消失的 run 被移除`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(RunNode(run(1, 418, RunStatus.SUCCESS), null), RunNode(run(2, 419, RunStatus.SUCCESS), null)),
                ),
            ),
        )

        model.apply(listOf(WorkflowNode("CI", listOf(RunNode(run(2, 419, RunStatus.SUCCESS), null)))))

        val workflow = child(model.root, 0)
        assertEquals(1, workflow.childCount)
        assertEquals(419, (child(workflow, 0).userObject as RunItem).run.runNumber)
    }

    @Test
    fun `jobs 从未加载变为已加载时挂上子节点`() {
        val model = ActionsTreeModel()
        model.apply(listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419, RunStatus.IN_PROGRESS), null)))))
        val runNode = child(child(model.root, 0), 0)
        assertEquals(0, runNode.childCount)

        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(RunNode(run(1, 419, RunStatus.IN_PROGRESS), listOf(job(88, RunStatus.SUCCESS)))),
                ),
            ),
        )

        assertSame(runNode, child(child(model.root, 0), 0))
        assertEquals(1, runNode.childCount)
    }

    @Test
    fun `不同 workflow 同名 job 的身份互不冲突`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode("CI", listOf(RunNode(run(1, 1, RunStatus.SUCCESS, "CI"), listOf(job(10, RunStatus.SUCCESS))))),
                WorkflowNode("CD", listOf(RunNode(run(2, 1, RunStatus.SUCCESS, "CD"), listOf(job(20, RunStatus.SUCCESS))))),
            ),
        )

        assertEquals(2, model.root.childCount)
        assertNotSame(
            child(child(child(model.root, 0), 0), 0),
            child(child(child(model.root, 1), 0), 0),
        )
    }

    @Test
    fun `空数据清空整棵树`() {
        val model = ActionsTreeModel()
        model.apply(listOf(WorkflowNode("CI", listOf(RunNode(run(1, 419, RunStatus.SUCCESS), null)))))
        model.apply(emptyList())

        assertEquals(0, model.root.childCount)
    }

    @Test
    fun `enclosingRun 能从各层节点回溯所属 run`() {
        val model = ActionsTreeModel()
        model.apply(
            listOf(
                WorkflowNode(
                    "CI",
                    listOf(
                        RunNode(
                            run(1, 419, RunStatus.SUCCESS),
                            listOf(job(88, RunStatus.SUCCESS, listOf(Step(1, "Checkout", RunStatus.SUCCESS)))),
                        ),
                    ),
                ),
            ),
        )

        val workflow = child(model.root, 0)
        val runNode = child(workflow, 0)
        val jobNode = child(runNode, 0)
        val stepNode = child(jobNode, 0)

        assertEquals(1L, enclosingRun(runNode)?.run?.id)
        assertEquals(1L, enclosingRun(jobNode)?.run?.id)
        assertEquals(1L, enclosingRun(stepNode)?.run?.id)
        assertNull(enclosingRun(workflow))
        assertNull(enclosingRun(model.root))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./gradlew test --tests '*ActionsTreeModelTest*'
```

期望：编译失败，`Unresolved reference: ActionsTreeModel`。

- [ ] **Step 3: 写 `TreeItem.kt`**

```kotlin
package com.github.ghactions.ui

import com.github.ghactions.model.Job
import com.github.ghactions.model.RunStatus
import com.github.ghactions.model.Step
import com.github.ghactions.model.WorkflowRun

/**
 * 树节点承载的数据。[id] 是节点的稳定身份，
 * 差异更新据此判断「同一个节点」，从而复用节点对象、保持展开态。
 */
sealed interface TreeItem {
    val id: String
    val label: String
    val status: RunStatus?
}

data class WorkflowItem(val name: String) : TreeItem {
    override val id: String get() = "w:$name"
    override val label: String get() = name
    override val status: RunStatus? get() = null
}

data class RunItem(val run: WorkflowRun) : TreeItem {
    override val id: String get() = "r:${run.id}"
    override val label: String get() = "#${run.runNumber}"
    override val status: RunStatus get() = run.status
}

data class JobItem(val job: Job) : TreeItem {
    override val id: String get() = "j:${job.id}"
    override val label: String get() = job.name
    override val status: RunStatus get() = job.status
}

data class StepItem(val jobId: Long, val step: Step) : TreeItem {
    override val id: String get() = "s:$jobId:${step.number}"
    override val label: String get() = step.name
    override val status: RunStatus get() = step.status
}
```

- [ ] **Step 4: 写 `ActionsTreeModel.kt`**

```kotlin
package com.github.ghactions.ui

import com.github.ghactions.model.WorkflowNode
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * 按稳定身份做差异更新的树模型。
 *
 * 关键约束：状态刷新时**必须复用**已存在的节点对象。只要节点实例没有被
 * remove/insert，JTree 的展开状态与选中状态就天然保持——这是每 5 秒刷新
 * 却不打断用户的唯一办法。
 */
class ActionsTreeModel {

    val root: DefaultMutableTreeNode = DefaultMutableTreeNode("WORKFLOWS")
    val swingModel: DefaultTreeModel = DefaultTreeModel(root)

    fun apply(workflows: List<WorkflowNode>) {
        syncChildren(root, workflows.map { WorkflowItem(it.name) }) { node, index ->
            val workflow = workflows[index]
            syncChildren(node, workflow.runs.map { RunItem(it.run) }) { runNode, runIndex ->
                val jobs = workflow.runs[runIndex].jobs.orEmpty()
                syncChildren(runNode, jobs.map { JobItem(it) }) { jobNode, jobIndex ->
                    val job = jobs[jobIndex]
                    syncChildren(jobNode, job.steps.map { StepItem(job.id, it) }) { _, _ -> }
                }
            }
        }
        swingModel.nodeStructureChanged(root)
    }

    /**
     * 让 [parent] 的子节点与 [items] 对齐：
     * 身份相同则复用节点实例并更新数据，缺失则新建，多余则删除，顺序按 [items] 排列。
     */
    private fun syncChildren(
        parent: DefaultMutableTreeNode,
        items: List<TreeItem>,
        recurse: (DefaultMutableTreeNode, Int) -> Unit,
    ) {
        val existing = LinkedHashMap<String, DefaultMutableTreeNode>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            val item = child.userObject as? TreeItem ?: continue
            existing[item.id] = child
        }

        parent.removeAllChildren()

        items.forEachIndexed { index, item ->
            val node = existing.remove(item.id)?.also { it.userObject = item }
                ?: DefaultMutableTreeNode(item)
            parent.add(node)
            recurse(node, index)
        }
    }
}

/** 从任意节点回溯其所属的 run；workflow 节点与根节点返回 null。 */
fun enclosingRun(node: DefaultMutableTreeNode): RunItem? {
    var current: DefaultMutableTreeNode? = node
    while (current != null) {
        val item = current.userObject
        if (item is RunItem) return item
        current = current.parent as? DefaultMutableTreeNode
    }
    return null
}
```

`removeAllChildren()` 后再逐个 `add` 回去，看似会破坏节点身份，实则不然：
被 `add` 回去的是**同一批节点实例**，`JTree` 的展开状态记录在 `TreePath` 上，
而 `TreePath` 由节点实例的 `equals` 决定。`DefaultMutableTreeNode` 未重写
`equals`，用的是实例同一性——所以只要实例没换，路径就没变，展开态就保住了。

- [ ] **Step 5: 运行测试确认通过**

```bash
./gradlew test --tests '*ActionsTreeModelTest*'
```

期望：全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(ui): 添加按身份差异更新的树模型"
```

---

## Task 12: 节点渲染与空状态面板

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/ui/ActionsTreeCellRenderer.kt`
- Create: `src/main/kotlin/com/github/ghactions/ui/EmptyStatePanel.kt`

**Interfaces:**
- Consumes: `TreeItem` / `WorkflowItem` / `RunItem` / `JobItem` / `StepItem`（Task 11）、`RunStatus`（Task 2）、`ViewState`（Task 9）
- Produces:
  - `class ActionsTreeCellRenderer : ColoredTreeCellRenderer`
  - `fun iconForStatus(status: RunStatus?): Icon?`
  - `object EmptyStatePanel { fun forState(state: ViewState): JComponent }`

纯展示代码，无分支逻辑值得单测，由 Task 13 的手动验证覆盖。

- [ ] **Step 1: 写 `ActionsTreeCellRenderer.kt`**

```kotlin
package com.github.ghactions.ui

import com.github.ghactions.model.RunStatus
import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.text.DateFormatUtil
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

fun iconForStatus(status: RunStatus?): Icon? = when (status) {
    RunStatus.IN_PROGRESS -> AnimatedIcon.Default.INSTANCE
    RunStatus.QUEUED -> AllIcons.RunConfigurations.TestNotRan
    RunStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
    RunStatus.FAILURE -> AllIcons.RunConfigurations.TestFailed
    RunStatus.CANCELLED -> AllIcons.RunConfigurations.TestTerminated
    RunStatus.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
    null -> null
}

/**
 * 节点渲染。主要信息用常规色，附属信息（分支、时间）用次要色，
 * 使一眼扫过去信息层次分明。
 */
class ActionsTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val item = node.userObject as? TreeItem

        if (item == null) {
            append("WORKFLOWS", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            return
        }

        icon = iconForStatus(item.status)

        val muted = item.status == RunStatus.CANCELLED || item.status == RunStatus.SKIPPED
        val mainAttributes = when {
            item is WorkflowItem -> SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
            muted -> SimpleTextAttributes.GRAYED_ATTRIBUTES
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(item.label, mainAttributes)

        if (item is RunItem) {
            val run = item.run
            if (run.branch.isNotEmpty()) {
                append("  ·  ${run.branch}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            if (run.updatedAt.epochSecond > 0) {
                val ago = DateFormatUtil.formatBetweenDates(run.updatedAt.toEpochMilli(), System.currentTimeMillis())
                append("  ·  $ago", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            toolTipText = run.htmlUrl.ifEmpty { null }
        }
    }
}
```

若 `AllIcons.RunConfigurations` 下某个常量在目标平台不存在，编译会直接报错——
此时用 IDE 补全在该命名空间下挑选语义最接近的替代即可，不要自行绘制图标。

- [ ] **Step 2: 写 `EmptyStatePanel.kt`**

```kotlin
package com.github.ghactions.ui

import com.github.ghactions.poll.ViewState
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CopyPasteManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import java.awt.datatransfer.StringSelection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent

/**
 * 各非数据状态的画面。每种状态都给出下一步动作，
 * 而不只是报错——用户看到的永远是「怎么办」，不是「出错了」。
 */
object EmptyStatePanel {

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun forState(state: ViewState): JComponent {
        val panel = JBPanelWithEmptyText()
        val text = panel.emptyText

        when (state) {
            ViewState.Loading -> text.text = "正在加载…"

            ViewState.NoGitRemote ->
                text.text = "当前项目没有 GitHub remote"

            ViewState.GhNotInstalled -> {
                text.text = "未检测到 GitHub CLI"
                text.appendLine(
                    "前往安装 GitHub CLI",
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { BrowserUtil.browse("https://cli.github.com") }
            }

            ViewState.GhNotLoggedIn -> {
                text.text = "尚未登录 GitHub CLI"
                text.appendLine("请在终端运行：gh auth login", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
                text.appendLine(
                    "复制命令",
                    SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                ) { CopyPasteManager.getInstance().setContents(StringSelection("gh auth login")) }
            }

            is ViewState.RateLimited ->
                text.text = "API 配额已用尽，将于 ${TIME_FORMAT.format(state.resetAt)} 恢复"

            is ViewState.Error ->
                text.text = "加载失败：${state.message}"

            is ViewState.Loaded ->
                text.text = "没有找到工作流运行记录"
        }

        return panel
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew compileKotlin
```

期望：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat(ui): 添加节点渲染器与空状态面板"
```

---

## Task 13: 面板装配、工具栏与手动验证

**Files:**
- Create: `src/main/kotlin/com/github/ghactions/ui/ActionsTreePanel.kt`
- Modify: `src/main/kotlin/com/github/ghactions/poll/ActionsPollingService.kt`（新增 `observe` 方法）
- Modify: `src/main/kotlin/com/github/ghactions/ui/ActionsToolWindowFactory.kt`（改为挂载真实面板）

**Interfaces:**
- Consumes: `ActionsPollingService`（Task 10）、`ActionsTreeModel` / `enclosingRun` / `RunItem`（Task 11）、`ActionsTreeCellRenderer` / `EmptyStatePanel`（Task 12）、`ViewState`（Task 9）
- Produces:
  - `fun ActionsPollingService.observe(onState: (ViewState) -> Unit)`（作为成员方法）
  - `class ActionsTreePanel(project: Project) : JBPanel<ActionsTreePanel>`

**可见性检测用 `HierarchyListener` 而非 `ToolWindowManagerListener`**：前者直接反映
组件是否真的显示在屏幕上，语义精确，且不受平台 listener 签名变更影响。

- [ ] **Step 1: 给 `ActionsPollingService` 添加 `observe`**

在 `ActionsPollingService` 类中新增以下方法（并补上对应 import）：

```kotlin
    /** 在 EDT 上订阅状态变化。协程随 service 的 scope 一同取消。 */
    fun observe(onState: (ViewState) -> Unit) {
        scope.launch(Dispatchers.EDT) {
            engine.state.collect { onState(it) }
        }
    }
```

同时把构造函数参数 `scope` 改为属性以便复用：

```kotlin
class ActionsPollingService(project: Project, private val scope: CoroutineScope) {
```

新增 import：

```kotlin
import com.intellij.openapi.application.EDT
```

- [ ] **Step 2: 写 `ActionsTreePanel.kt`**

```kotlin
package com.github.ghactions.ui

import com.github.ghactions.poll.ActionsPollingService
import com.github.ghactions.poll.ViewState
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.text.DateFormatUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeSelectionModel

private const val CARD_TREE = "tree"
private const val CARD_EMPTY = "empty"

class ActionsTreePanel(private val project: Project) : JBPanel<ActionsTreePanel>(BorderLayout()) {

    private val service = ActionsPollingService.getInstance(project)
    private val treeModel = ActionsTreeModel()
    private val tree = Tree(treeModel.swingModel)

    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val emptyHolder = JPanel(BorderLayout())
    private val statusLabel = JLabel(" ", SwingConstants.LEFT)

    private var branchFilterEnabled = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ActionsTreeCellRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

        content.add(JBScrollPane(tree), CARD_TREE)
        content.add(emptyHolder, CARD_EMPTY)

        add(createToolbar(), BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        wireExpansionTracking()
        wireDoubleClick()
        wireVisibilityTracking()

        service.observe(::render)
    }

    private fun createToolbar(): JPanel {
        val group = DefaultActionGroup(
            object : AnAction("刷新", "立即刷新", AllIcons.Actions.Refresh) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = service.engine.requestRefresh()
            },
            object : ToggleAction("只看当前分支", "只显示当前分支的运行记录", AllIcons.Vcs.Branch) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent): Boolean = branchFilterEnabled
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    branchFilterEnabled = state
                    service.engine.setBranchFilter(state)
                }
            },
            object : AnAction("在浏览器中打开", "打开选中运行的 GitHub 页面", AllIcons.General.Web) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedRunUrl() != null
                }
                override fun actionPerformed(e: AnActionEvent) {
                    selectedRunUrl()?.let(BrowserUtil::browse)
                }
            },
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("GitHubActionsViewer", group, true)
        toolbar.targetComponent = tree
        return JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.WEST) }
    }

    /** 展开状态直接驱动 jobs 的按需拉取——用户看什么，才请求什么。 */
    private fun wireExpansionTracking() {
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) = update(event, true)
            override fun treeCollapsed(event: TreeExpansionEvent) = update(event, false)

            private fun update(event: TreeExpansionEvent, expanded: Boolean) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val item = node.userObject
                if (item is RunItem) service.setExpanded(item.run.id, expanded)
            }
        })
    }

    private fun wireDoubleClick() {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val url = selectedRunUrl() ?: return false
                BrowserUtil.browse(url)
                return true
            }
        }.installOn(tree)
    }

    /** 面板不在屏幕上显示时彻底暂停轮询，重新显示时立即强刷一次。 */
    private fun wireVisibilityTracking() {
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                service.engine.setVisible(isShowing)
            }
        }
    }

    private fun selectedRunUrl(): String? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return enclosingRun(node)?.run?.htmlUrl?.ifEmpty { null }
    }

    private fun render(state: ViewState) {
        if (state is ViewState.Loaded && state.workflows.isNotEmpty()) {
            treeModel.apply(state.workflows)
            cards.show(content, CARD_TREE)
            val ago = DateFormatUtil.formatBetweenDates(state.lastUpdated.toEpochMilli(), System.currentTimeMillis())
            statusLabel.text = if (state.degraded) {
                "  最后更新于 $ago · API 配额偏低，已降低刷新频率"
            } else {
                "  最后更新于 $ago"
            }
        } else {
            emptyHolder.removeAll()
            emptyHolder.add(EmptyStatePanel.forState(state), BorderLayout.CENTER)
            emptyHolder.revalidate()
            emptyHolder.repaint()
            cards.show(content, CARD_EMPTY)
            statusLabel.text = " "
        }
    }
}
```

- [ ] **Step 3: 改写 `ActionsToolWindowFactory.kt`**

```kotlin
package com.github.ghactions.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class ActionsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ActionsTreePanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
```

- [ ] **Step 4: 构建与全量测试**

```bash
./gradlew build
```

期望：`BUILD SUCCESSFUL`，全部测试通过。

- [ ] **Step 5: 手动验证**

```bash
./gradlew runIde
```

在沙箱 IDE 中打开一个**有 GitHub Actions 的真实仓库**，逐项确认：

1. 右侧栏出现 "GitHub Actions" 图标，点开显示 workflow 列表
2. 展开一个 run → 出现 jobs；再展开 job → 出现 steps
3. 触发一次 workflow（推一个 commit 或在网页点 Run），确认 5 秒内出现转圈图标并持续更新
4. **展开态保持**：保持某个 run 展开，等待至少两轮刷新，确认它没有被折叠
5. 收起工具窗口，确认停止发请求（可在 GitHub 设置页观察配额，或临时加日志）；重新展开，确认立即刷新
6. 点「只看当前分支」，确认只剩当前分支的 run
7. 双击一个 run，确认浏览器打开对应 GitHub 页面
8. 临时执行 `gh auth logout`，重启沙箱，确认显示「尚未登录 GitHub CLI」与复制命令链接；之后 `gh auth login` 恢复

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(ui): 装配工具窗口面板、工具栏与状态订阅"
```

---

## 完成标准

- `./gradlew build` 通过，全部测试为绿
- 上述手动验证 8 项全部确认
- 面板在每种 `ViewState` 下都有明确画面与下一步动作
- 刷新过程中展开态、选中态、滚动位置均不丢失
