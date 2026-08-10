# 运行列表截断提示

## 问题

工具窗口每轮只拉取最近 `GitHubActionsClient.DEFAULT_RUN_LIMIT`（15）条运行记录，按 workflow 分组挂到树上。列表末尾没有任何交代，下方是一片空白，用户会怀疑数据没加载全。

## 目标

在树的末尾给出一行明确的终止提示，说清「就这么多，更多的在 GitHub 上」。不引入分页或加载更多。

## 设计

### 树末尾的提示行

树的最后一行（root 的最后一个子节点，与 workflow 分组平级）显示灰色小字：

```
仅显示最近 15 次运行，在 GitHub 上查看全部 ›
```

- 条数取自 `DEFAULT_RUN_LIMIT`，不写死字面量，改常量文案自动跟随。
- 点击整行，用浏览器打开 `https://github.com/{owner}/{name}/actions`。
- 只在 `ViewState.Loaded` 且有数据时出现；空状态卡片里不出现。

选择树内一行而非并入底部状态栏，是因为用户的落空感来自列表末尾那片空白，提示必须长在列表尾巴上才治得住。底部状态栏（`最后更新于 X`）保持原样不动。

### 组件改动

**`TreeItem.kt`** — 新增

```kotlin
data class TruncationNoticeItem(val limit: Int, val actionsUrl: String) : TreeItem {
    override val id: String get() = "notice"
    override val label: String get() = "仅显示最近 $limit 次运行，在 GitHub 上查看全部 ›"
    override val status: RunStatus? get() = null
    override val isLeaf: Boolean get() = true
}
```

`id` 恒定为 `"notice"`，因此天然融入 `ActionsTreeModel` 的差异更新：节点实例每轮被复用，不会删了重建，也不会干扰兄弟节点的展开态。`limit` 与 `actionsUrl` 参与 `equals`，仓库切换时通过 `nodeChanged` 更新文案。

**`ActionsTreeModel.kt`** — `apply` 增加一个可空的 notice 参数，追加到 root 一层的 items 末尾。root 的 `recurse` 回调按下标回查 `workflows[index]`，notice 的下标会越界，需要在回调里对超出 `workflows.size` 的下标直接返回。`applyTo` 同步透传该参数。

**`ActionsTreeCellRenderer.kt`** — `TruncationNoticeItem` 走独立分支：无图标，整行 `GRAYED_SMALL_ATTRIBUTES`，`toolTipText` 设为目标 URL。不参与现有按状态着色的逻辑。

**`ActionsRowRenderer.kt`** — 右侧悬停按钮的判定条件是 `item is RunItem`，提示行自然不显示按钮，无需改动。

**`ViewState.kt`** — `Loaded` 增加 `repo: RepoCoordinates` 字段。`PollingEngine` 构造 `Loaded` 处已持有 `repo` 局部变量，直接传入。这比从 run 的 `htmlUrl` 截取字符串可靠，且仓库坐标本就属于「当前展示的是什么」这一状态。

**`ActionsTreePanel.kt`** — `render` 中由 `state.repo` 构造 `TruncationNoticeItem` 并传给 `applyTo`。已有的 `mouseClicked` 监听增加一个分支：命中行的 `userObject` 是 `TruncationNoticeItem` 时，`openInBrowser(item.actionsUrl)`（复用现有的后台线程封装）。命中判定是整行，不限于右端。

### 测试

- `ActionsTreeModelTest`：提示行始终是 root 的最后一个子节点；连续两次 `apply` 后该节点是同一实例；传 null 时不出现。
- `ActionsTreeCellRendererTest`（或现有 renderer 测试）：提示行文案随 `limit` 变化，且不带状态图标。
- 所有构造 `ViewState.Loaded` 的既有测试随字段增加而更新（编译期即可暴露）。

## 不做

- 加载更多 / 分页。轮询每轮全量重建数据，维护「已加载 N 页」的状态会和刷新逻辑打架，而历史运行记录通常直接去网页看。
- 让条数可配置。先看这行提示是否已经解决问题。
