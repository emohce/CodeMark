# 书签树导航 — 实现变更摘要

> **基线**：2026-06-04  
> **状态**：已实现  
> **需求规格（主文档，含验收与演进）**：[260604-cursor-tree-operations-spec.md](./260604-cursor-tree-operations-spec.md)

本文档仅记录**相对基线的代码级变更**与**曾出现的问题**，不重复需求条文与 runIde 清单。

---

## 1. 曾出现的问题

| 现象 | 根因 |
|------|------|
| 非搜索 ↑↓ 只在顶层 file root 间跳 | 懒加载未展开、可见行过少；旧逻辑只处理 `parent == root` |
| 非搜索 ←→ 无效 | Dispatcher 仅搜索期生效；`expandSelectedPath` 不 populate、不进首子 |
| 搜索 ←→ 清高亮 | `SpeedSearchBase` 默认消费左右键 |
| 搜索过滤未生效（历史） | `searchQuery` 未与 `enteredPrefix` 同步 |
| ↑↓ 与 ←→ 互相串台（一次按键触发两种行为） | `KeyEventDispatcher` 与 `InputMap` 双通道同时绑定裸方向键；`InputMap` 仅写 `"none"` 未注册空 Action，JTree 默认 ←→ 仍可能生效 |
| 非搜索 ↑↓ 一次按键步进 2 行 | `moveSelectionConsideringLazyLoad` 内 find+fallback 双路径（已删）；以及 Dispatcher 与 JTree `processKeyEvent` 同键重复处理 |
| 搜索期 ← 折叠后选中跳到其它节点 | 搜索期未同步 `selectedNodeId`，`CollapseNode` 触发 `updateTree` 后 `selectNodeWithRetry` 用旧 id 拉回；折叠前未先锁定 `selectionPath` |
| 搜索期 ↑↓ 异常/误退出搜索 | `currentSearchRelevantIds` 在方向键时读 `enteredPrefix`，空则 `exitSearchMode`；↑↓ 每次 `SelectNode` 触发 `updateTree` 与可见行导航冲突 |
| 搜索期到不了最后一项匹配 | `moveSelectionByVisibleRow` 用 `coerceIn` 把下一步钳回当前行；已展开祖先也在落点集导致停在中间 Group |
| 搜索高亮与导航 ids 不一致 | `TreeSpeedSearch` 基于 `displayName`（名称）高亮，`BookmarkIndexService.search()` 基于 `searchableText()`（名称+描述+文件路径等）匹配；导致高亮节点不在 `visibleNodeIds` 中，无法导航到达 |

---

## 2. 代码变更

### `BookmarkTreeUtil.kt`

| 符号 | 作用 |
|------|------|
| `moveSelectionConsideringLazyLoad` | ↑↓ 单路径 `moveSelectionByVisibleRow`（修复 find+fallback 双步进）；搜索期传 `visibleNodeIds` |
| `expandForNavigation` | ←→ 之 →：populate 占位 → 展开 → 首子 |
| 搜索 ↑↓ | 与非搜索共用 `moveSelectionConsideringLazyLoad`，可见行可落 Group |

### `BookmarkPanel.kt`

| 符号 | 作用 |
|------|------|
| `installTreeNavigationKeyDispatcher` | 树焦点下**唯一**处理裸 ↑↓←→（在 `installShortcuts` 之后注册，优先于 `TreeSpeedSearch`）；带 Alt/Ctrl/Shift/Meta 不拦截（留给 Alt+↑↓ 兄弟排序等） |
| `suppressDefaultTreeVerticalKeys` | `actionMap["none"]` 空 Action + `WHEN_FOCUSED_WINDOW`；`installShortcuts` 后再次绑定 |
| `dispatchTreeNavigationKey` / `BookmarkDropPreviewTree.processKeyEvent` | 统一 ↑↓←→ 入口；`isHandlingTreeNavigationKey` 防 Dispatcher 与 树双处理 |
| 搜索期选中 / 折叠 | `updateTree` 以树当前选中为准、不 `selectNodeWithRetry`；`collapsePathKeepingSelection` 先选中再折叠；↑↓ 用 `isKeyboardTreeNavigating` 跳过 `SelectNode`；`searchNavigationStopIds`；`moveSelectionByVisibleRow` 去掉 `coerceIn` |
| `onSpeedSearchPrefixChanged` | 同步 `searchQuery`；首次搜索 `bootstrapSearchExpansion` |
| `treeExpanded` | 仅 `hasPlaceholder` 时 `populateChildren` |
| 搜索期 | `TreeSelectionListener` 跳过 `SelectNode`；折叠仍写 `expandedNodeIds` |
| `searchNavigationStopIds` | 优先添加所有直接匹配节点（高亮节点）到导航目标集合，确保即使其父 group 未展开也能导航到达 |

### `BookmarkIndexService.kt`

| 符号 | 作用 |
|------|------|
| `nameByNode` | 新增索引映射存储节点名称 |
| `matchingNodeIdsForSearch` | 修改为基于节点 `name` 匹配，与 TreeSpeedSearch 高亮逻辑一致 |

### `BookmarkTreeDnDHandler.kt`

- `exportAsDrag`：按坐标选中并聚焦，无需先单击。

---

## 2.1 展开状态保持（2026-06-04 补丁）

| 现象 | 根因 |
|------|------|
| 树中点击书签 / 选中节点时整棵树「全量收缩」 | `updateTree` 对每次 state 变化（含纯选中/导航）都重建 + `applyExpandedIdsToTree`；程序化 `collapsePath` 又回调 `treeCollapsed` 发 `CollapseNode` 反噬 `expandedNodeIds`，级联收缩 |
| Gutter 点击折叠组下深节点：展开选中后又被收回 | 程序展开的祖先未写入 `expandedNodeIds`，随后 Refresh 重建按旧快照折叠 |
| 搜索 ↑↓ 定位匹配项展开的祖先，退出搜索后未回收 | 搜索导航展开经 `treeExpanded` 持久化进 `expandedNodeIds`，与「临时定位」语义不符 |

改动（[BookmarkPanel.kt](../src/main/kotlin/emohce/presentation/toolwindow/panel/BookmarkPanel.kt)）：

| 符号 | 作用 |
|------|------|
| `isApplyingExpansion` 守卫 + `applyExpandedIdsToTree` | 程序对齐展开/折叠期间，`treeExpanded`/`treeCollapsed` 不回灌 `Expand`/`CollapseNode` intent，杜绝级联收缩 |
| `updateTree` 的 `transientOnly` 判定 | rootNode/expandedNodeIds/引用映射（按实例）+ 搜索临时集均未变 → 跳过重建与 re-sync，仅高亮+选中，保持树原样 |
| `gutterPersistExpandIds` + `persistAncestorExpansion` | 同步并入祖先展开 id（`effectiveExpandedNodeIds`），并异步 `ExpandNode`；Gutter 不再每次 `Refresh`（仅全失败时兜底） |
| `SelectionBus` 优先 `expandToNodeByDomainPath` | 深节点先沿域路径 populate/展开再选中；`isSelectingFromSideEffect` 时跳过 `SelectNode` 避免二次 `selectNodeWithRetry` 扰动 |
| `transientOnly` 用 `==` 比较 `expandedNodeIds` / 引用 Map | 修复 `ExpandNode` 每次 copy 新 Set、引用 Map 新实例导致误判全量重建 |
| `mergeLiveTreeExpansionIntoPersistCache` | 结构重建前从 JTree 采集当前展开 id 并入 `gutterPersistExpandIds`，修复拖拽等操作后 `expandedNodeIds` 滞后于 UI 的全量收缩 |
| `gutterPersistExpandIds` 清理 | 仅 `removeAll` 已持久 id，避免 `retainAll` 误删未写入 ViewModel 的 UI 展开 |
| `BookmarkStore.isRecentSelfSave` 1200ms | 降低拖拽落盘后 VFS 触发 `reloadBookmarks`（`forceRebuild`）概率 |
| `isSearchAutoExpanding` + `bootstrapSearchExpansion`/搜索 ↑↓ | 搜索导航自动展开只计入临时集 `searchBootstrapExpandIds`（不持久化）；退出搜索清空临时集并按持久 `expandedNodeIds` 重建 → 自动展开回收，**用户手动展开/折叠仍持久** |

`TreeRefreshKind`（[BookmarkViewModel.kt](../src/main/kotlin/emohce/presentation/toolwindow/BookmarkViewModel.kt) + `updateTree`）：

| 粒度 | 触发 | 树行为 |
|------|------|--------|
| `SKIP` | 选中/导航、展开折叠 intent | 不 diff/setRoot；仅展开集变时对齐 |
| `DIFF` | 增删移改（内存已更新） | `applyDiff` + expand-only；`expandedIdsWithAncestors`；invisible 根无 NodeView 时仍递归子节点 |
| `FULL` | Refresh、磁盘 reload、diff 失败 | `setRoot` + 全量展开对齐 |

---

## 2.2 树搜索快捷键直接进入 Full 模式探索记录（2026-06-05 记录）

### 需求背景
用户期望在书签树（JTree）上按下 `Ctrl+F`（Mac 上为 `Cmd+F`）触发搜索时，能够**直接、即时**进入 `TreeSearchMode.FULL` 模式并展现搜索框及 "Full" 角标。

### 尝试方案与实现错误（Lessons Learned）
1. **第一次尝试（试图通过 `showPopup` 唤起）**：
   - **方案**：在 `toggleSearchModeFromFindShortcut` 的未激活搜索分支，以及首个空白分支中，在调用 `updateSearchModeLabel()` 前调用了 `treeSpeedSearch.showPopup()`。
   - **现象**：运行时**无效（no use）**。
   - **错误根因**：
     - IntelliJ 的 `SpeedSearchBase.showPopup()` 依赖于内部状态中的 search popup 实例（`myPopup`），而该实例仅在有实际输入的非空前缀（`enteredPrefix`）时才会被懒加载创建并渲染。
     - 在搜索尚未激活且 `enteredPrefix` 为空时直接调用 `showPopup()`，内部的 popup 实例为 `null`，导致该调用在运行时成为空操作（No-op），没有任何视觉和交互反馈。
     - 直接修改 `shouldShowTreeSearchModeBadge` 以使 pending 状态直接显示角标，会打破单元测试中 `pending search mode does not show mode badge before query input` 的原有设计。

### 后续处理建议
- 保持目前代码的回滚状态（已回退至 2026-06-04 正常通过所有 61 个单元测试的基线）。
- 未来若需彻底解决此问题，需重构 SpeedSearch 的唤起机制（例如通过模拟触发第一个字符输入，或使用平台顶层的非 Tree 专有搜索框组件如 `SearchTextField` / 侧边独立搜索栏进行代替，而非直接强行唤起 headless/无前缀的 `TreeSpeedSearch` 弹窗）。

---

## 3. 验证

- 手工：见主文档 [§10](./260604-cursor-tree-operations-spec.md#10-验收)。
- 自动化：`./gradlew test` — `BookmarkTreeUtilTest`、`BookmarkTreeModelBuilderTest`。
