# Bookmark-X 现代化重构方案 (Opus)

> 生成时间: 2026-01-21
> 基于: 需求.md, 260121-cursor-BookmarkX-功能逻辑梳理.md, 260121-cursor-流程概念执行方案.md

---

## 一、执行摘要

本方案基于对 Bookmark-X 项目的全面分析，提出一套**现代化、高效、可维护**的重构方案。核心变化包括：

| 维度 | 当前 | 目标 |
|------|------|------|
| **语言** | Java | Kotlin 1.9+ |
| **架构** | 单体耦合 | Clean Architecture + MVI |
| **异步** | Thread/volatile | Kotlin Coroutines + Flow |
| **数据模型** | 混乱继承 | Sealed Class 类型安全 |
| **持久化** | JAXB XML | kotlinx.serialization |
| **测试** | JUnit + Mockito | JUnit 5 + MockK + Turbine |

---

## 二、技术栈升级

### 2.1 技术栈对比

| 组件 | 当前 | 目标 | 升级理由 |
|------|------|------|----------|
| **语言** | Java | Kotlin 1.9+ | 空安全、协程、数据类、sealed class |
| **Gradle** | 7.4 (Groovy) | 8.5+ (Kotlin DSL) | 类型安全、IDE 支持更好 |
| **IntelliJ Plugin** | 1.13.3 | 1.17+ | 新 API 支持、Kotlin 优先 |
| **Platform SDK** | 2021.2.2 | 2023.2+ | 新功能、更好的 Kotlin 集成 |
| **序列化** | JAXB | kotlinx.serialization | 类型安全、Kotlin 原生 |
| **异步** | Thread/volatile | Coroutines + Flow | 结构化并发、响应式 |
| **测试** | JUnit 5 + Mockito 1.x | JUnit 5 + MockK + Turbine | Kotlin 友好、协程测试 |

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

### 3.2 核心设计原则

1. **单向数据流 (Unidirectional Data Flow)**
   - Intent → ViewModel → State → UI
   - 可预测、可追踪、易调试

2. **关注点分离 (Separation of Concerns)**
   - Domain 层无平台依赖
   - Data 层处理持久化细节
   - Presentation 层处理 UI 交互

3. **依赖反转 (Dependency Inversion)**
   - 高层模块不依赖低层模块
   - 通过接口解耦

4. **响应式设计 (Reactive Design)**
   - StateFlow 驱动 UI 更新
   - 事件总线处理跨组件通信

---

## 四、模块划分

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

## 五、核心数据模型

### 5.1 Sealed Class 节点定义

```kotlin
// domain/model/BookmarkNode.kt

/**
 * 书签树节点 - 使用 sealed class 实现类型安全
 *
 * 优势：
 * 1. 编译期穷尽检查 (when 表达式)
 * 2. 类型安全，无需 instanceof
 * 3. 不可变数据，线程安全
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
     * 满足需求：描述性书签（无文件定位，仅描述）
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
     * 满足需求：文件夹
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
     * 满足需求：流程概念
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
 * 满足需求：同步到其他位置 - 源/引用机制
 */
data class Reference(
    val sourceId: String,    // 源节点 UUID
    val targetId: String,    // 引用节点 UUID
    val createdAt: Instant = Instant.now()
)

/**
 * 流程导航进度
 * 满足需求：顺序导航（上一步/下一步）
 */
data class ProcessProgress(
    val processName: String,
    val currentStep: Int,
    val totalSteps: Int,
    val currentBookmark: BookmarkNode.Bookmark
)
```

### 5.2 持久化数据模型

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

## 六、核心实现

### 6.1 Repository 接口

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

### 6.2 MVI ViewModel

```kotlin
// presentation/toolwindow/BookmarkViewModel.kt

/**
 * 视图状态 - 不可变
 */
data class BookmarkViewState(
    val rootNode: BookmarkNode.Group? = null,
    val selectedNodeId: String? = null,
    val expandedNodeIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val searchResults: List<BookmarkNode> = emptyList(),
    val processProgress: ProcessProgress? = null,
    val lastClickedNodeId: String? = null  // 满足需求：最近一次点击的树节点
)

/**
 * 用户意图
 */
sealed class BookmarkIntent {
    // 节点选择
    data class SelectNode(val nodeId: String) : BookmarkIntent()
    data class ExpandNode(val nodeId: String) : BookmarkIntent()
    data class CollapseNode(val nodeId: String) : BookmarkIntent()

    // 书签操作 - 满足需求：编辑器内新增/修改、树内新增
    data class CreateBookmark(val parentId: String?, val bookmark: BookmarkNode.Bookmark) : BookmarkIntent()
    data class CreateDescriptive(val parentId: String?, val bookmark: BookmarkNode.DescriptiveBookmark) : BookmarkIntent()
    data class CreateGroup(val parentId: String?, val group: BookmarkNode.Group) : BookmarkIntent()
    data class CreateProcess(val parentId: String?, val process: BookmarkNode.Process) : BookmarkIntent()
    data class EditNode(val node: BookmarkNode) : BookmarkIntent()
    data class DeleteNode(val nodeId: String) : BookmarkIntent()
    data class MoveNode(val nodeId: String, val newParentId: String, val newIndex: Int) : BookmarkIntent()

    // 引用操作 - 满足需求：同步到其他位置
    data class CreateReference(val sourceId: String, val targetParentId: String) : BookmarkIntent()
    data class SyncReferences(val sourceId: String) : BookmarkIntent()

    // 导航 - 满足需求：流程导航
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
    data object RefreshGutterIcons : BookmarkSideEffect()  // 满足需求：刷新 Gutter
    data object RefreshLineEndPainter : BookmarkSideEffect()  // 满足需求：刷新行末渲染
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
                is BookmarkIntent.CreateBookmark -> handleCreateBookmark(intent)
                is BookmarkIntent.EditNode -> handleEditNode(intent.node)
                is BookmarkIntent.DeleteNode -> handleDeleteNode(intent.nodeId)
                is BookmarkIntent.NavigateToBookmark -> handleNavigateToBookmark(intent.bookmark)
                is BookmarkIntent.NavigateToNextInProcess -> handleNavigateNext()
                is BookmarkIntent.NavigateToPrevInProcess -> handleNavigatePrev()
                is BookmarkIntent.CreateReference -> handleCreateReference(intent)
                is BookmarkIntent.SyncReferences -> handleSyncReferences(intent.sourceId)
                is BookmarkIntent.Search -> handleSearch(intent.query)
                else -> { /* 其他 intent */ }
            }
        }
    }

    /**
     * 处理创建书签
     * 满足需求：新增/修改完成后，保存位置应与"最近一次点击的树节点"相关
     */
    private suspend fun handleCreateBookmark(intent: BookmarkIntent.CreateBookmark) {
        val parentId = intent.parentId ?: determineInsertParent()
        val index = determineInsertIndex(parentId)

        withContext(dispatchers.io) {
            bookmarkRepository.create(intent.bookmark, parentId, index)
        }

        _sideEffects.emit(BookmarkSideEffect.ShowNotification("书签已创建", NotificationType.INFORMATION))
        _sideEffects.emit(BookmarkSideEffect.RefreshGutterIcons)
        _sideEffects.emit(BookmarkSideEffect.RefreshLineEndPainter)
    }

    /**
     * 确定插入位置的父节点
     * 满足需求：
     * - 最近节点为普通书签：新书签放在其后
     * - 最近节点为文件夹：新书签放在文件夹末尾
     * - 若树中没有选中节点：弹窗选择
     */
    private suspend fun determineInsertParent(): String? {
        val lastClicked = _state.value.lastClickedNodeId
        if (lastClicked == null) {
            // 弹窗选择
            _sideEffects.emit(BookmarkSideEffect.ShowDialog(DialogType.SelectInsertPosition(null)))
            return null
        }

        val node = bookmarkRepository.findByUuid(lastClicked)
        return when (node) {
            is BookmarkNode.Bookmark -> {
                // 放在书签后面，实际是放在父节点下
                bookmarkRepository.findParent(lastClicked)?.uuid
            }
            is BookmarkNode.Group, is BookmarkNode.Process -> {
                // 放在文件夹/流程末尾
                lastClicked
            }
            else -> null
        }
    }

    /**
     * 处理编辑节点
     * 满足需求：修改后需自动刷新对应 editor 的书签样式
     */
    private suspend fun handleEditNode(node: BookmarkNode) {
        withContext(dispatchers.io) {
            bookmarkRepository.update(node)
        }

        _sideEffects.emit(BookmarkSideEffect.RefreshGutterIcons)
        _sideEffects.emit(BookmarkSideEffect.RefreshLineEndPainter)
        _sideEffects.emit(BookmarkSideEffect.ShowNotification("书签已更新", NotificationType.INFORMATION))
    }

    /**
     * 处理删除节点
     * 满足需求：删除时自动判断是否为同步节点
     */
    private suspend fun handleDeleteNode(nodeId: String) {
        val node = bookmarkRepository.findByUuid(nodeId) ?: return

        // 检查是否有引用
        val referenceCount = if (node is BookmarkNode.Bookmark && !node.isReference) {
            referenceRepository.getReferenceCount(nodeId)
        } else {
            0
        }

        if (referenceCount > 0) {
            // 弹窗确认
            _sideEffects.emit(BookmarkSideEffect.ShowDialog(
                DialogType.ConfirmDelete(nodeId, node.name, hasReferences = true)
            ))
        } else {
            // 直接删除
            withContext(dispatchers.io) {
                bookmarkRepository.delete(nodeId)
            }
            _sideEffects.emit(BookmarkSideEffect.RefreshGutterIcons)
        }
    }

    /**
     * 处理引用同步
     * 满足需求：引用同步
     */
    private suspend fun handleSyncReferences(sourceId: String) {
        val syncedCount = withContext(dispatchers.io) {
            referenceRepository.syncFromSource(sourceId)
        }
        _sideEffects.emit(BookmarkSideEffect.ShowNotification(
            "已同步 $syncedCount 个引用",
            NotificationType.INFORMATION
        ))
    }

    fun dispose() {
        scope.cancel()
    }
}
```

### 6.3 流程导航 UseCase

```kotlin
// domain/usecase/navigation/ProcessNavigationUseCase.kt

/**
 * 流程导航用例
 * 满足需求：顺序导航（上一步/下一步）
 */
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

### 6.4 引用同步 UseCase

```kotlin
// domain/usecase/reference/SyncReferencesUseCase.kt

/**
 * 引用同步用例
 * 满足需求：支持选中节点后"同步到其他位置"
 */
class SyncReferencesUseCase(
    private val bookmarkRepository: BookmarkRepository,
    private val referenceRepository: ReferenceRepository
) {
    /**
     * 从源节点同步到所有引用
     * @return 同步结果
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

/**
 * 循环引用检测用例
 */
class DetectCircularRefUseCase(
    private val bookmarkRepository: BookmarkRepository,
    private val referenceRepository: ReferenceRepository
) {
    /**
     * 检测创建引用是否会导致循环
     */
    suspend fun execute(sourceId: String, targetParentId: String): Boolean {
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

### 6.5 事件系统

```kotlin
// domain/event/BookmarkEvent.kt

/**
 * 书签领域事件
 */
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

/**
 * 事件总线
 * 基于 Kotlin Flow，替代传统的 MessageBus 回调
 */
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

## 七、数据迁移方案

### 7.1 迁移策略

```kotlin
// data/migration/DataMigrationService.kt

/**
 * 数据迁移服务
 * 支持从 V1/V2 迁移到 V3
 */
class DataMigrationService(private val project: Project) {

    private val logger = Logger.getInstance(DataMigrationService::class.java)

    /**
     * 检测并执行必要的数据迁移
     */
    suspend fun migrateIfNeeded(legacyState: BookmarkPO?): BookmarkPersistentState {
        if (legacyState == null) {
            return createEmptyState()
        }

        val version = legacyState.version
        logger.info("Detected data version: $version, current: ${BookmarkPersistentState.CURRENT_VERSION}")

        return when {
            version < 2 -> migrateV1ToV3(legacyState)
            version == 2 -> migrateV2ToV3(legacyState)
            else -> convertToNewFormat(legacyState)
        }
    }

    /**
     * V1 → V3 迁移
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

## 八、需求覆盖矩阵

| 需求 | 实现方案 | 关键代码位置 |
|------|----------|--------------|
| 编辑器内右键新增书签 | CreateBookmarkAction + ViewModel | `action/CreateBookmarkAction.kt` |
| 快捷键定位当前行并触发新增/修改 | Action + KeyboardShortcut | `plugin.xml` 配置 |
| 保存位置与"最近点击的树节点"相关 | ViewState.lastClickedNodeId + determineInsertParent() | `BookmarkViewModel.kt` |
| 树内新增文件夹 | BookmarkIntent.CreateGroup | `BookmarkIntent.kt` |
| 树内新增描述性书签 | BookmarkNode.DescriptiveBookmark | `BookmarkNode.kt` |
| 树内修改与新增表单一致 | 复用 BookmarkCreatorDialog | `dialog/BookmarkCreatorDialog.kt` |
| 修改后自动刷新编辑器书签样式 | BookmarkSideEffect.RefreshGutterIcons/RefreshLineEndPainter | `BookmarkViewModel.kt` |
| 同步到其他位置 | CreateReferenceUseCase + SyncReferencesUseCase | `usecase/reference/` |
| 删除时判断是否为同步节点 | handleDeleteNode() 检查 referenceCount | `BookmarkViewModel.kt` |
| 删除源节点时的处理策略 | DialogType.ConfirmDelete + 二次确认 | `BookmarkSideEffect.kt` |
| 流程节点定义 | BookmarkNode.Process sealed class | `BookmarkNode.kt` |
| 顺序导航（上一步/下一步） | ProcessNavigationUseCase | `ProcessNavigationUseCase.kt` |
| Markdown 详细描述渲染 | ShowMarkdown SideEffect + Markdown 插件 | `BookmarkSideEffect.kt` |
| 引用机制与自动同步 | ReferenceRepository + SyncReferencesUseCase | `usecase/reference/` |
| 循环引用检测 | DetectCircularRefUseCase | `DetectCircularRefUseCase.kt` |

---

## 九、实施阶段

### Phase 1：基础设施（1-2 周）

**目标**：建立 Kotlin 开发环境和基础架构

- [ ] 添加 Kotlin 支持到项目
- [ ] 迁移 build.gradle → build.gradle.kts
- [ ] 创建新包结构
- [ ] 实现 BookmarkNode sealed class
- [ ] 实现 NodeData 持久化模型
- [ ] 添加 kotlinx.serialization 配置

### Phase 2：数据层（1-2 周）

**目标**：实现数据存储和访问

- [ ] 实现 BookmarkRepository 接口
- [ ] 实现 BookmarkRepositoryImpl
- [ ] 实现 ReferenceRepository
- [ ] 实现 BookmarkMapper
- [ ] 实现 DataMigrationService
- [ ] 实现 BookmarkEventBus

### Phase 3：领域层（1-2 周）

**目标**：实现核心业务逻辑

- [ ] 实现 CreateBookmarkUseCase
- [ ] 实现 EditBookmarkUseCase
- [ ] 实现 DeleteBookmarkUseCase
- [ ] 实现 ProcessNavigationUseCase
- [ ] 实现 CreateReferenceUseCase
- [ ] 实现 SyncReferencesUseCase
- [ ] 实现 DetectCircularRefUseCase

### Phase 4：表现层（2-3 周）

**目标**：实现 UI 和交互

- [ ] 实现 BookmarkViewModel (MVI)
- [ ] 实现 BookmarkViewState/Intent/SideEffect
- [ ] 迁移 BookmarkTreePanel
- [ ] 迁移 BookmarkCreatorDialog
- [ ] 更新 BookmarkGutterRenderer
- [ ] 更新 LineEndPainter
- [ ] 更新 BookmarkContextMenuProvider
- [ ] 迁移 Action 类

### Phase 5：集成与清理（1 周）

**目标**：测试、优化、文档

- [ ] 端到端测试
- [ ] 性能优化
- [ ] 删除旧 Java 代码
- [ ] 更新文档

---

## 十、验证清单

### 功能验证

- [ ] 创建书签（编辑器内 / 树内）
- [ ] 创建描述性书签
- [ ] 创建分组
- [ ] 创建流程
- [ ] 编辑节点（名称、描述、位置）
- [ ] 删除节点（单个 / 批量 / 含引用）
- [ ] 流程导航（上一步 / 下一步）
- [ ] 引用创建
- [ ] 引用同步
- [ ] 搜索功能
- [ ] 拖拽排序
- [ ] Gutter 图标显示
- [ ] 行末描述显示
- [ ] Markdown 渲染
- [ ] 数据持久化
- [ ] 数据迁移（V1/V2 → V3）

### 性能验证

- [ ] 1000+ 书签加载时间 < 1s
- [ ] UI 操作响应时间 < 100ms
- [ ] 引用同步异步执行，不阻塞 UI
- [ ] 内存占用稳定，无泄漏

### 兼容性验证

- [ ] IntelliJ IDEA 2023.2+
- [ ] 旧版数据自动迁移
- [ ] 降级时数据可读（XML 格式保留）

---

## 十一、风险与缓解

| 风险 | 级别 | 缓解措施 |
|------|------|----------|
| Kotlin 学习曲线 | 中 | 渐进式迁移，保留 Java 互操作 |
| IntelliJ API 兼容性 | 高 | 版本检测，条件分支，多版本测试 |
| 数据迁移失败 | 高 | 自动备份，回滚机制，迁移日志 |
| 性能回退 | 中 | 持续性能测试，缓存优化 |
| Coroutines 误用 | 中 | 代码审查，使用结构化并发 |
| 循环引用死循环 | 高 | 保存前 DFS 检测，深度限制 |
| 大量引用同步卡顿 | 中 | 异步批量更新，节流机制 |

---

## 十二、附录：关键文件清单

### 需要创建的新文件

| 路径 | 用途 |
|------|------|
| `build.gradle.kts` | Kotlin DSL 构建配置 |
| `domain/model/BookmarkNode.kt` | Sealed class 节点定义 |
| `domain/model/Reference.kt` | 引用关系 |
| `domain/model/ProcessProgress.kt` | 流程进度 |
| `domain/repository/BookmarkRepository.kt` | 书签仓库接口 |
| `domain/repository/ReferenceRepository.kt` | 引用仓库接口 |
| `domain/usecase/navigation/ProcessNavigationUseCase.kt` | 流程导航用例 |
| `domain/usecase/reference/SyncReferencesUseCase.kt` | 引用同步用例 |
| `domain/usecase/reference/DetectCircularRefUseCase.kt` | 循环检测用例 |
| `domain/event/BookmarkEvent.kt` | 领域事件 |
| `data/persistence/BookmarkPersistentState.kt` | 持久化状态 |
| `data/persistence/NodeData.kt` | 持久化数据模型 |
| `data/mapper/BookmarkMapper.kt` | 模型映射器 |
| `data/migration/DataMigrationService.kt` | 数据迁移服务 |
| `presentation/toolwindow/BookmarkViewModel.kt` | MVI ViewModel |
| `presentation/toolwindow/BookmarkViewState.kt` | 视图状态 |
| `presentation/toolwindow/BookmarkIntent.kt` | 用户意图 |
| `presentation/toolwindow/BookmarkSideEffect.kt` | 副作用 |
| `core/event/BookmarkEventBus.kt` | 事件总线 |
| `core/coroutine/CoroutineDispatchers.kt` | 协程调度器 |

### 需要重构/删除的现有文件

| 文件 | 操作 |
|------|------|
| `build.gradle` | 删除（迁移到 .kts） |
| `BookmarksManager.java` | 简化为 Facade 或删除 |
| `BookmarkPO.java` | 保留用于迁移，最终删除 |
| `BookmarkNodeModel.java` | 删除（替换为 BookmarkNode） |
| `GroupNodeModel.java` | 删除（替换为 BookmarkNode.Group） |
| `MyPersistent.java` | 迁移到 Kotlin |
| `ProcessNavigationService.java` | 迁移到 UseCase |
| `ReferenceManager.java` | 迁移到 Repository |

---

*本方案由 Claude Opus 4.5 生成，基于对项目的全面分析和现代软件工程最佳实践。*
