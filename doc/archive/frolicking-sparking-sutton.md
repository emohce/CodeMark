# Bookmark-X 现代化重构方案 (Opus)

## 一、执行摘要

本方案基于对 Bookmark-X 项目的全面分析，提出一套**现代化、高效、可维护**的重构方案。核心变化包括：

- **语言升级**：Java → Kotlin（IntelliJ 官方推荐）
- **架构重构**：单体 → Clean Architecture + MVI
- **异步模型**：回调/线程 → Kotlin Coroutines + Flow
- **数据模型**：混乱继承 → Sealed Class 类型安全
- **持久化**：JAXB XML → kotlinx.serialization

---

## 二、技术栈选择

### 2.1 当前 vs 目标

| 组件 | 当前 | 目标 | 理由 |
|------|------|------|------|
| **语言** | Java | Kotlin 1.9+ | 空安全、协程、数据类、sealed class |
| **Gradle** | 7.4 | 8.5+ (Kotlin DSL) | 类型安全、更好的 IDE 支持 |
| **IntelliJ Plugin** | 1.13.3 | 1.17+ | 新 API 支持 |
| **Platform SDK** | 2021.2.2 | 2023.2+ | 新功能、Kotlin 优先 |
| **序列化** | JAXB | kotlinx.serialization | 类型安全、Kotlin 原生 |
| **异步** | Thread/volatile | Coroutines + Flow | 结构化并发、响应式 |
| **测试** | JUnit 5 + Mockito | JUnit 5 + MockK + Turbine | Kotlin 友好 |

### 2.2 新 build.gradle.kts

```kotlin
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.21"
    id("org.jetbrains.intellij") version "1.17.0"
}

group = "indi.bookmarkx"
version = "3.0.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
}

intellij {
    version.set("2023.2")
    plugins.set(listOf("com.intellij.java", "org.intellij.plugins.markdown"))
}

patchPluginXml {
    sinceBuild.set("232.0")
    untilBuild.set("252.*")
}
```

---

## 三、整体架构设计

### 3.1 Clean Architecture + MVI

```
┌─────────────────────────────────────────────────────────────────────┐
│                      PRESENTATION LAYER                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐   │
│  │ ToolWindow   │  │  Dialogs     │  │   Editor Integration     │   │
│  │ (MVI)        │  │  (MVI)       │  │   (Gutter/Inlay)         │   │
│  └──────┬───────┘  └──────┬───────┘  └────────────┬─────────────┘   │
│         │                 │                       │                 │
│         └─────────────────┴───────────────────────┘                 │
│                           │                                         │
│         ┌─────────────────▼─────────────────┐                       │
│         │      ViewModels (StateFlow)       │                       │
│         │   Intent → State → SideEffect     │                       │
│         └─────────────────┬─────────────────┘                       │
└───────────────────────────┼─────────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────────┐
│                        DOMAIN LAYER                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐   │
│  │   UseCases   │  │   Entities   │  │   Repository Interfaces  │   │
│  │(Interactors) │  │(BookmarkNode)│  │                          │   │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘   │
└───────────────────────────┼─────────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────────┐
│                         DATA LAYER                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐   │
│  │  Repository  │  │ DataSources  │  │       Mappers            │   │
│  │    Impl      │  │ (XML/Cache)  │  │   (PO ↔ Entity)          │   │
│  └──────────────┘  └──────────────┘  └──────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 模块划分

```
src/main/kotlin/indi/bookmarkx/
├── domain/                              # 领域层（纯 Kotlin，无平台依赖）
│   ├── model/                           # 领域实体
│   │   ├── BookmarkNode.kt              # sealed class 节点定义
│   │   ├── Reference.kt                 # 引用关系
│   │   └── ProcessProgress.kt           # 流程进度
│   ├── repository/                      # 仓库接口
│   │   ├── BookmarkRepository.kt
│   │   └── ReferenceRepository.kt
│   ├── usecase/                         # 用例
│   │   ├── bookmark/
│   │   │   ├── CreateBookmarkUseCase.kt
│   │   │   ├── EditBookmarkUseCase.kt
│   │   │   ├── DeleteBookmarkUseCase.kt
│   │   │   └── SearchBookmarkUseCase.kt
│   │   ├── navigation/
│   │   │   ├── NavigateToBookmarkUseCase.kt
│   │   │   └── ProcessNavigationUseCase.kt
│   │   └── reference/
│   │       ├── CreateReferenceUseCase.kt
│   │       ├── SyncReferencesUseCase.kt
│   │       └── DetectCircularRefUseCase.kt
│   └── event/                           # 领域事件
│       └── BookmarkEvent.kt
│
├── data/                                # 数据层
│   ├── repository/                      # 仓库实现
│   │   ├── BookmarkRepositoryImpl.kt
│   │   └── ReferenceRepositoryImpl.kt
│   ├── datasource/                      # 数据源
│   │   ├── BookmarkPersistentDataSource.kt
│   │   └── BookmarkCacheDataSource.kt
│   ├── persistence/                     # 持久化
│   │   ├── BookmarkPersistentState.kt   # @Serializable 状态
│   │   ├── NodeData.kt                  # 持久化数据模型
│   │   └── BookmarkPersistentComponent.kt
│   ├── mapper/                          # 映射器
│   │   └── BookmarkMapper.kt
│   └── migration/                       # 数据迁移
│       └── DataMigrationService.kt
│
├── presentation/                        # 表现层
│   ├── toolwindow/                      # 工具窗口
│   │   ├── BookmarkToolWindowFactory.kt
│   │   ├── BookmarkViewModel.kt
│   │   ├── BookmarkViewState.kt
│   │   ├── BookmarkIntent.kt
│   │   ├── BookmarkSideEffect.kt
│   │   └── panel/
│   │       ├── BookmarkTreePanel.kt
│   │       └── BookmarkTreeCellRenderer.kt
│   ├── dialog/                          # 对话框
│   │   ├── BookmarkCreatorDialog.kt
│   │   ├── ReferenceSelectDialog.kt
│   │   └── ProcessDescriptionDialog.kt
│   ├── editor/                          # 编辑器集成
│   │   ├── gutter/
│   │   │   └── BookmarkGutterRenderer.kt
│   │   ├── inlay/
│   │   │   ├── BookmarkInlayProvider.kt
│   │   │   └── BookmarkInlayRenderer.kt
│   │   └── painter/
│   │       └── LineEndPainter.kt
│   ├── menu/                            # 菜单
│   │   └── BookmarkContextMenuProvider.kt
│   └── action/                          # Action
│       ├── CreateBookmarkAction.kt
│       ├── NavigationActions.kt
│       └── ProcessActions.kt
│
├── core/                                # 核心工具
│   ├── di/                              # 依赖注入
│   │   └── ServiceLocator.kt
│   ├── coroutine/                       # 协程工具
│   │   └── CoroutineDispatchers.kt
│   ├── cache/                           # 缓存
│   │   └── FileBookmarkCache.kt
│   ├── event/                           # 事件总线
│   │   └── BookmarkEventBus.kt
│   └── util/                            # 工具类
│       ├── FileUtil.kt
│       └── MarkdownUtil.kt
│
└── plugin/                              # 插件入口
    ├── BookmarkXStartupActivity.kt
    └── BookmarkXDisposable.kt
```

---

## 四、核心数据模型

### 4.1 Sealed Class 节点定义

```kotlin
// domain/model/BookmarkNode.kt

/**
 * 书签树节点 - 使用 sealed class 实现类型安全
 */
sealed class BookmarkNode {
    abstract val uuid: String
    abstract val name: String
    abstract val description: String
    abstract val createdAt: Instant
    abstract val modifiedAt: Instant

    /**
     * 普通书签 - 关联代码位置
     */
    data class Bookmark(
        override val uuid: String = UUID.randomUUID().toString(),
        override val name: String,
        override val description: String = "",
        override val createdAt: Instant = Instant.now(),
        override val modifiedAt: Instant = Instant.now(),
        val filePath: String,
        val line: Int,
        val column: Int = 0,
        val iconPath: String? = null,
        val referenceId: String? = null  // 引用源 ID（null 表示非引用）
    ) : BookmarkNode() {
        val isReference: Boolean get() = referenceId != null
    }

    /**
     * 描述性书签 - 无文件位置，纯文本/Markdown
     */
    data class DescriptiveBookmark(
        override val uuid: String = UUID.randomUUID().toString(),
        override val name: String,
        override val description: String = "",
        override val createdAt: Instant = Instant.now(),
        override val modifiedAt: Instant = Instant.now(),
        val markdownContent: String = ""  // Markdown 富文本
    ) : BookmarkNode()

    /**
     * 分组节点 - 纯容器
     */
    data class Group(
        override val uuid: String = UUID.randomUUID().toString(),
        override val name: String,
        override val description: String = "",
        override val createdAt: Instant = Instant.now(),
        override val modifiedAt: Instant = Instant.now(),
        val children: List<BookmarkNode> = emptyList()
    ) : BookmarkNode()

    /**
     * 流程节点 - 有序步骤容器，支持导航
     */
    data class Process(
        override val uuid: String = UUID.randomUUID().toString(),
        override val name: String,
        override val description: String = "",
        override val createdAt: Instant = Instant.now(),
        override val modifiedAt: Instant = Instant.now(),
        val entryFilePath: String? = null,  // 流程入口位置（可选）
        val entryLine: Int? = null,
        val markdownContent: String = "",   // 流程说明文档
        val steps: List<BookmarkNode> = emptyList()  // 有序步骤
    ) : BookmarkNode() {

        /**
         * 获取所有可导航的书签（扁平化）
         */
        fun flattenNavigableBookmarks(): List<Bookmark> {
            return steps.flatMap { node ->
                when (node) {
                    is Bookmark -> listOf(node)
                    is Process -> node.flattenNavigableBookmarks()
                    else -> emptyList()
                }
            }
        }
    }
}

/**
 * 引用关系
 */
data class Reference(
    val sourceId: String,    // 源节点 UUID
    val targetId: String,    // 引用节点 UUID
    val createdAt: Instant = Instant.now()
)

/**
 * 流程导航进度
 */
data class ProcessProgress(
    val processName: String,
    val currentStep: Int,
    val totalSteps: Int,
    val currentBookmark: BookmarkNode.Bookmark
)
```

### 4.2 持久化数据模型

```kotlin
// data/persistence/NodeData.kt

@Serializable
sealed class NodeData {
    abstract val uuid: String
    abstract val name: String
    abstract val description: String
    abstract val createdAt: Long  // Epoch millis
    abstract val modifiedAt: Long

    @Serializable
    @SerialName("bookmark")
    data class BookmarkData(
        override val uuid: String,
        override val name: String,
        override val description: String = "",
        override val createdAt: Long = System.currentTimeMillis(),
        override val modifiedAt: Long = System.currentTimeMillis(),
        val filePath: String,
        val line: Int,
        val column: Int = 0,
        val iconPath: String? = null,
        val referenceId: String? = null
    ) : NodeData()

    @Serializable
    @SerialName("descriptive")
    data class DescriptiveData(
        override val uuid: String,
        override val name: String,
        override val description: String = "",
        override val createdAt: Long = System.currentTimeMillis(),
        override val modifiedAt: Long = System.currentTimeMillis(),
        val markdownContent: String = ""
    ) : NodeData()

    @Serializable
    @SerialName("group")
    data class GroupData(
        override val uuid: String,
        override val name: String,
        override val description: String = "",
        override val createdAt: Long = System.currentTimeMillis(),
        override val modifiedAt: Long = System.currentTimeMillis(),
        val children: List<NodeData> = emptyList()
    ) : NodeData()

    @Serializable
    @SerialName("process")
    data class ProcessData(
        override val uuid: String,
        override val name: String,
        override val description: String = "",
        override val createdAt: Long = System.currentTimeMillis(),
        override val modifiedAt: Long = System.currentTimeMillis(),
        val entryFilePath: String? = null,
        val entryLine: Int? = null,
        val markdownContent: String = "",
        val steps: List<NodeData> = emptyList()
    ) : NodeData()
}

@Serializable
data class BookmarkPersistentState(
    val version: Int = CURRENT_VERSION,
    val root: NodeData.GroupData
) {
    companion object {
        const val CURRENT_VERSION = 3
    }
}
```

---

## 五、核心实现

### 5.1 Repository 接口

```kotlin
// domain/repository/BookmarkRepository.kt

interface BookmarkRepository {
    // ===== 查询 =====
    suspend fun getRootNode(): BookmarkNode.Group
    suspend fun findByUuid(uuid: String): BookmarkNode?
    suspend fun findByFilePath(filePath: String): List<BookmarkNode.Bookmark>
    suspend fun findParent(nodeId: String): BookmarkNode?
    suspend fun search(query: String, limit: Int = 50): List<BookmarkNode>

    // ===== 修改 =====
    suspend fun create(node: BookmarkNode, parentId: String?, index: Int? = null)
    suspend fun update(node: BookmarkNode)
    suspend fun delete(nodeId: String)
    suspend fun move(nodeId: String, newParentId: String, newIndex: Int)
    suspend fun reorder(parentId: String, orderedChildIds: List<String>)

    // ===== 观察 =====
    fun observeChanges(): Flow<BookmarkEvent>
    fun observeNode(nodeId: String): Flow<BookmarkNode?>
}

interface ReferenceRepository {
    suspend fun createReference(sourceId: String, parentId: String, index: Int? = null): BookmarkNode.Bookmark
    suspend fun getReferences(sourceId: String): List<BookmarkNode.Bookmark>
    suspend fun getReferenceCount(sourceId: String): Int
    suspend fun deleteReference(referenceId: String)
    suspend fun deleteAllReferences(sourceId: String)
    suspend fun syncFromSource(sourceId: String): Int  // 返回同步数量
    suspend fun hasCircularReference(sourceId: String, targetParentId: String): Boolean
}
```

### 5.2 ViewModel (MVI)

```kotlin
// presentation/toolwindow/BookmarkViewModel.kt

/**
 * 视图状态
 */
data class BookmarkViewState(
    val rootNode: BookmarkNode.Group? = null,
    val selectedNodeId: String? = null,
    val expandedNodeIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val searchResults: List<BookmarkNode> = emptyList(),
    val processProgress: ProcessProgress? = null
)

/**
 * 用户意图
 */
sealed class BookmarkIntent {
    data class SelectNode(val nodeId: String) : BookmarkIntent()
    data class ExpandNode(val nodeId: String) : BookmarkIntent()
    data class CollapseNode(val nodeId: String) : BookmarkIntent()

    // 书签操作
    data class CreateBookmark(val parentId: String?, val bookmark: BookmarkNode.Bookmark) : BookmarkIntent()
    data class CreateDescriptive(val parentId: String?, val bookmark: BookmarkNode.DescriptiveBookmark) : BookmarkIntent()
    data class CreateGroup(val parentId: String?, val group: BookmarkNode.Group) : BookmarkIntent()
    data class CreateProcess(val parentId: String?, val process: BookmarkNode.Process) : BookmarkIntent()
    data class EditNode(val node: BookmarkNode) : BookmarkIntent()
    data class DeleteNode(val nodeId: String) : BookmarkIntent()
    data class MoveNode(val nodeId: String, val newParentId: String, val newIndex: Int) : BookmarkIntent()

    // 引用操作
    data class CreateReference(val sourceId: String, val targetParentId: String) : BookmarkIntent()
    data class SyncReferences(val sourceId: String) : BookmarkIntent()

    // 导航
    data class NavigateToBookmark(val bookmark: BookmarkNode.Bookmark) : BookmarkIntent()
    data object NavigateToNextInProcess : BookmarkIntent()
    data object NavigateToPrevInProcess : BookmarkIntent()

    // 搜索
    data class Search(val query: String) : BookmarkIntent()
    data object ClearSearch : BookmarkIntent()

    data object Refresh : BookmarkIntent()
}

/**
 * 副作用（一次性事件）
 */
sealed class BookmarkSideEffect {
    data class NavigateToFile(val filePath: String, val line: Int, val column: Int = 0) : BookmarkSideEffect()
    data class ShowNotification(val message: String, val type: NotificationType) : BookmarkSideEffect()
    data class ShowDialog(val dialogType: DialogType) : BookmarkSideEffect()
    data class ShowMarkdown(val title: String, val content: String) : BookmarkSideEffect()
    data object ScrollToSelected : BookmarkSideEffect()
}

sealed class DialogType {
    data class ConfirmDelete(val nodeId: String, val nodeName: String, val hasReferences: Boolean) : DialogType()
    data class SelectInsertPosition(val nodeId: String) : DialogType()
    data class ShowDescription(val bookmark: BookmarkNode.DescriptiveBookmark) : DialogType()
}

/**
 * ViewModel 实现
 */
class BookmarkViewModel(
    private val project: Project,
    private val bookmarkRepository: BookmarkRepository,
    private val referenceRepository: ReferenceRepository,
    private val processNavigationUseCase: ProcessNavigationUseCase,
    private val dispatchers: CoroutineDispatchers
) {
    private val scope = CoroutineScope(dispatchers.main + SupervisorJob())

    private val _state = MutableStateFlow(BookmarkViewState())
    val state: StateFlow<BookmarkViewState> = _state.asStateFlow()

    private val _sideEffects = MutableSharedFlow<BookmarkSideEffect>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sideEffects: SharedFlow<BookmarkSideEffect> = _sideEffects.asSharedFlow()

    init {
        loadBookmarks()
        observeChanges()
    }

    fun processIntent(intent: BookmarkIntent) {
        scope.launch {
            when (intent) {
                is BookmarkIntent.SelectNode -> handleSelectNode(intent.nodeId)
                is BookmarkIntent.CreateBookmark -> handleCreateBookmark(intent.parentId, intent.bookmark)
                is BookmarkIntent.DeleteNode -> handleDeleteNode(intent.nodeId)
                is BookmarkIntent.NavigateToBookmark -> handleNavigateToBookmark(intent.bookmark)
                is BookmarkIntent.NavigateToNextInProcess -> handleNavigateNext()
                is BookmarkIntent.Search -> handleSearch(intent.query)
                // ... 其他 intent 处理
                else -> {}
            }
        }
    }

    private fun loadBookmarks() {
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val root = withContext(dispatchers.io) {
                    bookmarkRepository.getRootNode()
                }
                _state.update { it.copy(rootNode = root, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    private fun observeChanges() {
        scope.launch {
            bookmarkRepository.observeChanges().collect { event ->
                // 增量更新而非全量重载
                when (event) {
                    is BookmarkEvent.NodeAdded -> handleNodeAdded(event)
                    is BookmarkEvent.NodeUpdated -> handleNodeUpdated(event)
                    is BookmarkEvent.NodeRemoved -> handleNodeRemoved(event)
                }
            }
        }
    }

    private suspend fun handleNavigateToBookmark(bookmark: BookmarkNode.Bookmark) {
        _state.update { it.copy(selectedNodeId = bookmark.uuid) }
        _sideEffects.emit(BookmarkSideEffect.NavigateToFile(bookmark.filePath, bookmark.line, bookmark.column))
        _sideEffects.emit(BookmarkSideEffect.ScrollToSelected)

        // 更新流程进度
        val progress = processNavigationUseCase.getProgress(bookmark)
        _state.update { it.copy(processProgress = progress) }
    }

    private suspend fun handleNavigateNext() {
        val currentId = _state.value.selectedNodeId ?: return
        val current = bookmarkRepository.findByUuid(currentId) as? BookmarkNode.Bookmark ?: return

        val next = processNavigationUseCase.findNext(current)
        if (next != null) {
            handleNavigateToBookmark(next)
        } else {
            _sideEffects.emit(BookmarkSideEffect.ShowNotification("已到达流程末尾", NotificationType.INFORMATION))
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
```

### 5.3 流程导航 UseCase

```kotlin
// domain/usecase/navigation/ProcessNavigationUseCase.kt

class ProcessNavigationUseCase(
    private val bookmarkRepository: BookmarkRepository
) {
    /**
     * 查找流程中的下一个可导航节点
     */
    suspend fun findNext(current: BookmarkNode.Bookmark): BookmarkNode.Bookmark? {
        val process = findParentProcess(current.uuid) ?: return null
        val steps = process.flattenNavigableBookmarks()
        val currentIndex = steps.indexOfFirst { it.uuid == current.uuid }

        return steps.getOrNull(currentIndex + 1)
    }

    /**
     * 查找流程中的上一个可导航节点
     */
    suspend fun findPrevious(current: BookmarkNode.Bookmark): BookmarkNode.Bookmark? {
        val process = findParentProcess(current.uuid) ?: return null
        val steps = process.flattenNavigableBookmarks()
        val currentIndex = steps.indexOfFirst { it.uuid == current.uuid }

        return steps.getOrNull(currentIndex - 1)
    }

    /**
     * 获取当前流程进度
     */
    suspend fun getProgress(current: BookmarkNode.Bookmark): ProcessProgress? {
        val process = findParentProcess(current.uuid) ?: return null
        val steps = process.flattenNavigableBookmarks()
        val currentIndex = steps.indexOfFirst { it.uuid == current.uuid }

        return if (currentIndex >= 0) {
            ProcessProgress(
                processName = process.name,
                currentStep = currentIndex + 1,
                totalSteps = steps.size,
                currentBookmark = current
            )
        } else {
            null
        }
    }

    /**
     * 获取流程的第一个节点
     */
    suspend fun findFirst(process: BookmarkNode.Process): BookmarkNode.Bookmark? {
        return process.flattenNavigableBookmarks().firstOrNull()
    }

    /**
     * 获取流程的最后一个节点
     */
    suspend fun findLast(process: BookmarkNode.Process): BookmarkNode.Bookmark? {
        return process.flattenNavigableBookmarks().lastOrNull()
    }

    private suspend fun findParentProcess(nodeId: String): BookmarkNode.Process? {
        val root = bookmarkRepository.getRootNode()
        return findProcessContaining(root, nodeId)
    }

    private fun findProcessContaining(node: BookmarkNode, targetId: String): BookmarkNode.Process? {
        return when (node) {
            is BookmarkNode.Bookmark -> null
            is BookmarkNode.DescriptiveBookmark -> null
            is BookmarkNode.Group -> {
                node.children.firstNotNullOfOrNull { findProcessContaining(it, targetId) }
            }
            is BookmarkNode.Process -> {
                if (containsNode(node, targetId)) {
                    node
                } else {
                    node.steps.firstNotNullOfOrNull { findProcessContaining(it, targetId) }
                }
            }
        }
    }

    private fun containsNode(process: BookmarkNode.Process, targetId: String): Boolean {
        return process.steps.any { step ->
            when (step) {
                is BookmarkNode.Bookmark -> step.uuid == targetId
                is BookmarkNode.DescriptiveBookmark -> step.uuid == targetId
                is BookmarkNode.Process -> containsNode(step, targetId)
                else -> false
            }
        }
    }
}
```

### 5.4 引用同步 UseCase

```kotlin
// domain/usecase/reference/SyncReferencesUseCase.kt

class SyncReferencesUseCase(
    private val bookmarkRepository: BookmarkRepository,
    private val referenceRepository: ReferenceRepository
) {
    /**
     * 从源节点同步到所有引用
     * @return 同步的引用数量
     */
    suspend fun execute(sourceId: String): SyncResult {
        val source = bookmarkRepository.findByUuid(sourceId) as? BookmarkNode.Bookmark
            ?: return SyncResult.SourceNotFound

        val references = referenceRepository.getReferences(sourceId)
        if (references.isEmpty()) {
            return SyncResult.NoReferences
        }

        var syncedCount = 0
        val errors = mutableListOf<String>()

        references.forEach { ref ->
            try {
                val updated = ref.copy(
                    name = source.name,
                    description = source.description,
                    filePath = source.filePath,
                    line = source.line,
                    column = source.column,
                    iconPath = source.iconPath,
                    modifiedAt = Instant.now()
                )
                bookmarkRepository.update(updated)
                syncedCount++
            } catch (e: Exception) {
                errors.add("${ref.uuid}: ${e.message}")
            }
        }

        return SyncResult.Success(syncedCount, errors)
    }

    sealed class SyncResult {
        data object SourceNotFound : SyncResult()
        data object NoReferences : SyncResult()
        data class Success(val count: Int, val errors: List<String>) : SyncResult()
    }
}

// 循环引用检测
class DetectCircularRefUseCase(
    private val bookmarkRepository: BookmarkRepository,
    private val referenceRepository: ReferenceRepository
) {
    /**
     * 检测创建引用是否会导致循环
     */
    suspend fun execute(sourceId: String, targetParentId: String): Boolean {
        // 使用 DFS 检测循环
        val visited = mutableSetOf<String>()
        return hasCircle(sourceId, targetParentId, visited)
    }

    private suspend fun hasCircle(
        sourceId: String,
        currentId: String,
        visited: MutableSet<String>
    ): Boolean {
        if (currentId in visited) return true
        if (currentId == sourceId) return true

        visited.add(currentId)

        val node = bookmarkRepository.findByUuid(currentId)
        if (node is BookmarkNode.Bookmark && node.isReference) {
            return hasCircle(sourceId, node.referenceId!!, visited)
        }

        return false
    }
}
```

### 5.5 事件系统

```kotlin
// domain/event/BookmarkEvent.kt

sealed class BookmarkEvent {
    abstract val timestamp: Instant

    data class NodeAdded(
        val node: BookmarkNode,
        val parentId: String?,
        val index: Int,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()

    data class NodeUpdated(
        val node: BookmarkNode,
        val previousNode: BookmarkNode?,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()

    data class NodeRemoved(
        val nodeId: String,
        val parentId: String?,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()

    data class NodeMoved(
        val nodeId: String,
        val oldParentId: String,
        val newParentId: String,
        val newIndex: Int,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()

    data class ReferenceSynced(
        val sourceId: String,
        val syncedCount: Int,
        override val timestamp: Instant = Instant.now()
    ) : BookmarkEvent()
}

// core/event/BookmarkEventBus.kt

@Service(Service.Level.PROJECT)
class BookmarkEventBus(private val project: Project) : Disposable {

    private val _events = MutableSharedFlow<BookmarkEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<BookmarkEvent> = _events.asSharedFlow()

    suspend fun emit(event: BookmarkEvent) {
        _events.emit(event)
    }

    override fun dispose() {
        // Cleanup if needed
    }

    companion object {
        fun getInstance(project: Project): BookmarkEventBus = project.service()
    }
}
```

---

## 六、数据迁移方案

### 6.1 版本兼容策略

```kotlin
// data/migration/DataMigrationService.kt

class DataMigrationService(private val project: Project) {

    private val logger = Logger.getInstance(DataMigrationService::class.java)

    /**
     * 检测并执行必要的数据迁移
     */
    suspend fun migrateIfNeeded(legacyState: BookmarkPO?): BookmarkPersistentState {
        if (legacyState == null) {
            return createEmptyState()
        }

        // 检测旧版本
        val version = legacyState.version
        logger.info("Detected data version: $version, current: ${BookmarkPersistentState.CURRENT_VERSION}")

        return when {
            version < 2 -> migrateV1ToV3(legacyState)
            version == 2 -> migrateV2ToV3(legacyState)
            else -> convertToNewFormat(legacyState)
        }
    }

    /**
     * V1 → V3 迁移（跳过 V2）
     * V1 特征：使用 bookmark boolean 而非 nodeType
     */
    private fun migrateV1ToV3(legacy: BookmarkPO): BookmarkPersistentState {
        logger.info("Migrating from V1 to V3")
        backupLegacyData(legacy)

        val root = convertLegacyNodeToV3(legacy)
        return BookmarkPersistentState(
            version = BookmarkPersistentState.CURRENT_VERSION,
            root = root as NodeData.GroupData
        )
    }

    private fun convertLegacyNodeToV3(legacy: BookmarkPO): NodeData {
        return when {
            // 流程节点
            legacy.nodeType == BookmarkPO.NodeType.FLOW -> {
                NodeData.ProcessData(
                    uuid = legacy.uuid ?: UUID.randomUUID().toString(),
                    name = legacy.name ?: "",
                    description = legacy.desc ?: "",
                    entryFilePath = legacy.virtualFilePath,
                    entryLine = legacy.line.takeIf { it >= 0 },
                    markdownContent = legacy.description ?: "",
                    steps = legacy.children?.map { convertLegacyNodeToV3(it) } ?: emptyList()
                )
            }
            // 描述性书签
            legacy.isDescriptive == true -> {
                NodeData.DescriptiveData(
                    uuid = legacy.uuid ?: UUID.randomUUID().toString(),
                    name = legacy.name ?: "",
                    description = legacy.desc ?: "",
                    markdownContent = legacy.description ?: ""
                )
            }
            // 普通书签
            legacy.isBookmark -> {
                NodeData.BookmarkData(
                    uuid = legacy.uuid ?: UUID.randomUUID().toString(),
                    name = legacy.name ?: "",
                    description = legacy.desc ?: "",
                    filePath = legacy.virtualFilePath ?: "",
                    line = legacy.line,
                    referenceId = legacy.refId
                )
            }
            // 分组
            else -> {
                NodeData.GroupData(
                    uuid = legacy.uuid ?: UUID.randomUUID().toString(),
                    name = legacy.name ?: "",
                    description = legacy.desc ?: "",
                    children = legacy.children?.map { convertLegacyNodeToV3(it) } ?: emptyList()
                )
            }
        }
    }

    private fun backupLegacyData(legacy: BookmarkPO) {
        try {
            val backupPath = project.basePath?.let {
                Path.of(it, ".idea", "SuperBookmarkState.backup.xml")
            } ?: return

            // 序列化旧数据到备份文件
            val json = Json { prettyPrint = true }
            // ... 备份逻辑

            logger.info("Legacy data backed up to: $backupPath")
        } catch (e: Exception) {
            logger.warn("Failed to backup legacy data", e)
        }
    }

    private fun createEmptyState(): BookmarkPersistentState {
        return BookmarkPersistentState(
            version = BookmarkPersistentState.CURRENT_VERSION,
            root = NodeData.GroupData(
                uuid = "root",
                name = "Bookmarks",
                description = ""
            )
        )
    }
}
```

---

## 七、关键文件清单

### 需要创建的新文件

| 路径 | 用途 |
|------|------|
| `domain/model/BookmarkNode.kt` | Sealed class 节点定义 |
| `domain/repository/BookmarkRepository.kt` | 仓库接口 |
| `domain/usecase/navigation/ProcessNavigationUseCase.kt` | 流程导航 |
| `data/persistence/BookmarkPersistentState.kt` | 持久化状态 |
| `data/mapper/BookmarkMapper.kt` | 模型映射 |
| `presentation/toolwindow/BookmarkViewModel.kt` | MVI ViewModel |
| `core/event/BookmarkEventBus.kt` | 事件总线 |

### 需要重构的现有文件

| 文件 | 变更 |
|------|------|
| `build.gradle` | 迁移到 Kotlin DSL，添加新依赖 |
| `BookmarksManager.java` | 简化为 Facade，内部调用新架构 |
| `BookmarkPO.java` | 保留用于迁移，最终删除 |
| `MyPersistent.java` | 适配新持久化格式 |
| `ProcessNavigationService.java` | 迁移到 Kotlin UseCase |

---

## 八、实施阶段

### Phase 1：基础设施（1-2 周）
1. 添加 Kotlin 支持到项目
2. 迁移 build.gradle → build.gradle.kts
3. 创建新包结构
4. 实现 BookmarkNode sealed class
5. 实现 NodeData 持久化模型

### Phase 2：数据层（1-2 周）
1. 实现 Repository 接口和实现
2. 实现 BookmarkMapper
3. 实现 DataMigrationService
4. 添加 BookmarkEventBus

### Phase 3：领域层（1-2 周）
1. 实现核心 UseCases
2. 实现流程导航
3. 实现引用同步
4. 实现循环检测

### Phase 4：表现层（2-3 周）
1. 实现 BookmarkViewModel (MVI)
2. 迁移 BookmarkTreePanel
3. 迁移对话框
4. 更新 Gutter/Inlay 渲染

### Phase 5：集成与清理（1 周）
1. 端到端测试
2. 性能优化
3. 删除旧代码
4. 文档更新

---

## 九、验证方案

### 功能验证
- [ ] 创建书签（编辑器内/树内）
- [ ] 编辑书签（名称、描述、位置）
- [ ] 删除书签（单个/批量/含引用）
- [ ] 创建/删除分组
- [ ] 创建/删除流程
- [ ] 流程导航（上一步/下一步）
- [ ] 引用创建和同步
- [ ] 搜索功能
- [ ] 拖拽排序
- [ ] 数据持久化
- [ ] 数据迁移（V1→V3）

### 性能验证
- [ ] 1000+ 书签加载时间 < 1s
- [ ] UI 操作响应时间 < 100ms
- [ ] 引用同步异步执行，不阻塞 UI

### 兼容性验证
- [ ] IntelliJ IDEA 2023.2+
- [ ] 旧版数据自动迁移
- [ ] 降级时数据可读（XML 格式保留）

---

## 十、风险与缓解

| 风险 | 级别 | 缓解措施 |
|------|------|----------|
| Kotlin 学习曲线 | 中 | 渐进式迁移，保留 Java 互操作 |
| API 兼容性变化 | 高 | 版本检测，条件分支 |
| 数据迁移失败 | 高 | 自动备份，回滚机制 |
| 性能回退 | 中 | 持续性能测试，缓存优化 |
| Coroutines 误用 | 中 | 代码审查，使用结构化并发 |
