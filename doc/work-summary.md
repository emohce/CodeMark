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