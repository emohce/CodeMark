package emohce.data.repository

import com.intellij.openapi.project.Project
import emohce.data.datasource.BookmarkPersistentDataSource
import emohce.data.mapper.BookmarkMapper
import emohce.data.persistence.BookmarkPersistentState
import emohce.domain.model.BookmarkNode
import emohce.domain.model.Reference

class BookmarkStore(project: Project) {
    private val dataSource = BookmarkPersistentDataSource(project)

    var root: BookmarkNode.Group
        private set

    val references: MutableList<Reference>

    init {
        val state = dataSource.load()
        if (state == null) {
            root = BookmarkNode.Group(uuid = "root", name = "Bookmarks")
            references = mutableListOf()
        } else {
            root = BookmarkMapper.fromData(state.root) as BookmarkNode.Group
            references = state.references.map { BookmarkMapper.fromReferenceData(it) }.toMutableList()
        }
    }

    fun save(saveUndo: Boolean = true) {
        val state = BookmarkPersistentState(
            version = BookmarkPersistentState.CURRENT_VERSION,
            root = BookmarkMapper.toData(root) as emohce.data.persistence.NodeData.GroupData,
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
        root = BookmarkMapper.fromData(undoState.root) as BookmarkNode.Group
        references.clear()
        references.addAll(undoState.references.map { BookmarkMapper.fromReferenceData(it) })
        // 撤销后保存，但不保存撤销文件（避免循环撤销）
        save(saveUndo = false)
        return true
    }

    fun canUndo(): Boolean = dataSource.hasUndo()
    
    fun reload() {
        val state = dataSource.load()
        if (state != null) {
            root = BookmarkMapper.fromData(state.root) as BookmarkNode.Group
            references.clear()
            references.addAll(state.references.map { BookmarkMapper.fromReferenceData(it) })
        }
    }
}
