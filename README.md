# GitHub Actions Viewer

IntelliJ IDEA 插件。在侧边栏实时查看当前项目的 GitHub Actions 执行状态，不必切到浏览器。

```
WORKFLOWS
└─ Build and Push Docker Image
   ├─ ◉ #422  ·  main  ·  2 分钟前
   │  └─ ◉ build-and-push                    1m 12s
   │     ├─ ✓ Set up job                        3s
   │     ├─ ✓ Checkout                          5s
   │     └─ ◉ Build and push
   ├─ ✓ #421  ·  main  ·  5 小时前
   └─ ✗ #420  ·  feat/x  ·  6 小时前
```

## 功能

- **四层树**：Workflow → Run → Job → Step，逐层展开
- **自动刷新**：有构建在跑时 5 秒一刷，全部跑完降到 60 秒，面板收起后完全停止
- **按需加载**：只有展开的 run 才拉取它的 job 详情，折叠后不再请求
- **执行耗时**：每个 job 与 step 显示实际耗时
- **状态一目了然**：失败用错误色标出；workflow 折叠着也带最近一次运行的状态图标
- **输入即搜索**：在树上直接打字定位任意节点
- **快捷跳转**：悬停行内按钮、右键菜单或工具栏按钮打开对应的 GitHub 页面
- **分支过滤**：一键只看当前分支的运行记录

## 前置要求

| 项 | 要求 |
|---|---|
| IDE | IntelliJ IDEA 2026.2.x |
| 认证 | 已安装 [GitHub CLI](https://cli.github.com) 并完成 `gh auth login` |
| 仓库 | 当前项目的 git remote 指向 `github.com` |

插件不存储任何凭据，每次通过 `gh auth token` 取用，token 仅存在于内存中。

## 安装

```bash
./gradlew buildPlugin
```

产物在 `build/distributions/github-actions-viewer-*.zip`，通过
`Settings → Plugins → ⚙ → Install Plugin from Disk...` 安装。

## 使用

打开任意 GitHub 项目后，点击右侧边栏的 **GitHub Actions** 图标。

| 操作 | 方式 |
|---|---|
| 展开查看 job / step | 点击展开箭头 |
| 打开 GitHub 页面 | 悬停行右侧按钮 / 右键菜单 / 工具栏按钮 |
| 复制运行链接 | 右键菜单 |
| 立即刷新 | 工具栏 ⟳ |
| 只看当前分支 | 工具栏分支图标 |
| 查找节点 | 选中树后直接打字 |

面板底部常驻显示数据的更新时间。

### 代理

遵从 IDE 的设置（`Settings → System Settings → HTTP Proxy`）：只有**显式**配置了代理
（手动填写或 PAC）时才走代理；默认的「自动检测」与「无代理」一律直连。

## 已知限制

- 仅支持 `github.com`，不支持 GitHub Enterprise
- 仅显示当前打开项目的仓库，不支持多仓库看板
- 默认取最近 15 条运行记录，更早的历史请到 GitHub 网页查看
- 只读：不支持重跑、取消或手动触发 workflow
- 不在插件内查看日志——用行内按钮或右键菜单跳转到 GitHub 页面查看

## 开发

```bash
./gradlew runIde     # 启动装好插件的沙箱 IDE
./gradlew test       # 运行测试
./gradlew build      # 构建 + 测试
```

调试提示：每次 `runIde` 都会新开一个沙箱实例且不会自动关闭旧的，容易对着旧版本
反复排查。建议先清理：

```bash
pkill -9 -f idea-sandbox; ./gradlew runIde
```

排查问题时，沙箱日志里有两行关键信息：

```bash
grep -E "解析到 GitHub 仓库|面板状态" .intellijPlatform/sandbox/*/*/log/idea.log
```

### 关于 SDK

构建默认使用**本机已安装的 IDE** 作为 SDK（`/Applications/IntelliJ IDEA.app`）。
自 2025.3 起 IDEA Community 不再单独发布 artifact，Gradle 插件转而下载完整安装包
（macOS 上是 1~2GB 的 .dmg）；直接用本机 IDE 既快，版本也与实际运行环境一致。

指定其他路径：

```bash
./gradlew runIde -PlocalIdePath="/path/to/IntelliJ IDEA.app"
```

本机找不到时会自动回落到下载，CI 等环境不受影响。

### 结构

```
model/   领域模型与状态枚举，无任何依赖
auth/    通过 gh CLI 取 token
repo/    git remote 解析（纯函数）+ IDE 适配层
api/     HTTP 传输、ETag 缓存、GitHub API 客户端
poll/    轮询节奏决策与循环，汇出单一 ViewState
ui/      树模型、渲染、工具窗口面板
```

依赖单向向下。除 `repo` 的适配层、`poll` 的 service 外壳与 `ui` 之外，
其余代码不依赖 IDE 运行环境，因此绝大部分逻辑（含轮询节奏、树的差异更新）
都能用纯 JVM 单测覆盖，无需启动沙箱。

## 设计要点

**为什么按需加载 job**：GitHub 的 runs 接口一次返回所有运行的概要，但每个 run 的
job 详情需要单独请求。若为全部运行拉取详情，每轮会产生十几个请求。因此只对树上
展开的 run 拉取，并缓存已完成运行的结果——它们是终态，不会再变。

**为什么不用 ETag 代替缓存**：ETag 命中 304 时不计入 API 配额，但**仍要走一次完整
的网络往返**。省配额与省延迟是两回事，在慢网络下后者才是用户感受到的部分。

**为什么树刷新不打断操作**：每次刷新按节点身份做差异更新、复用节点对象，而不是
重建整棵树。这样展开状态、选中状态与滚动位置都不会丢失——盯着一条正在跑的流水线时，
每 5 秒被折叠一次是不可接受的。
