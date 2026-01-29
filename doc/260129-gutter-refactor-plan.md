# Gutter 刷新重构方案（最高效最精准）

**基线**：2025-01-29，当前实现为「双源 + 多处触发刷新」。  
**目标**：单一数据源、单一刷新路径，符合 JetBrains 对「动态行号数据」的推荐做法。

---

## 一、现状与问题

### 1.1 当前架构：双源绘制 Gutter

| 组件 | 机制 | 数据来源 | 刷新方式 |
|------|------|----------|----------|
| **BookmarkHighlighterService** | `RangeHighlighter` + `GutterIconRenderer` | 自维护 `fileIndex`（repository 遍历） | `refreshOpenEditors()` |
| **BookmarkLineMarkerProvider** | `LineMarkerProviderDescriptor`，`collectSlowLineMarkers` | 自维护 cache + repository | `clearAllCache()` + `DaemonCodeAnalyzer.restart()` |

两套逻辑都在画 gutter 图标，导致：

- **刷新路径分散**：ViewModel 删/增发 `RefreshGutterAll` → Panel 清 cache + 全量 daemon 重启；HighlighterService 在 `rebuildIndex()` 后又要清 LineMarker cache + daemon 重启 + refreshOpenEditors。
- **时序依赖**：LineMarker 由 daemon 驱动，若 daemon 在数据就绪前跑完，gutter 为空；需在 `rebuildIndex()` 后再触发 daemon，逻辑绕。
- **重复工作**：同一批书签既建 `fileIndex` 又建 LineMarker cache，且要同时维护两套失效策略。

### 1.2 官方建议（联网结论）

- **LineMarkerProvider**：适合「静态、基于 PSI 元素」的 gutter（如导航到定义）。
- **RangeHighlighter + GutterIconRenderer**：适合「动态、基于行号/外部数据」的 gutter（如测试结果、控制台输出）。

书签是「动态、行号 + 外部仓库」，更符合 **RangeHighlighter** 的用法；单一来源可避免双源同步与 daemon 重启成本。

---

## 二、推荐方案：单源 = BookmarkHighlighterService（RangeHighlighter）

### 2.1 思路

- **Gutter 只由 BookmarkHighlighterService 绘制**：继续用 `fileIndex` + `applyHighlighters(editor, entries)`（含 `GutterIconRenderer`），不新增逻辑。
- **停用 BookmarkLineMarkerProvider 的 gutter 输出**：在插件中不再用其画书签图标（见下「实施要点」）。
- **单一刷新路径**：凡「书签数据就绪/变更」只做一件事：  
  `rebuildIndex()` 完成 → 在 EDT 上 `refreshOpenEditors()`。  
  不再调用 `BookmarkLineMarkerProvider.clearAllCache()`，也不再为 gutter 做 `DaemonCodeAnalyzer.restart()`。

效果：

- 数据源唯一：repository → HighlighterService.fileIndex。
- 刷新唯一：index 更新后只刷新已打开编辑器，无 daemon、无 LineMarker cache。
- 初始加载、增删书签、右键删除等，都走同一路径（repository 监听 → rebuildIndex → refreshOpenEditors），行为一致、易推理。

### 2.2 与 toFix/260129-fix-gutter.md 的对应

| 需求 | 本方案下的做法 |
|------|----------------|
| gutter 在 IDEA 启动后自动显示 | `start()` → `rebuildIndex()` → 完成后 EDT 上 `refreshOpenEditors()`，无需 daemon。 |
| 创建/删除书签时 gutter 刷新 | repository 变更 → `rebuildIndex()` → 同上。 |
| 左键 gutter 自动展开对应树节点（含内层） | 不改数据源与刷新；在 Highlighter 的 `getClickAction()` 里增强 `SelectionBus.requestSelect()` / 树展开逻辑，单独实现。 |

### 2.3 实施要点（代码级）

1. **BookmarkHighlighterService**
   - `rebuildIndex()` 末尾：仅保留「在 EDT 上调用 `refreshOpenEditors()`」，**删除**对 `BookmarkLineMarkerProvider.clearAllCache()` 和 `DaemonCodeAnalyzer` 的调用及相关 import。
   - 保持对 `FileEditorManagerListener` 的监听（文件打开/切换时对当前编辑器 `refreshEditor`）。

2. **BookmarkLineMarkerProvider**
   - **方案 2a（推荐）**：从 `plugin.xml` 的 `lineMarkerProvider` 中**移除**该 provider，不再注册。  
     或  
   - **方案 2b**：保留注册，但 `getLineMarkerInfo` / `collectSlowLineMarkers` 始终不添加书签相关 `LineMarkerInfo`（仅保留空实现或返回空集合），避免双图标。

3. **BookmarkViewModel**
   - 删除所有 `BookmarkLineMarkerProvider.clearAllCache()`、`clearCache(path)`、`updateCacheForNewBookmark(...)` 调用。
   - 删除对 `RefreshGutterAll` 的 emit（或保留枚举但不做 gutter 相关逻辑）。

4. **BookmarkPanel**
   - 对 `RefreshGutterAll` 的 case：不再调用 `BookmarkLineMarkerProvider.clearAllCache()` 和 `DaemonCodeAnalyzer.restart()`；可改为空实现或移除该分支（若 ViewModel 不再发出）。

5. **BookmarkDocumentListener / 其它**
   - 若有仅用于「通知 LineMarker 刷新」的 `BookmarkLineMarkerProvider.clearCache(...)`，删除或改为无需调用。

6. **左键展开树（内层）**
   - 不放在本重构范围内；在 Highlighter 的点击逻辑或 SelectionBus/树展开处单独做「展开到对应节点路径」。

### 2.4 取舍

- **失去**：通过「设置 | Editor | General | Gutter Icons」对**本插件**图标的单独开关（该开关主要面向继承 `GutterIconDescriptor` 的 LineMarkerProvider）。若产品需要此开关，可采用下条「备选方案」。
- **获得**：单源、无 daemon 依赖、刷新路径短、初始加载与增删行为一致，符合「最高效最精准」的重构目标。

---

## 三、备选方案：单源 = BookmarkLineMarkerProvider（保留 Gutter 设置开关）

若必须保留「Gutter Icons」里对书签图标的可见性配置：

- **Gutter 只由 BookmarkLineMarkerProvider 绘制**；数据来源改为**单一索引**（例如由 BookmarkHighlighterService 暴露只读 `fileIndex`，或新建轻量 BookmarkGutterIndexService）。
- **BookmarkHighlighterService** 只做行背景高亮，**不再**设置 `RangeHighlighter.gutterIconRenderer`。
- **刷新路径**：repository / 索引更新后 → 清 LineMarker cache → 在 EDT 上 `DaemonCodeAnalyzer.restart(...)`；且**在索引就绪后再触发**（例如 rebuildIndex 完成后 invokeLater 里清 cache + restart），以保证「IDEA 启动后 gutter 自动显示」。
- 代价：仍依赖 daemon 与全量/按文件 restart，刷新成本高于「仅 refreshOpenEditors」；实现上需保证「索引先于 daemon 运行」的时序。

---

## 四、建议执行顺序

1. 按「二、推荐方案」修改 BookmarkHighlighterService（去掉 LineMarker + Daemon 调用，只留 refreshOpenEditors）。
2. 停用或清空 BookmarkLineMarkerProvider 的书签绘制（2a 或 2b）。
3. ViewModel / Panel / 其它调用处移除对 BookmarkLineMarkerProvider 与 RefreshGutterAll 的 gutter 相关逻辑。
4. 验证：启动后打开含书签文件 → gutter 出现；增删书签、右键删除 → gutter 即时更新。
5. 左键展开到内层树节点：单独排期，在现有点击逻辑上增强。

---

## 五、参考文献与依据

- JetBrains: [Line Marker Provider](https://plugins.jetbrains.com/docs/intellij/line-marker-provider.html)（PSI、leaf 元素、两阶段收集）。
- 社区结论：静态/PSI 用 LineMarkerProvider，动态/行号用 RangeHighlighter + GutterIconRenderer。
- 项目内：`BookmarkHighlighterService`、`BookmarkLineMarkerProvider`、`BookmarkPanel`（RefreshGutterAll）、`BookmarkViewModel`（clearAllCache/RefreshGutterAll）。
