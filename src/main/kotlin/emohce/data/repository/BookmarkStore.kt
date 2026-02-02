package emohce.data.repository

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

class BookmarkStore(private val project: Project) {
    private val dataSource = BookmarkPersistentDataSource(project)
    
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
    

    var root: BookmarkNode.Group
        private set

    val references: MutableList<Reference>

    init {
        val state = dataSource.load()
        if (state == null) {
            root = BookmarkNode.Group(uuid = "root", name = "Bookmarks")
            references = mutableListOf()
        } else {
            // 从数据加载时，将相对路径转换为绝对路径
            root = fromDataWithAbsolutePaths(state.root) as BookmarkNode.Group
            references = state.references.map { BookmarkMapper.fromReferenceData(it) }.toMutableList()
        }
    }

    fun save(saveUndo: Boolean = true) {
        // 保存时，将绝对路径转换为相对路径
        val state = BookmarkPersistentState(
            version = BookmarkPersistentState.CURRENT_VERSION,
            root = toDataWithRelativePaths(root) as emohce.data.persistence.NodeData.GroupData,
            references = references.map { BookmarkMapper.toReferenceData(it) }
        )
        dataSource.save(state, saveUndo)
    }

    fun replaceRoot(newRoot: BookmarkNode.Group) {
        root = newRoot
        save(saveUndo = true) // 保存时自动保存撤销文件
    }

    fun undo(): Boolean {
        val undoState = dataSource.loadUndo() ?: return false
        // 从撤销数据加载时，将相对路径转换为绝对路径
        root = fromDataWithAbsolutePaths(undoState.root) as BookmarkNode.Group
        references.clear()
        references.addAll(undoState.references.map { BookmarkMapper.fromReferenceData(it) })
        // 撤销后保存，但不保存撤销文件（避免循环撤销）
        save(saveUndo = false)
        return true
    }

    fun canUndo(): Boolean = dataSource.hasUndo()
    
    fun reload() {
        // 刷新 VFS 缓存，确保读取最新的文件内容
        val basePath = project.basePath ?: return
        val bookmarkxPath = BookmarkPersistentDataSource.dataPath(basePath)
        val normalizedPath = FileUtil.toSystemIndependentName(bookmarkxPath.toString())
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalizedPath)
        file?.refresh(false, false)
        
        val state = dataSource.load()
        if (state != null) {
            // 从数据加载时，将相对路径转换为绝对路径
            root = fromDataWithAbsolutePaths(state.root) as BookmarkNode.Group
            references.clear()
            references.addAll(state.references.map { BookmarkMapper.fromReferenceData(it) })
        }
    }
}
