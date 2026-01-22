package emohce.data.mapper

import emohce.domain.model.BookmarkNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class BookmarkMapperTest {
    @Test
    fun `maps bookmark round trip`() {
        val bookmark = BookmarkNode.Bookmark(
            uuid = "b1",
            name = "Intro",
            description = "desc",
            createdAt = Instant.ofEpochMilli(1000),
            modifiedAt = Instant.ofEpochMilli(2000),
            filePath = "/tmp/file.kt",
            line = 10,
            column = 2,
            iconPath = null
        )

        val data = BookmarkMapper.toData(bookmark)
        val restored = BookmarkMapper.fromData(data) as BookmarkNode.Bookmark

        assertEquals(bookmark.uuid, restored.uuid)
        assertEquals(bookmark.name, restored.name)
        assertEquals(bookmark.description, restored.description)
        assertEquals(bookmark.filePath, restored.filePath)
        assertEquals(bookmark.line, restored.line)
        assertEquals(bookmark.column, restored.column)
    }

    @Test
    fun `maps group with children`() {
        val child = BookmarkNode.DescriptiveBookmark(
            uuid = "d1",
            name = "Note",
            description = "",
            markdownContent = "md"
        )
        val group = BookmarkNode.Group(
            uuid = "g1",
            name = "Group",
            description = "",
            children = listOf(child)
        )

        val data = BookmarkMapper.toData(group)
        val restored = BookmarkMapper.fromData(data) as BookmarkNode.Group

        assertEquals(group.uuid, restored.uuid)
        assertEquals(group.children.size, restored.children.size)
        val restoredChild = restored.children.first() as BookmarkNode.DescriptiveBookmark
        assertEquals(child.uuid, restoredChild.uuid)
        assertEquals(child.markdownContent, restoredChild.markdownContent)
    }
}
