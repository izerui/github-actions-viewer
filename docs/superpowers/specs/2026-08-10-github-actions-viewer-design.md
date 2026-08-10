# GitHub Actions Viewer — IntelliJ IDEA 插件设计

日期：2026-08-10
状态：已确认，待实现

## 1. 目标

在 IntelliJ IDEA 中提供一个侧边栏工具窗口，实时展示当前项目的 GitHub Actions
执行状态，形态对标 VSCode 的 GitHub Actions 插件。

第一版只回答一个问题：**我刚推的代码，流水线现在跑到哪了？**

## 2. 范围

### 包含

- Workflow → Run → Job → Step 四层树形展示，带状态图标
- 自适应自动刷新
- 手动刷新
- 「只看当前分支」过滤
- 双击 run 在浏览器打开对应 GitHub 页面

### 明确不做（第一版）

日志查看、重跑 / 取消 / 手动触发、失败通知、多仓库、PAT 认证兜底、
翻页历史、GitHub Enterprise 支持。

这些是刻意推迟，不是遗漏。

## 3. 关键决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 形态 | IntelliJ IDEA 插件 | 用户明确要求 |
| 仓库来源 | 仅当前打开的项目 | 零配置，发挥 IDE 的上下文感知优势 |
| 认证 | 仅复用 `gh` CLI 登录态 | 零密钥管理；未安装/未登录时显示引导 |
| 数据获取 | `gh auth token` 取 token + 自建 HTTP 客户端 | 需要读响应头才能做 ETag 和限流感知，`gh api` 给不了 |
| 刷新 | 自适应轮询 | 兼顾实时感与配额消耗 |

## 4. 技术栈

| 项 | 选择 |
|---|---|
| 语言 | Kotlin |
| 构建 | Gradle + IntelliJ Platform Gradle Plugin 2.x（`org.jetbrains.intellij.platform`） |
| 目标 IDE | IntelliJ IDEA 2024.2+（`since-build 242`） |
| 并发 | Kotlin Coroutines，使用平台注入的 project 级 `CoroutineScope` |
| UI | Swing，基于 `com.intellij.ui.treeStructure.Tree` + `AllIcons` / `AnimatedIcon` |
| JSON | Gson（IntelliJ 平台捆绑）。不选 kotlinx.serialization：后者需 Kotlin 编译器插件且运行时版本须与平台捆绑版严格匹配，否则运行期 `NoSuchMethodError` |

## 5. 架构

```
┌─────────────────────────────────────────────────┐
│  ui/          ToolWindowFactory · TreeModel     │
│               CellRenderer · EmptyStatePanel    │
└────────────────────┬────────────────────────────┘
                     │ 订阅 StateFlow<ViewState>
┌────────────────────┴────────────────────────────┐
│  poll/        ActionsPollingService             │
│               (project service + CoroutineScope)│
└──────┬───────────────────────┬──────────────────┘
       │                       │
┌──────┴─────────┐    ┌────────┴──────────┐
│  api/          │    │  repo/            │
│  GitHubClient  │    │  GitRemoteResolver│
│  (HTTP + ETag) │    └───────────────────┘
└──────┬─────────┘
       │
┌──────┴──────────┐   ┌──────────────────┐
│  auth/          │   │  model/          │
│  TokenProvider  │   │  纯数据类 + 枚举  │
└─────────────────┘   └──────────────────┘
```

依赖方向单向向下。`api/`、`auth/`、`repo/`、`model/`、`poll/` 均不依赖 Swing，
`api/`、`auth/`、`model/` 不依赖 IDE 环境，可用纯 JVM 单测覆盖。

### 5.1 模块职责

**`model/`** — 纯数据类，零依赖。`WorkflowRun`、`Job`、`Step`、`RunStatus`。

`RunStatus` 是 sealed 类型，把 GitHub 的 `status` + `conclusion` 双字段
收敛为单一状态：`Queued` / `InProgress` / `Success` / `Failure` /
`Cancelled` / `Skipped`。所有 GitHub API 的表达怪异之处止步于此边界，
UI 层只需 `when` 一个枚举。

**`auth/`** — `TokenProvider` 接口 + `GhCliTokenProvider` 实现。
执行 `gh auth token`，返回 `Success(token)` 或结构化失败
（`GhNotInstalled` / `GhNotLoggedIn`）。进程执行抽象为可替换接口，
测试时注入假实现。

**`repo/`** — `GitRemoteResolver`：从项目 VCS 配置读 origin URL，
解析为 `RepoCoordinates(owner, name)`。URL 解析为纯函数。

**`api/`** — `GitHubActionsClient`：唯一进行 HTTP 通信的模块。
持有 ETag 缓存，返回 `ApiResult`：`Data` / `NotModified` /
`RateLimited(resetAt)` / `Error`。

**`poll/`** — `ActionsPollingService`：项目级 service，
承载自适应轮询状态机，汇出单一 `StateFlow<ViewState>`。

**`ui/`** — 订阅 `StateFlow`，在 EDT 上差异更新树。

## 6. 树结构与数据拉取

### 6.1 树的形状

```
WORKFLOWS
├─ Build and Push Docker Image          ← workflow（按名字分组）
│  ├─ ◉ #419  ·  main  ·  2m ago        ← run
│  │  ├─ ◉ build-and-push                ← job
│  │  │  ├─ ✓ Set up job                 ← step
│  │  │  ├─ ✓ Checkout
│  │  │  └─ ◉ Build and push
│  │  └─ ◉ build-and-push (/question_bank)
│  ├─ ✓ #418  ·  main  ·  1h ago
│  └─ ✓ #417  ·  main  ·  3h ago
└─ Run Tests
   └─ ✗ #212  ·  feat/x  ·  5h ago
```

Run 节点显示编号、分支名、相对时间。

「只看当前分支」实现为工具栏的过滤 toggle，而非独立的第二棵树——
两棵树意味着双份状态管理与渲染成本，实际效果等同于过滤。
监听 VCS 分支变化，切分支时自动重新过滤。

### 6.2 Jobs 按需拉取（关键决策）

GitHub API 的形态：

- `GET /repos/{o}/{r}/actions/runs` — 一次返回所有 run 概要，不含 jobs
- `GET /repos/{o}/{r}/actions/runs/{id}/jobs` — 每个 run 需单独请求

若每轮为 30 个 run 全量拉取 jobs，5 秒一轮即约 22000 次/小时，
远超 5000/小时 的配额上限。

**规则：runs 列表每轮必拉；jobs 仅拉取树上当前处于展开状态的 run。**

用户展开某个 run 才拉取并持续刷新其 jobs，折叠后停止。
稳态下每轮 2~3 个请求，且多数命中 ETag 返回 304（不计配额）。

该策略天然对齐用户注意力：正在查看的内容刷新最勤，不可见的内容零消耗。

### 6.3 展示量

默认取最近 30 个 run。不做翻页与「加载更多」——
插件定位是盯当下，历史浏览交给 GitHub 网页。

## 7. 轮询引擎

### 7.1 节奏状态机

```
        ┌──────────────────────────────────────┐
        │  PAUSED  ← tool window 不可见         │
        │  (完全不发请求)                       │
        └────┬─────────────────────────▲───────┘
   变为可见  │                         │ 变为不可见
        ┌────▼─────────────────────────┴───────┐
        │  ACTIVE (5s)  ← 存在 queued /         │
        │                 in_progress 的 run    │
        └────┬─────────────────────────▲───────┘
   全部完成  │                         │ 出现新的运行中 run
        ┌────▼─────────────────────────┴───────┐
        │  IDLE (60s)   ← 全部 run 已完成       │
        └──────────────────────────────────────┘
```

暂停条件采用「tool window 不可见」而非「IDE 窗口失焦」：
更精确，也避免用户切到浏览器查资料时误暂停。
从 PAUSED 恢复时立即强制刷新一次（忽略 ETag）。

### 7.2 ETag

每个端点的 ETag 缓存于客户端，后续请求携带 `If-None-Match`。
无变化时 GitHub 返回 304 且不计入 rate limit。

一条运行 10 分钟的流水线共约 120 轮轮询，实际计费的仅为状态真正变化的那几轮。

### 7.3 限流与退避

读取每次响应的 `X-RateLimit-Remaining`：

- **剩余 < 100**：间隔拉长至 5 分钟，面板顶部显示降级提示条
- **403 / 429（已耗尽）**：进入 `RateLimited` 状态，停止轮询至
  `X-RateLimit-Reset` 时刻，显示「配额已用尽，将于 HH:MM 恢复」

不做无脑重试。受限时明确告知用户在等什么、等到何时。

### 7.4 单一数据出口

```kotlin
sealed interface ViewState {
    object Loading              // 首次加载，尚无数据
    object NoGitRemote          // 项目无 GitHub remote
    object GhNotInstalled       // 未安装 gh
    object GhNotLoggedIn        // 未执行 gh auth login
    data class RateLimited(val resetAt: Instant)
    data class Error(val message: String)
    data class Loaded(
        val workflows: List<WorkflowNode>,
        val lastUpdated: Instant,
        val degraded: Boolean,   // 配额偏低等降级状态
    )
}
```

暴露为 `StateFlow<ViewState>`。所有失败模式在类型层面穷举，
编译器强制 UI 处理全部分支，不存在「空白面板且无解释」的情况。

手动刷新 = 清除 ETag + 立即触发一轮。

## 8. UI

### 8.1 展开态保持

每 5 秒重建树若处理不当，会导致展开态折叠、选中丢失、滚动位置跳转。

**方案：为每个节点赋予稳定身份，按身份做差异更新。**

| 节点 | 身份 |
|---|---|
| Workflow | workflow 名 |
| Run | run id |
| Job | job id |
| Step | job id + step number |

刷新时逐节点比对：状态变化则更新并重绘，消失则移除，新增则插入。
展开态、选中态、滚动位置天然保持。

树自身持有的展开状态同时驱动 6.2 的 jobs 拉取策略。

### 8.2 视觉

使用平台内置图标，自动跟随 IDEA 明暗主题：

| 状态 | 图标 |
|---|---|
| 运行中 | `AnimatedIcon.Default` |
| 成功 | 绿色对勾 |
| 失败 | 红色叉 |
| 排队中 | 灰色时钟 |
| 已取消 / 跳过 | 灰色，节点文字降为次要色 |

Run 节点富文本渲染：`#419` 用主色，`· main · 2m ago` 用次要色。

### 8.3 工具栏

三个操作：**刷新**、**只看当前分支**（toggle）、**在浏览器中打开**。

双击任意 run 节点亦可在浏览器打开对应 GitHub 页面。
此功能是第一版不做日志查看的前提——查看日志与重跑只需一次双击即可到达，
无需在插件内构建半成品的日志查看器。

### 8.4 空状态

每种 `ViewState` 均有对应界面，且提供下一步动作：

| 状态 | 展示 |
|---|---|
| 未安装 gh | 「未检测到 GitHub CLI」+ 安装页链接 |
| 未登录 | 「请运行 `gh auth login`」+ 一键复制命令按钮 |
| 无 GitHub remote | 「当前项目没有 GitHub remote」（说明性，非报错） |
| 配额耗尽 | 「配额已用尽，将于 15:42 恢复」 |

底部状态栏常驻显示「最后更新于 N 秒前」，使数据新鲜度始终可见。

## 9. 测试策略

原则：将尽可能多的逻辑置于无需启动沙箱 IDE 即可测试的位置。

### 纯 JVM 单测

- `GitRemoteResolver`：SSH / HTTPS / 带 `.git` 后缀 / 带端口 / 非 GitHub URL
- `RunStatus` 收敛：覆盖 `status` × `conclusion` 全部组合，
  含 `completed` + `null` 等畸形数据
- `GhCliTokenProvider`：注入假进程执行器，验证正常 / 未安装 / 未登录三种分类
- `GitHubActionsClient`：假 HTTP 传输层，验证 ETag 发送与 304 处理、
  403 限流解析、JSON 反序列化
- **轮询状态机**：时钟抽象为接口，虚拟时间驱动，验证
  有运行中 → 5s、全完成 → 60s、不可见 → 停止、恢复 → 立即强刷、
  配额低 → 降级

- **树差异更新**：验证刷新后节点对象被**复用**而非替换。节点对象复用正是
  展开态得以保持的底层机制，因此该测试比启动沙箱 IDE 手工点击更本质。
  操作 `DefaultTreeModel` 即可，不需要 `JTree` 实例，故仍属纯 JVM 测试。

不编写基于 `BasePlatformTestCase` 的平台测试——所有值得测的逻辑均已下沉至
无需 IDE 环境的层次。

### 手动验证

沙箱 IDE 打开真实仓库，触发一次 workflow，确认动画图标与实时更新。

## 10. 项目结构

```
build.gradle.kts
settings.gradle.kts
src/main/
├── kotlin/.../ghactions/
│   ├── model/      RunStatus.kt  WorkflowRun.kt  Job.kt
│   ├── auth/       TokenProvider.kt  GhCliTokenProvider.kt
│   ├── repo/       GitRemoteResolver.kt
│   ├── api/        GitHubActionsClient.kt  ApiResult.kt  EtagCache.kt
│   ├── poll/       ActionsPollingService.kt  ViewState.kt  PollingSchedule.kt
│   └── ui/         ActionsToolWindowFactory.kt  ActionsTreePanel.kt
│                   ActionsTreeModel.kt  ActionsTreeCellRenderer.kt
│                   EmptyStatePanel.kt
└── resources/META-INF/plugin.xml
src/test/kotlin/    与主目录同构
```

文件保持小而专注。单个文件承担多重职责时（如 `ActionsTreePanel`
同时管理布局、事件与数据映射）即为拆分信号。
