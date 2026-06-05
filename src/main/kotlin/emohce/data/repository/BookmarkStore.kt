package emohce.data.repository

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.io.FileUtil
import emohce.data.datasource.BookmarkPersistentDataSource
import emohce.data.datasource.BookmarkTreeUiDataSource
import emohce.data.persistence.BookmarkTreeUiState
import emohce.data.mapper.BookmarkMapper
import emohce.data.persistence.BookmarkPersistentState
import emohce.data.persistence.NodeData
import emohce.domain.model.BookmarkNode
import emohce.domain.model.Reference
import java.io.File
import java.nio.file.Path

/** 单个根文件的内存表示 */
data class RootFileEntry(
    val filePath: Path,
    var root: BookmarkNode.Group,
    val references: MutableList<Reference>
)

class BookmarkStore(private val project: Project) {
    private val logger = Logger.getInstance(BookmarkStore::class.java)
    private val dataSource = BookmarkPersistentDataSource(project)
    private val treeUiDataSource = BookmarkTreeUiDataSource(project)

    @Volatile
    private var persistedExpandedNodeIds: Set<String> = emptySet()

    init {
        loadTreeUiStateFromDisk()
    }

    /** 自身保存操作的时间戳，用于 VFS 监听器跳过自触发的文件变化 */
    @Volatile
    private var lastSaveTimestamp: Long = 0L

    @Volatile
    var revision: Long = 0L
        private set

    @Volatile
    private var skipNextVfsReload: Boolean = false

    /** 判断是否是自身刚刚触发的保存（含拖拽落盘后的 VFS 回调，窗口略宽） */
    fun isRecentSelfSave(): Boolean {
        return System.currentTimeMillis() - lastSaveTimestamp < 2000L
    }

    fun markSelfMutationPendingVfsSkip() {
        skipNextVfsReload = true
        lastSaveTimestamp = System.currentTimeMillis()
    }

    fun consumeSkipVfsReload(): Boolean {
        if (skipNextVfsReload) {
            skipNextVfsReload = false
            return true
        }
        return isRecentSelfSave()
    }

    /** 按文件路径索引的所有根文件 */
    val rootFiles: MutableMap<String, RootFileEntry> = linkedMapOf()
    private val nodeById: MutableMap<String, BookmarkNode> = linkedMapOf()
    private val parentById: MutableMap<String, String?> = linkedMapOf()
    private val ownerFileByNodeId: MutableMap<String, RootFileEntry> = linkedMapOf()
    
    /**
     * 将绝对路径转换为相对于项目根目录的相对路径
     * 如果路径不在项目根目录下，保持绝对路径
     */
    private fun toRelativePath(absolutePath: String?): String? {
        if (absolutePath.isNullOrBlank()) return absolutePath
        val basePath = project.basePath ?: return absolutePath
        
        try {
            val baseFile = File(basePath).canonicalFile
            val pathFile = File(absolutePath).canonicalFile
            
            val basePathStr = FileUtil.toSystemIndependentName(baseFile.absolutePath)
            val pathStr = FileUtil.toSystemIndependentName(pathFile.absolutePath)
            
            // 检查路径是否在项目根目录下（使用规范化后的路径进行比较）
            if (pathStr.startsWith(basePathStr)) {
                val relativePath = baseFile.toPath().relativize(pathFile.toPath())
                val relativePathStr = FileUtil.toSystemIndependentName(relativePath.toString())
                // 如果相对路径为空，说明是项目根目录本身，返回 "."
                return if (relativePathStr.isEmpty()) "." else relativePathStr
            }
        } catch (e: Exception) {
            // 如果转换失败，保持原路径
        }
        
        // 如果路径不在项目根目录下，保持绝对路径
        return FileUtil.toSystemIndependentName(absolutePath)
    }
    
    /**
     * 将相对路径转换为基于项目根目录的绝对路径
     * 如果路径已经是绝对路径，直接返回
     */
    private fun toAbsolutePath(relativePath: String?): String? {
        if (relativePath.isNullOrBlank()) return relativePath
        val basePath = project.basePath ?: return relativePath
        
        try {
            val normalizedPath = FileUtil.toSystemIndependentName(relativePath)
            val pathFile = File(normalizedPath)
            
            // 如果已经是绝对路径，直接返回（规范化后）
            if (pathFile.isAbsolute) {
                return normalizedPath
            }
            
            // 处理 "." 表示项目根目录的情况
            val pathToResolve = if (normalizedPath == "." || normalizedPath.isEmpty()) {
                basePath
            } else {
                normalizedPath
            }
            
            // 转换为绝对路径
            val absoluteFile = File(basePath, pathToResolve).canonicalFile
            return FileUtil.toSystemIndependentName(absoluteFile.absolutePath)
        } catch (e: Exception) {
            // 如果转换失败，返回规范化后的原路径
            return FileUtil.toSystemIndependentName(relativePath)
        }
    }
    
    /**
     * 将 BookmarkNode 转换为 NodeData，并将文件路径转换为相对路径
     */
    private fun toDataWithRelativePaths(node: BookmarkNode): NodeData {
        val data = BookmarkMapper.toData(node)
        return convertDataPathsToRelative(data)
    }
    
    /**
     * 递归转换 NodeData 中的路径为相对路径
     */
    private fun convertDataPathsToRelative(data: NodeData): NodeData {
        return when (data) {
            is NodeData.BookmarkData -> data.copy(
                filePath = toRelativePath(data.filePath) ?: data.filePath
            )
            is NodeData.ProcessData -> data.copy(
                entryFilePath = toRelativePath(data.entryFilePath),
                steps = data.steps.map { convertDataPathsToRelative(it) }
            )
            is NodeData.GroupData -> data.copy(
                children = data.children.map { convertDataPathsToRelative(it) }
            )
            is NodeData.DescriptiveData -> data
        }
    }
    
    /**
     * 将 NodeData 转换为 BookmarkNode，并将相对路径转换为绝对路径
     */
    private fun fromDataWithAbsolutePaths(data: NodeData): BookmarkNode {
        // 先将 NodeData 中的相对路径转换为绝对路径，然后转换为 BookmarkNode
        // 注意：不需要再次转换 BookmarkNode，因为 BookmarkMapper.fromData() 只是字段复制
        return BookmarkMapper.fromData(convertDataPathsToAbsolute(data))
    }
    
    /**
     * 递归转换 NodeData 中的路径为绝对路径
     */
    private fun convertDataPathsToAbsolute(data: NodeData): NodeData {
        return when (data) {
            is NodeData.BookmarkData -> data.copy(
                filePath = toAbsolutePath(data.filePath) ?: data.filePath
            )
            is NodeData.ProcessData -> data.copy(
                entryFilePath = toAbsolutePath(data.entryFilePath),
                steps = data.steps.map { convertDataPathsToAbsolute(it) }
            )
            is NodeData.GroupData -> data.copy(
                children = data.children.map { convertDataPathsToAbsolute(it) }
            )
            is NodeData.DescriptiveData -> data
        }
    }
    

    /** 内存 stash：保存上一次操作前的快照，用于单步撤销 */
    private var stash: Map<String, Pair<BookmarkNode.Group, List<Reference>>>? = null

    /** 虚拟超级根：聚合所有文件根作为 children */
    var root: BookmarkNode.Group
        private set

    /** 所有文件的引用合集 */
    val references: MutableList<Reference>

    init {
        references = mutableListOf()
        root = BookmarkNode.Group(uuid = SUPER_ROOT_UUID, name = ROOT_NODE_NAME)
        loadAllFiles()
    }

    /** 扫描 .codemark/ 目录加载所有根文件 */
    private fun loadAllFiles() {
        val basePath = project.basePath ?: return
        rootFiles.clear()
        references.clear()

        val files = BookmarkPersistentDataSource.listRootFiles(basePath)
        if (files.isEmpty()) {
            // 无任何文件时，创建默认 codemark.json
            val defaultRoot = BookmarkNode.Group(uuid = "root", name = "CodeMarks")
            val defaultPath = BookmarkPersistentDataSource.dataPath(basePath)
            val entry = RootFileEntry(defaultPath, defaultRoot, mutableListOf())
            rootFiles[defaultPath.toString()] = entry
            saveFileEntry(entry)
        } else {
            for (filePath in files) {
                val state = dataSource.loadFrom(filePath) ?: continue
                val rootData = state.rootData() ?: continue
                val fileRoot = fromDataWithAbsolutePaths(rootData) as BookmarkNode.Group
                val fileRefs = state.references.map { BookmarkMapper.fromReferenceData(it) }.toMutableList()
                val entry = RootFileEntry(filePath, fileRoot, fileRefs)
                rootFiles[filePath.toString()] = entry
                references.addAll(fileRefs)
                if (state.version < BookmarkPersistentState.CURRENT_VERSION) {
                    saveFileEntry(entry)
                }
            }
        }
        rebuildSuperRoot()
        bumpRevision()
        loadTreeUiStateFromDisk()
    }

    fun getValidExpandedNodeIds(): Set<String> =
        persistedExpandedNodeIds.filter { nodeById.containsKey(it) }.toSet()

    fun saveExpandedNodeIds(ids: Set<String>) {
        persistedExpandedNodeIds = ids
        persistTreeUiStateToDisk()
    }

    private fun loadTreeUiStateFromDisk() {
        persistedExpandedNodeIds = treeUiDataSource.load()?.expandedNodeIds?.toSet() ?: emptySet()
    }

    private fun persistTreeUiStateToDisk() {
        treeUiDataSource.save(
            BookmarkTreeUiState(expandedNodeIds = persistedExpandedNodeIds.toList())
        )
    }

    /** 重建虚拟超级根：将所有文件根作为 children */
    private fun rebuildSuperRoot() {
        val children = rootFiles.values.map { it.root }
        root = BookmarkNode.Group(
            uuid = SUPER_ROOT_UUID,
            name = ROOT_NODE_NAME,
            children = children
        )
        rebuildRuntimeIndexes()
    }

    private fun rebuildRuntimeIndexes() {
        nodeById.clear()
        parentById.clear()
        ownerFileByNodeId.clear()
        rootFiles.values.forEach { entry ->
            indexNode(entry.root, null, entry)
        }
        nodeById[root.uuid] = root
        parentById[root.uuid] = null
    }

    private fun indexNode(node: BookmarkNode, parentId: String?, owner: RootFileEntry) {
        nodeById[node.uuid] = node
        parentById[node.uuid] = parentId
        ownerFileByNodeId[node.uuid] = owner
        when (node) {
            is BookmarkNode.Group -> node.children.forEach { indexNode(it, node.uuid, owner) }
            is BookmarkNode.Process -> node.steps.forEach { indexNode(it, node.uuid, owner) }
            else -> Unit
        }
    }

    /** 保存单个文件条目 */
    private fun saveFileEntry(entry: RootFileEntry) {
        dataSource.saveTo(
            entry.filePath,
            BookmarkPersistentState.fromRoot(
                toDataWithRelativePaths(entry.root) as NodeData.GroupData,
                entry.references.map { BookmarkMapper.toReferenceData(it) }
            )
        )
        lastSaveTimestamp = System.currentTimeMillis()
    }

    /** 保存所有文件（先将聚合引用同步回各文件条目） */
    fun save() {
        syncReferencesToFiles()
        rootFiles.values.forEach { saveFileEntry(it) }
    }

    /** 将聚合 references 按 sourceId 所属文件分配回各 RootFileEntry */
    private fun syncReferencesToFiles() {
        // 清空各文件的引用
        rootFiles.values.forEach { it.references.clear() }
        // 按 sourceId 归属文件重新分配
        for (ref in references) {
            val entry = findOwnerFile(ref.sourceId)
            entry?.references?.add(ref)
        }
    }

    /** 查找节点所属的根文件 */
    fun findOwnerFile(nodeId: String): RootFileEntry? {
        return ownerFileByNodeId[nodeId]
    }

    fun firstFileRootId(): String? {
        return rootFiles.values.firstOrNull()?.root?.uuid
    }

    fun nodeById(nodeId: String): BookmarkNode? {
        return nodeById[nodeId]
    }

    fun parentIdOf(nodeId: String): String? {
        return parentById[nodeId]
    }

    fun insertNode(parentId: String?, node: BookmarkNode, index: Int?): Boolean {
        val targetParentId = normalizeParentId(parentId) ?: return false
        val targetEntry = findEntryForParent(targetParentId) ?: return false
        stashCurrent()
        val updatedRoot = replaceChildren(targetEntry.root, targetParentId) { children ->
            insertAt(children, node, index)
        }
        if (updatedRoot == targetEntry.root) return false
        targetEntry.root = updatedRoot
        persistMutation(setOf(targetEntry))
        return true
    }

    fun updateNode(node: BookmarkNode): BookmarkNode? {
        val entry = findOwnerFile(node.uuid) ?: return null
        val previous = nodeById[node.uuid] ?: return null
        stashCurrent()
        val updatedRoot = replaceNode(entry.root, node) as BookmarkNode.Group
        if (updatedRoot == entry.root) return previous
        entry.root = updatedRoot
        persistMutation(setOf(entry))
        return previous
    }

    fun deleteNode(nodeId: String): String? {
        if (nodeId == SUPER_ROOT_UUID || rootFiles.values.any { it.root.uuid == nodeId }) return null
        val entry = findOwnerFile(nodeId) ?: return null
        val removed = nodeById[nodeId] ?: return null
        val parentId = parentById[nodeId]
        stashCurrent()
        val removedIds = collectNodeIds(removed)
        entry.root = removeNode(entry.root, nodeId)
        references.removeAll { it.sourceId in removedIds || it.targetId in removedIds }
        rootFiles.values.forEach { it.references.removeAll { ref -> ref.sourceId in removedIds || ref.targetId in removedIds } }
        persistMutation(setOf(entry))
        return parentId
    }

    fun moveNode(nodeId: String, newParentId: String?, newIndex: Int): Boolean {
        if (nodeId == SUPER_ROOT_UUID || rootFiles.values.any { it.root.uuid == nodeId }) return false
        val node = nodeById[nodeId] ?: return false
        val sourceEntry = findOwnerFile(nodeId) ?: return false
        val targetParentId = normalizeParentId(newParentId) ?: return false
        val targetEntry = findEntryForParent(targetParentId) ?: return false
        if (isDescendant(node, targetParentId)) return false
        stashCurrent()
        sourceEntry.root = removeNode(sourceEntry.root, nodeId)
        targetEntry.root = replaceChildren(targetEntry.root, targetParentId) { children ->
            insertAt(children, node, newIndex)
        }
        persistMutation(setOf(sourceEntry, targetEntry))
        return true
    }

    fun reorderChildren(parentId: String, orderedChildIds: List<String>): Boolean {
        val entry = findEntryForParent(parentId) ?: return false
        stashCurrent()
        val updatedRoot = replaceChildren(entry.root, parentId) { children ->
            val byId = children.associateBy { it.uuid }
            orderedChildIds.mapNotNull { byId[it] }
        }
        if (updatedRoot == entry.root) return false
        entry.root = updatedRoot
        persistMutation(setOf(entry))
        return true
    }

    /** 替换虚拟超级根（实际替换对应文件的根） */
    fun replaceRoot(newRoot: BookmarkNode.Group) {
        // 保存当前状态到 stash
        stash = rootFiles.mapValues { (_, entry) ->
            entry.root.copy() to entry.references.toList()
        }

        if (newRoot.uuid == SUPER_ROOT_UUID) {
            // 替换整个超级根：更新每个文件根
            for (child in newRoot.children) {
                if (child is BookmarkNode.Group) {
                    val entry = rootFiles.values.find { it.root.uuid == child.uuid }
                    if (entry != null) {
                        entry.root = child
                        saveFileEntry(entry)
                    }
                }
            }
        } else {
            // 替换某个文件的根
            val entry = rootFiles.values.find { it.root.uuid == newRoot.uuid }
            if (entry != null) {
                entry.root = newRoot
                saveFileEntry(entry)
            } else {
                logger.warn("replaceRoot called with non-root node uuid=${newRoot.uuid}")
            }
        }
        // 重建引用合集
        references.clear()
        rootFiles.values.forEach { references.addAll(it.references) }
        rebuildSuperRoot()
        bumpRevision()
    }

    /** 创建新的根文件 */
    fun createNewRootFile(name: String): BookmarkNode.Group {
        val basePath = project.basePath ?: throw IllegalStateException("Project basePath is null")
        val filePath = BookmarkPersistentDataSource.createRootFilePath(basePath, name)
        val newRoot = BookmarkNode.Group(name = name.trim())
        val entry = RootFileEntry(filePath, newRoot, mutableListOf())
        rootFiles[filePath.toString()] = entry
        saveFileEntry(entry)
        rebuildSuperRoot()
        bumpRevision()
        return newRoot
    }

    /** 跨文件移动节点 */
    fun moveNodeAcrossFiles(nodeId: String, targetFileRootId: String) {
        moveNode(nodeId, targetFileRootId, -1)
    }

    /** 从内存 stash 恢复上一次操作前的状态 */
    fun undo(): Boolean {
        val snapshot = stash ?: return false
        for ((key, pair) in snapshot) {
            val entry = rootFiles[key] ?: continue
            entry.root = pair.first
            entry.references.clear()
            entry.references.addAll(pair.second)
            saveFileEntry(entry)
        }
        stash = null
        references.clear()
        rootFiles.values.forEach { references.addAll(it.references) }
        rebuildSuperRoot()
        bumpRevision()
        return true
    }

    fun canUndo(): Boolean = stash != null

    private fun bumpRevision() {
        revision += 1
    }

    private fun stashCurrent() {
        stash = rootFiles.mapValues { (_, entry) ->
            entry.root.copy() to entry.references.toList()
        }
    }

    private fun persistMutation(entries: Set<RootFileEntry>) {
        markSelfMutationPendingVfsSkip()
        syncReferencesToFiles()
        entries.forEach { saveFileEntry(it) }
        references.clear()
        rootFiles.values.forEach { references.addAll(it.references) }
        rebuildSuperRoot()
        bumpRevision()
    }

    private fun normalizeParentId(parentId: String?): String? {
        return if (parentId == null || parentId == SUPER_ROOT_UUID) firstFileRootId() else parentId
    }

    private fun findEntryForParent(parentId: String): RootFileEntry? {
        return ownerFileByNodeId[parentId] ?: rootFiles.values.find { it.root.uuid == parentId }
    }

    private fun replaceNode(current: BookmarkNode, updated: BookmarkNode): BookmarkNode {
        if (current.uuid == updated.uuid) return updated
        return when (current) {
            is BookmarkNode.Group -> current.copy(children = current.children.map { replaceNode(it, updated) })
            is BookmarkNode.Process -> current.copy(steps = current.steps.map { replaceNode(it, updated) })
            else -> current
        }
    }

    private fun removeNode(current: BookmarkNode, targetId: String): BookmarkNode.Group {
        return removeNodeInternal(current, targetId) as BookmarkNode.Group
    }

    private fun removeNodeInternal(current: BookmarkNode, targetId: String): BookmarkNode {
        return when (current) {
            is BookmarkNode.Group -> current.copy(
                children = current.children
                    .filterNot { it.uuid == targetId }
                    .map { removeNodeInternal(it, targetId) }
            )
            is BookmarkNode.Process -> current.copy(
                steps = current.steps
                    .filterNot { it.uuid == targetId }
                    .map { removeNodeInternal(it, targetId) }
            )
            else -> current
        }
    }

    private fun replaceChildren(
        current: BookmarkNode,
        parentId: String,
        transform: (List<BookmarkNode>) -> List<BookmarkNode>
    ): BookmarkNode.Group {
        return replaceChildrenInternal(current, parentId, transform) as BookmarkNode.Group
    }

    private fun replaceChildrenInternal(
        current: BookmarkNode,
        parentId: String,
        transform: (List<BookmarkNode>) -> List<BookmarkNode>
    ): BookmarkNode {
        return when (current) {
            is BookmarkNode.Group -> {
                if (current.uuid == parentId) {
                    current.copy(children = transform(current.children))
                } else {
                    current.copy(children = current.children.map { replaceChildrenInternal(it, parentId, transform) })
                }
            }
            is BookmarkNode.Process -> {
                if (current.uuid == parentId) {
                    current.copy(steps = transform(current.steps))
                } else {
                    current.copy(steps = current.steps.map { replaceChildrenInternal(it, parentId, transform) })
                }
            }
            else -> current
        }
    }

    private fun collectNodeIds(node: BookmarkNode): Set<String> {
        val ids = linkedSetOf<String>()
        fun visit(current: BookmarkNode) {
            ids.add(current.uuid)
            when (current) {
                is BookmarkNode.Group -> current.children.forEach(::visit)
                is BookmarkNode.Process -> current.steps.forEach(::visit)
                else -> Unit
            }
        }
        visit(node)
        return ids
    }

    private fun isDescendant(node: BookmarkNode, targetId: String): Boolean {
        if (node.uuid == targetId) return true
        return when (node) {
            is BookmarkNode.Group -> node.children.any { isDescendant(it, targetId) }
            is BookmarkNode.Process -> node.steps.any { isDescendant(it, targetId) }
            else -> false
        }
    }

    private fun <T> insertAt(list: List<T>, item: T, index: Int?): List<T> {
        if (index == null || index < 0 || index >= list.size) {
            return list + item
        }
        val mutable = list.toMutableList()
        mutable.add(index, item)
        return mutable.toList()
    }
    
    companion object {
        const val ROOT_NODE_NAME = "CodeMarks"
        const val SUPER_ROOT_UUID = "super-root"
    }
    
    fun reload() {
        val basePath = project.basePath ?: return
        // 刷新 VFS 缓存
        val dir = BookmarkPersistentDataSource.dataDirPath(basePath)
        val normalizedDir = FileUtil.toSystemIndependentName(dir.toString())
        val vfsDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedDir)
        vfsDir?.refresh(false, true)

        loadAllFiles()
    }
}
