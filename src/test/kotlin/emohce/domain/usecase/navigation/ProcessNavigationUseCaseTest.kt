package emohce.domain.usecase.navigation

import emohce.domain.event.BookmarkEvent
import emohce.domain.model.BookmarkNode
import emohce.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProcessNavigationUseCaseTest {
    @Test
    fun `finds next and previous in process`() {
        val a = BookmarkNode.Bookmark(name = "A", filePath = "a", line = 1)
        val b = BookmarkNode.Bookmark(name = "B", filePath = "b", line = 2)
        val c = BookmarkNode.Bookmark(name = "C", filePath = "c", line = 3)
        val process = BookmarkNode.Process(
            name = "Flow",
            steps = listOf(a, b, c)
        )
        val root = BookmarkNode.Group(name = "root", children = listOf(process))
        val repo = FakeBookmarkRepository(root)
        val useCase = ProcessNavigationUseCase(repo)

        val next = kotlinx.coroutines.runBlocking { useCase.findNext(a) }
        val prev = kotlinx.coroutines.runBlocking { useCase.findPrevious(c) }

        assertEquals(b.uuid, next?.uuid)
        assertEquals(b.uuid, prev?.uuid)
    }

    private class FakeBookmarkRepository(private val root: BookmarkNode.Group) : BookmarkRepository {
        override suspend fun getRootNode(): BookmarkNode.Group = root
        override suspend fun findByUuid(uuid: String): BookmarkNode? = null
        override suspend fun findByFilePath(filePath: String): List<BookmarkNode.Bookmark> = emptyList()
        override suspend fun findParent(nodeId: String): BookmarkNode? = null
        override suspend fun search(query: String, limit: Int): List<BookmarkNode> = emptyList()
        override suspend fun create(node: BookmarkNode, parentId: String?, index: Int?) = Unit
        override suspend fun update(node: BookmarkNode) = Unit
        override suspend fun delete(nodeId: String) = Unit
        override suspend fun move(nodeId: String, newParentId: String?, newIndex: Int) = Unit
        override suspend fun reorder(parentId: String, orderedChildIds: List<String>) = Unit
        override fun observeChanges(): Flow<BookmarkEvent> = emptyFlow()
        override fun observeNode(nodeId: String): Flow<BookmarkNode?> = emptyFlow()
    }
}
