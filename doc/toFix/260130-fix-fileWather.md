文件监听, 有几个致命问题:
1. 在codemark对应行, 上方新增行(甚至任何编辑),  都会使得hints等下移一行, 实际应当 自动跟随原始的那行代码移动, 而且不应该影响editor的正常编辑行为, 现在编辑后光标就会失焦到toolwindows, 注意只有点击gutter才需要关注toolwindow, 同时也不需要editor失焦
2. 在codemark对应行, 上方删行  都会使得hints等上移一行

核验上面的问题, 我需要一个合理的完备方案

---

## 方案与修改摘要

1. **插入行时行号跟随**（`BookmarkDocumentListener.kt`）：在 `documentChanged` 中，当 `lineDelta > 0` 时，对 `bookmarkLine >= changeLine` 的书签也做行号后移（原先只对 `bookmarkLine > changeLine` 更新，导致“被顶下去”的那一行书签未更新）。修改后，在 codemark 对应行上方新增行时，hints/gutter 会随该行代码下移。
2. **编辑不抢焦点**（`BookmarkNavigationListener.kt`）：去掉因光标移动/切换文件而自动调用 `checkAndHandleBookmarkNavigation` 的逻辑（快速跳转、普通光标移动、文件选择变化后的延迟检查）。仅保留对 `pendingNavigation` 的处理（供日后“定位到行再选中节点”等流程使用）。工具窗口的打开与树节点选中只由 **gutter 点击** 触发（`BookmarkHighlighterService` → SelectionBus → 面板），编辑或单纯移动光标不再导致 editor 失焦到 tool window。
3. **删除行时的行数**：`documentChanged` 在文档已修改之后调用，无法取得被删除的旧片段，当前仍用 `oldLength` 与平均行宽估算 `oldLines`，删除多行时 `lineDelta` 可能不精确；已在代码中加注释说明该限制。