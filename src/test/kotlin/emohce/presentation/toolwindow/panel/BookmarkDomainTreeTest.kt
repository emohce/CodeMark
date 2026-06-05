package emohce.presentation.toolwindow.panel

import emohce.domain.model.BookmarkNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BookmarkDomainTreeTest {
    @Test
    fun `collectNestedContainerIds includes group self and nested containers only`() {
        val innerGroup = BookmarkNode.Group(uuid = "g-inner", name = "Inner")
        val process = BookmarkNode.Process(uuid = "p-1", name = "Proc", steps = emptyList())
        val outer = BookmarkNode.Group(
            uuid = "g-outer",
            name = "Outer",
            children = listOf(innerGroup, process, BookmarkNode.Bookmark(uuid = "b-1", name = "Bm", filePath = "a.kt", line = 1))
        )

        val ids = BookmarkDomainTree.collectNestedContainerIds(outer)

        assertEquals(setOf("g-outer", "g-inner", "p-1"), ids)
        assertTrue("b-1" !in ids)
    }

    @Test
    fun `withoutNodeAndDescendants removes node and keeps siblings`() {
        val target = BookmarkNode.Bookmark(uuid = "b-1", name = "B", filePath = "a.kt", line = 1)
        val root = BookmarkNode.Group(
            uuid = "g-root",
            name = "Root",
            children = listOf(
                target,
                BookmarkNode.Group(uuid = "g-2", name = "G2")
            )
        )
        val pruned = BookmarkDomainTree.withoutNodeAndDescendants(root, "b-1") as BookmarkNode.Group
        assertEquals(1, pruned.children.size)
        assertEquals("g-2", pruned.children.first().uuid)
    }
}
