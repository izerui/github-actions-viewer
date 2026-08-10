# 每个 workflow 独立的「加载更多」

取代 `2026-08-11-run-list-truncation-notice-design.md`。该方案已实现并提交（`515afa9`），本设计将其回退。

## 问题

每轮只拉取最近 15 条运行记录，列表末尾戛然而止，看着像没加载全。上一版加了一行「仅显示最近 15 次运行」的提示，但用户要的是真能往下翻，且以 workflow 为单位。

## 目标

每个 workflow 分组的末尾有一行「加载更多」，点一次往下追加 15 条该 workflow 的历史运行记录。翻到底后该行消失。

## 关键决策

**历史记录不参与轮询刷新。** 点击时单独发一次请求，结果追加后就静止。轮询继续只管最近 15 条，轮询的请求数不增加。

理由：5 秒一轮的情况下，每个加载过更多的 workflow 若都要跟着刷新，每小时多 720 次请求，叠加现有的 runs 与 jobs 请求会逼近 5000/小时的配额上限、频繁触发 `degraded` 降频。而历史 run 几乎都是已完成的终态，刷新它换来的只有「老 run 被 re-run 了能自动更新」这个近乎不存在的场景。

**翻到底后该行直接消失**，不留任何占位文字。

## 数据

### `workflow_id` 的引入

现在按 run 的 `name` 在客户端分组，没有 workflow 的身份。per-workflow 分页必须用 `/repos/{o}/{r}/actions/workflows/{workflow_id}/runs`，因此：

- `RunDto` 增加 `@SerializedName("workflow_id")`（GitHub 一直返回，只是没取）。
- `WorkflowRun` 增加 `workflowId: Long`，缺失时为 0。
- `WorkflowNode` 增加 `workflowId: Long`（取该组第一个 run 的值）与 `canLoadMore: Boolean`。

分组键仍是 `workflowName`，维持现状；`workflowId` 只用于分页请求。（注：workflow yaml 里自定义 `run-name:` 会让同一 workflow 的 run 名字各异、分组散开——这是既有行为，本次不动。）

### 客户端

`GitHubActionsClient` 新增：

```kotlin
fun listWorkflowRuns(
    repo: RepoCoordinates,
    workflowId: Long,
    page: Int,
    perPage: Int = LOAD_MORE_PAGE_SIZE,
): ApiResult<List<WorkflowRun>>
```

打到 `$API_BASE/repos/{owner}/{name}/actions/workflows/{workflowId}/runs?per_page={perPage}&page={page}`。不走 ETag 缓存——它是一次性的用户操作，缓存没有收益。`LOAD_MORE_PAGE_SIZE = 15`，与首屏一致。

### 引擎状态

`PollingEngine` 增加两份状态，键为 workflowName：

- `extraRuns: MutableMap<String, List<WorkflowRun>>` —— 已加载的历史记录，不参与轮询刷新。
- `exhaustedWorkflows: MutableSet<String>` —— 已翻到底的 workflow。

新增挂起函数 `loadMore(workflowName: String)`：

1. 从当前视图找到该 workflow 的 `workflowId`；找不到（如 id 为 0）直接返回。
2. 页码 = `extraRuns[name].size / LOAD_MORE_PAGE_SIZE + 2`。首屏是第 1 页，历史从第 2 页起。
3. 请求成功后，与已有记录按 run id 去重，追加到 `extraRuns[name]`。
4. 返回条数 `< LOAD_MORE_PAGE_SIZE` 时把该 workflow 记入 `exhaustedWorkflows`。
5. 立即发布新的 `ViewState.Loaded`，不等下一轮轮询。

失败时不改动状态、不进 `exhausted`，让用户可以再点一次重试。

### 合并规则

构造 `WorkflowNode` 时，该组的 runs = 轮询侧的最近 15 条 + `extraRuns[name]`，按 run id 去重（**轮询侧优先**，它的状态更新），再按 `updatedAt` 降序。

分支过滤开启时，`extraRuns` 与轮询数据用同一套过滤，避免历史记录绕过过滤器。

`canLoadMore` = `workflowId != 0L && name !in exhaustedWorkflows`。

仓库切换时（`repoProvider` 返回了不同坐标）清空这两份状态——否则上一个仓库的历史记录会混进新仓库的树里。

## UI

### 树节点

```kotlin
data class LoadMoreItem(val workflowName: String, val loading: Boolean) : TreeItem {
    override val isLeaf: Boolean get() = true
    override val id: String get() = "more:$workflowName"
    override val label: String get() = if (loading) "加载中…" else "加载更多"
    override val status: RunStatus? get() = null
}
```

`id` 只由 `workflowName` 决定，`loading` 变化走 `nodeChanged` 而非重建，行不会闪。

`ActionsTreeModel.apply` 在每个 workflow 的 runs 之后追加该项（`canLoadMore` 为真时）。runs 层的 `recurse` 回调同样要对超出 runs 范围的下标提前返回。

### 渲染

`ActionsTreeCellRenderer` 为 `LoadMoreItem` 走独立分支：`loading` 时用 `AnimatedIcon.Default.INSTANCE`，否则无图标；文字用 `GRAYED_ATTRIBUTES`。

### 交互

`ActionsTreePanel` 的 `mouseClicked` 增加分支：命中 `LoadMoreItem` 且非 `loading` 时调用 `service.loadMore(name)`，整行可点。点击瞬间把该 workflow 记入面板本地的 `loadingWorkflows` 并重绘——和 `pendingRuns` 同一套思路，网络往返要好几秒，反馈不能等数据回来才给。数据到达（该 workflow 的 runs 变多或进入 exhausted）后清除标记。

`loading` 期间重复点击被忽略。

## 回退

`git revert 515afa9`，移除 `TruncationNoticeItem`、`ViewState.Loaded.repo`、`ActionsTreeModel` 的 notice 参数及对应测试。`GitHubActionsClient` 的 companion 保持 `internal`（新常量同样需要被 UI 之外读取）。

## 测试

- `DtoTest`：解析 `workflow_id`；字段缺失时为 0。
- `GitHubActionsClientTest`：`listWorkflowRuns` 的 URL 含正确的 workflowId、page、per_page。
- `PollingEngineTest`：
  - `loadMore` 后该 workflow 的 runs 变多，其他 workflow 不变。
  - 再走一轮轮询，历史记录仍在（不被最近 15 条冲掉），且轮询请求数不增加。
  - 返回条数不足一页时标记 exhausted，`canLoadMore` 变 false。
  - 与轮询数据 id 重叠时去重，且保留轮询侧的状态。
  - 请求失败不改变状态，可重试。
  - 分支过滤对历史记录同样生效。
  - 仓库切换后历史记录被清空。
- `ActionsTreeModelTest`：`LoadMoreItem` 位于该 workflow 的 runs 之后；`canLoadMore` 为假时不出现；刷新时节点复用。
- `ActionsRowRendererTest`：`loading` 时显示忙碌图标。

## 不做

- 历史记录参与轮询刷新（见「关键决策」）。
- 「上次加载到第几页」的持久化。IDE 重启后回到首屏 15 条即可。
- 让首屏条数可配置。
