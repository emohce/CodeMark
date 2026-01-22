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

    fun save() {
        val state = BookmarkPersistentState(
            version = BookmarkPersistentState.CURRENT_VERSION,
            root = BookmarkMapper.toData(root) as emohce.data.persistence.NodeData.GroupData,
            references = references.map { BookmarkMapper.toReferenceData(it) }
        )
        dataSource.save(state)
    }

    fun replaceRoot(newRoot: BookmarkNode.Group) {
        root = newRoot
        save()
    }
}
