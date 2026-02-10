package emohce.data.repository

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.io.FileUtil
import emohce.data.datasource.BookmarkPersistentDataSource
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

    /** 自身保存操作的时间戳，用于 VFS 监听器跳过自触发的文件变化 */
    @Volatile
    private var lastSaveTimestamp: Long = 0L

    /** 判断是否是自身刚刚触发的保存（500ms 窗口内） */
    fun isRecentSelfSave(): Boolean {
        return System.currentTimeMillis() - lastSaveTimestamp < 500L
    }

    /** 按文件路径索引的所有根文件 */
    val rootFiles: MutableMap<String, RootFileEntry> = linkedMapOf()
    
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
                val fileRoot = fromDataWithAbsolutePaths(state.root) as BookmarkNode.Group
                val fileRefs = state.references.map { BookmarkMapper.fromReferenceData(it) }.toMutableList()
                rootFiles[filePath.toString()] = RootFileEntry(filePath, fileRoot, fileRefs)
                references.addAll(fileRefs)
            }
        }
        rebuildSuperRoot()
    }

    /** 重建虚拟超级根：将所有文件根作为 children */
    private fun rebuildSuperRoot() {
        val children = rootFiles.values.map { it.root }
        root = BookmarkNode.Group(
            uuid = SUPER_ROOT_UUID,
            name = ROOT_NODE_NAME,
            children = children
        )
    }

    /** 保存单个文件条目 */
    private fun saveFileEntry(entry: RootFileEntry) {
        val state = BookmarkPersistentState(
            version = BookmarkPersistentState.CURRENT_VERSION,
            root = toDataWithRelativePaths(entry.root) as NodeData.GroupData,
            references = entry.references.map { BookmarkMapper.toReferenceData(it) }
        )
        dataSource.saveTo(entry.filePath, state)
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
        // 如果是文件根本身
        for (entry in rootFiles.values) {
            if (entry.root.uuid == nodeId) return entry
            if (findNodeInGroup(entry.root, nodeId) != null) return entry
        }
        return null
    }

    private fun findNodeInGroup(node: BookmarkNode, targetId: String): BookmarkNode? {
        if (node.uuid == targetId) return node
        return when (node) {
            is BookmarkNode.Group -> node.children.firstNotNullOfOrNull { findNodeInGroup(it, targetId) }
            is BookmarkNode.Process -> node.steps.firstNotNullOfOrNull { findNodeInGroup(it, targetId) }
            else -> null
        }
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
                // 尝试查找包含该节点的文件并替换其根
                for (e in rootFiles.values) {
                    if (findNodeInGroup(e.root, newRoot.uuid) != null) {
                        // 这不应该发生，但作为安全回退
                        logger.warn("replaceRoot called with non-root node uuid=${newRoot.uuid}")
                        break
                    }
                }
            }
        }
        // 重建引用合集
        references.clear()
        rootFiles.values.forEach { references.addAll(it.references) }
        rebuildSuperRoot()
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
        return newRoot
    }

    /** 跨文件移动节点 */
    fun moveNodeAcrossFiles(nodeId: String, targetFileRootId: String) {
        val sourceEntry = findOwnerFile(nodeId) ?: return
        val targetEntry = rootFiles.values.find { it.root.uuid == targetFileRootId } ?: return
        if (sourceEntry === targetEntry) return

        val node = findNodeInGroup(sourceEntry.root, nodeId) ?: return
        // 从源文件移除
        sourceEntry.root = removeNodeFromGroup(sourceEntry.root, nodeId)
        // 添加到目标文件
        targetEntry.root = targetEntry.root.copy(
            children = targetEntry.root.children + node
        )
        saveFileEntry(sourceEntry)
        saveFileEntry(targetEntry)
        rebuildSuperRoot()
    }

    private fun removeNodeFromGroup(group: BookmarkNode.Group, nodeId: String): BookmarkNode.Group {
        return group.copy(
            children = group.children
                .filterNot { it.uuid == nodeId }
                .map { child ->
                    when (child) {
                        is BookmarkNode.Group -> removeNodeFromGroup(child, nodeId)
                        is BookmarkNode.Process -> child.copy(
                            steps = child.steps.filterNot { it.uuid == nodeId }
                        )
                        else -> child
                    }
                }
        )
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
        return true
    }

    fun canUndo(): Boolean = stash != null
    
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
