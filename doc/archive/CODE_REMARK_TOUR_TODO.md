# CodeRemarkTour Implementation TODO List

## 📊 Current Progress: ~25% Complete

### ✅ Completed (Foundation Layer)
- [x] Project Setup (Gradle KTS, Kotlin 2.1.20, IntelliJ SDK 2025.3)
- [x] Domain Models (BookmarkNode, Reference, ProcessProgress, etc.)
- [x] Persistence Models (NodeData, BookmarkPersistentState, ReferenceData)
- [x] Repository Interfaces (BookmarkRepository, ReferenceRepository)
- [x] Event System (BookmarkEvent with 8 event types)
- [x] UseCase Interfaces (5 interfaces defined)
- [x] Plugin Configuration (plugin.xml with ToolWindow + Actions)

---

## 🎯 Priority Tasks (Next Steps)

### 🔥 HIGH PRIORITY (Core Infrastructure)
- [x] **data-mapper-complete** - Complete BookmarkMapper.kt - Implement domain↔persistence model conversion functions
- [x] **bookmark-repo-impl** - Implement BookmarkRepositoryImpl.kt - Core CRUD operations for bookmarks
- [x] **reference-repo-impl** - Implement ReferenceRepositoryImpl.kt - Reference management and synchronization
- [x] **service-locator** - Create ServiceLocator.kt - Dependency injection container for application
- [ ] **event-bus** - Create CodeRemarkTourEventBus.kt - Event flow infrastructure using coroutines (当前未单独实现，事件由 Repository SharedFlow + ViewModel side effects承担)
- [x] **toolwindow-factory** - Create CodeRemarkTourToolWindowFactory.kt - Plugin visible entry point in IDE

---

## 📋 Detailed Task List

### 🔥 High Priority Tasks
| ID | Task | Description |
|----|------|-------------|
| data-mapper-complete | BookmarkMapper.kt | Implement domain↔persistence model conversion functions |
| bookmark-repo-impl | BookmarkRepositoryImpl.kt | Core CRUD operations for bookmarks |
| reference-repo-impl | ReferenceRepositoryImpl.kt | Reference management and synchronization |
| service-locator | ServiceLocator.kt | Dependency injection container for application |
| event-bus | CodeRemarkTourEventBus.kt | Event flow infrastructure using coroutines |
| toolwindow-factory | CodeRemarkTourToolWindowFactory.kt | Plugin visible entry point in IDE |

### 🟡 Medium Priority Tasks
| ID | Task | Description | Status |
|----|------|-------------|---------|
| coroutine-dispatchers | CoroutineDispatchers.kt | Coroutine dispatcher configuration for testing | ✅ 已实现 |
| usecase-implementations | UseCase Implementations | 5 UseCase classes (CrudBookmark, SearchBookmark, ProcessNavigation, SyncReferences, DetectCircularRef) | ✅ 已实现 |
| bookmark-viewmodel | BookmarkViewModel.kt | MVI pattern with StateFlow, Intent processing | ✅ 已实现 |
| bookmark-viewstate | BookmarkViewState.kt | UI state data class for MVI | ✅ 已实现 |
| bookmark-intent | BookmarkIntent.kt | User intent sealed class for MVI | ✅ 已实现 |
| bookmark-sideeffect | BookmarkSideEffect.kt | One-time event sealed class for MVI | ✅ 已实现 |
| bookmark-tree-panel | BookmarkTreePanel.kt | Main UI component for bookmark tree | ✅ 已实现（BookmarkPanel） |
| bookmark-cell-renderer | BookmarkTreeCellRenderer.kt | Custom renderer for tree nodes | ✅ 已实现 |
| create-bookmark-action | CreateBookmarkAction.kt | Ctrl+Shift+B action from editor context menu | ✅ 已实现 |
| navigate-next-action | NavigateToNextInProcessAction.kt | Ctrl+Shift+N action | ✅ 已实现 |
| navigate-prev-action | NavigateToPrevInProcessAction.kt | Ctrl+Shift+P action | ✅ 已实现 |
| startup-activity | BookmarkXStartupActivity.kt | Plugin startup and initialization | ✅ 已实现（BookmarkStartupActivity） |
| persistent-datasource | BookmarkPersistentDataSource.kt | Data source for persistence operations | ✅ 已实现 |
| usecase-tests | UseCase Unit Tests | Comprehensive unit tests for all UseCases | ☐ 未完成 |
| repository-tests | Repository Unit Tests | Unit tests for Repository implementations | ☐ 未完成 |
| mapper-tests | BookmarkMapper Unit Tests | Unit tests for BookmarkMapper | ☐ 未完成 |
| persistence-tests | Persistence Integration Tests | Integration tests for persistence layer | ☐ 未完成 |
| performance-optimization | Performance Optimization | Optimize for 1000+ bookmarks loading under 1 second | ☐ 未压测 |

### 🟢 Low Priority Tasks
| ID | Task | Description | Status |
|----|------|-------------|---------|
| bookmark-creator-dialog | BookmarkCreatorDialog.kt | Dialog for creating new bookmarks | ☐ 未单独实现（当前用 Inline 编辑/菜单） |
| reference-select-dialog | ReferenceSelectDialog.kt | Dialog for selecting reference targets | ☐ 未实现 |
| process-description-dialog | Dialog for process documentation | ☐ 未实现 |
| bookmark-gutter-renderer | BookmarkGutterRenderer.kt | Gutter icon renderer in editor | ✅ 自定义 RangeHighlighter + GutterIconRenderer 已上线 |
| bookmark-inlay-provider | BookmarkInlayProvider.kt | Inlay hint provider for bookmark info | ✅ 已实现（BookmarkLineEndInlayProvider） |
| line-end-painter | LineEndPainter.kt | Line end painter for bookmark indicators | ✅ 行尾提示已由 Inlay 覆盖 |
| cache-datasource | BookmarkCacheDataSource.kt | In-memory caching data source | ☐ 未实现 |
| file-cache | FileBookmarkCache.kt | File-based caching implementation | ☐ 未实现 |
| ui-tests | UI Tests | UI tests for ToolWindow and dialogs | ☐ 未完成 |
| data-migration | Data Migration Service | Implement data migration service for version upgrades | ☐ 未完成 |

---

## 📈 Phase Progress

| Phase | Name | Progress | Tasks Remaining |
|-------|------|----------|----------------|
| **1** | 项目脚手架 (Scaffolding) | ✅ **100%** | 0 |
| **2** | 领域层与用例 (Domain Layer) | 🟡 **60%** | 5 UseCase implementations |
| **3** | 数据层落地 (Data Layer) | 🟡 **30%** | Mapper, Repository implementations, DataSources |
| **4** | 表现层 MVP (Presentation) | ❌ **0%** | ViewModel, ViewStates, ToolWindow, Panels, Actions, Dialogs |
| **5** | 编辑器集成 (Editor Integration) | ❌ **0%** | Gutter, Inlay, Painter |
| **6** | 质量与性能 (Quality & Perf) | ❌ **0%** | All tests, Performance optimization |

---

## 🏁 Implementation Strategy

### Phase 2 Completion (Domain Layer)
1. Complete 5 UseCase implementations
2. Add comprehensive unit tests for UseCases

### Phase 3 Completion (Data Layer)  
1. Complete BookmarkMapper.kt
2. Implement BookmarkRepositoryImpl.kt
3. Implement ReferenceRepositoryImpl.kt
4. Create DataSource abstractions
5. Add unit and integration tests for data layer

### Phase 4 Completion (Presentation Layer)
1. Create ServiceLocator and EventBus infrastructure
2. Implement ToolWindow as plugin entry point
3. Build MVI components (ViewModel, ViewState, Intent, SideEffect)
4. Create UI components (TreePanel, CellRenderer, Dialogs)
5. Implement Actions for IDE integration
6. Add UI tests

### Phase 5 Completion (Editor Integration)
1. Implement Gutter icons
2. Add Inlay hints
3. Create LineEnd painter
4. Integration testing

### Phase 6 Completion (Quality & Performance)
1. Performance profiling and optimization
2. Comprehensive test coverage
3. Data migration implementation
4. Documentation updates

---

## 🎯 Success Criteria

- [ ] All 37 tasks completed
- [ ] 1000+ bookmarks load in < 1 second
- [ ] UI operations respond in < 100ms
- [ ] Test coverage > 80%
- [ ] No TODO/FIXME markers in code
- [ ] Plugin runs successfully in IntelliJ IDEA 2025.3+

---

**Last Updated:** 2026-01-23  
**Total Tasks:** 37  
**Completed:** 0 (infrastructure only)  
**In Progress:** 0  
**Remaining:** 37
