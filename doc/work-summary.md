# Work Summary

## Context
- User request (2026-01-22 14:52 UTC+08): Continue implementing "引用关系图形化展示" per Bookmark-X AI guide. Pending items also include richer inlay configs and large-tree perf.
- Constraints: Use Kotlin, Swing + IntelliJ UI DSL, Clean Architecture + MVI. No tests requested now. Must log summary before code changes.
- User request (2026-01-22 15:52 UTC+08): Fix the whole project's several errors (multiple compile issues observed in Bookmark repository/panel/inlay classes).

## Current Focus
- Implement graphical reference visualization (beyond tree view).
- Polish graph UX (arrows, tooltips, layout) per follow-up suggestion.
- Resolve compilation errors across repository/panel/inlay components before feature work.

## Next Actions
- Fix BookmarkLineEndInlayProvider compile errors (ImmediateConfigurable createComponent signature) and other reported type mismatches in repository/panel/viewmodel.
- Review existing reference data/state and ToolWindow UI to design graph view.
- Implement visualization and integration.
- Verify manually and report.

## Log
- 2026-01-22 15:52: Logged new request to fix project compile errors (BookmarkRepositoryImpl, BookmarkPanel, BookmarkViewModel, inlay provider). No code changes yet.
- 2026-01-22: Noted compile error in BookmarkLineEndInlayProvider createComponent signature; adjusted work plan accordingly.
- 2026-01-23 17:07: User reported ServiceConfigurationError for CoroutineExceptionHandler (IntelliJ CoroutineExceptionHandlerImpl not a subtype) and empty Bookmark toolwindow content; planning to remove bundled kotlinx-coroutines overrides and rely on platform-provided version.
- 2026-01-23 17:21: User reported line end hints not showing; investigating BookmarkLineEndInlayProvider registration and data source for current file.
- 2026-01-23 17:23: Analysis: provider registered for Java/Kotlin in plugin.xml; code resolves root via ServiceLocator every collect; likely no hints because stored bookmarks missing file path match or runBlocking on EDT may block collectors—plan to cache lookup and filter by current file path; also ensure extension point name matches new InlayHintsProvider API (line-end inlays use com.intellij.codeInsight.inlayProvider).
- 2026-01-23 17:36: Build error: “Suspension functions can only be called within coroutine body” at BookmarkLineEndInlayProvider.kt:80 due to calling suspend repository method inside ReadAction lambda; plan to move suspend call outside ReadAction and keep IO dispatcher.
- 2026-01-23 17:40: User reported toast shows “Bookmark created” but tree does not update even after refresh; suspect ServiceLocator creates separate BookmarkStore per action vs toolwindow, causing state divergence; plan to share BookmarkStore per project (singleton cache/service) so actions and toolwindow see same data.
- 2026-01-23 17:42: Added BookmarkStoreProvider singleton and wired ServiceLocator to use it so all consumers share the same store; expect bookmark actions to update toolwindow tree after refresh.
- 2026-01-23 17:44: Made line-end inlays auto-enabled and hidden from settings UI (isVisibleInSettings=false, isEnabledByDefault=true) so users don’t need to toggle manually.
- 2026-01-23 17:45: Build error “isEnabledByDefault overrides nothing” (API doesn’t expose that property); will remove override and rely on default enablement while keeping settings UI hidden.
- 2026-01-23 17:49: For auto-refresh and proper insertion after add, introduced SelectionBus tracking of last selected node (by ID) to target insertion and selection after creation; pending UI wiring.
- 2026-01-23 18:20: Wired BookmarkPanel to record last selection, compute insertionTarget (child of group/process or after selected leaf), pass insertIndex to Create* intents, and auto-select created node; unresolved reference fixed.
- 2026-01-23 19:31: Added SelectNode side effect handling in panel to scroll/highlight, and ViewModel now emits SelectNode on NodeAdded and navigate actions; reloadBookmarks factored to suspend and called after creates. Tree should auto-refresh and highlight newly created or navigated nodes.
- 2026-01-23 21:28: Inlay hints: removed [B]/[P] prefixes to drop icons, added Markdown/TEXT provider registrations, normalized file paths for matching, forced repaint after adding hints, and emit RefreshInlays side effect on create/node-added so new marks show without reopening editor.
- 2026-01-23 21:54: User reported compile error in BookmarkPanel: `HintUtils` is internal; plan to switch to public InlayHints API (e.g., `InlayHintsPassFactory.forceHintsUpdateOnNextPass`) to refresh hints.
- 2026-01-23 22:00: InlayHintsPassFactory not available in target platform; plan shifted to refresh inlay hints via DaemonCodeAnalyzer restart (per file when possible).
- 2026-01-23 22:12: Build errors: DaemonCodeAnalyzer.restart requires non-null PsiFile; adjust RefreshInlays handling to null-check and fall back. Also remove redundant qualifier in actionPerformed parameter.
- 2026-01-23 22:20: Duplicate hints persisted; deduped collected hints globally by (line, type, label) in provider.


[•]
将 ReferenceGraphPanel 从 BookmarkPanel.kt 中提取为独立文件
[ ]
添加缺失的 CRUD 用例类 (CreateBookmarkUseCase, UpdateBookmarkUseCase, DeleteBookmarkUseCase, MoveBookmarkUseCase)
[ ]
补充 ViewModel 单元测试 (Intent → State → SideEffect 流程)
[ ]
补充 Repository 实现单元测试
[ ]
补充 BookmarkMapper 边界情况测试
[ ]
拆分 BookmarkPanel 为 BookmarkTreePanel 和工具栏组件
[ ]
提取 TreeDiffEngine 为独立工具类
[ ]
提取对话框构建器到独立类 (BookmarkDialogBuilder)
[ ]
评估 ViewModel 是否应将所有 Repository 调用委托给用例
[ ]
添加书签导入/导出功能
[ ]
实现批量操作功能
[ ]
添加高级搜索 (正则表达式、按文件路径)
[ ]
实现书签复制/克隆功能

## Future

* Open a file with stored bookmarks and enable “CodeRemarkTour Line End” in Settings > Editor > Inlay Hints; verify hints appear on bookmarked lines.