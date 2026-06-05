package emohce.presentation.toolwindow.panel

import emohce.data.repository.BookmarkStore
import emohce.domain.model.BookmarkNode
import emohce.presentation.index.BookmarkIndexService
import emohce.presentation.toolwindow.panel.util.BookmarkTreeUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.tree.DefaultMutableTreeNode

class BookmarkTreeModelBuilderTest {
    @Test
    fun `multi file roots are not expanded unless recorded as expanded`() {
        val firstRoot = BookmarkNode.Group(
            uuid = "root-1",
            name = "Root 1",
            children = listOf(BookmarkNode.Group(uuid = "group-1", name = "Group 1"))
        )
        val secondRoot = BookmarkNode.Group(
            uuid = "root-2",
            name = "Root 2",
            children = listOf(BookmarkNode.Group(uuid = "group-2", name = "Group 2"))
        )
        val superRoot = BookmarkNode.Group(
            uuid = BookmarkStore.SUPER_ROOT_UUID,
            name = BookmarkStore.ROOT_NODE_NAME,
            children = listOf(firstRoot, secondRoot)
        )

        val treeRoot = BookmarkTreeModelBuilder(
            referenceCounts = emptyMap(),
            referenceTargets = emptySet(),
            expandedNodeIds = emptySet(),
            searchQuery = "",
            searchResult = BookmarkIndexService.SearchResult.EMPTY
        ).build(superRoot)
        val firstTreeRoot = treeRoot.getChildAt(0) as DefaultMutableTreeNode
        val secondTreeRoot = treeRoot.getChildAt(1) as DefaultMutableTreeNode

        assertEquals(2, treeRoot.childCount)
        assertTrue(BookmarkTreeUtil.hasPlaceholder(firstTreeRoot))
        assertTrue(BookmarkTreeUtil.hasPlaceholder(secondTreeRoot))
    }

    @Test
    fun `expanded multi file root renders its direct children once`() {
        val child = BookmarkNode.Group(uuid = "group-1", name = "Group 1")
        val firstRoot = BookmarkNode.Group(
            uuid = "root-1",
            name = "Root 1",
            children = listOf(child)
        )
        val secondRoot = BookmarkNode.Group(uuid = "root-2", name = "Root 2")
        val superRoot = BookmarkNode.Group(
            uuid = BookmarkStore.SUPER_ROOT_UUID,
            name = BookmarkStore.ROOT_NODE_NAME,
            children = listOf(firstRoot, secondRoot)
        )

        val treeRoot = BookmarkTreeModelBuilder(
            referenceCounts = emptyMap(),
            referenceTargets = emptySet(),
            expandedNodeIds = setOf(firstRoot.uuid),
            searchQuery = "",
            searchResult = BookmarkIndexService.SearchResult.EMPTY
        ).build(superRoot)
        val firstTreeRoot = treeRoot.getChildAt(0) as DefaultMutableTreeNode
        val firstChild = firstTreeRoot.getChildAt(0) as DefaultMutableTreeNode

        assertEquals(2, treeRoot.childCount)
        assertEquals(1, firstTreeRoot.childCount)
        assertEquals(child.uuid, BookmarkTreeUtil.getNodeView(firstChild)?.node?.uuid)
    }

    @Test
    fun `search query expands matching branch without placeholder`() {
        val child = BookmarkNode.Group(uuid = "group-1", name = "AlphaGroup")
        val firstRoot = BookmarkNode.Group(
            uuid = "root-1",
            name = "Root 1",
            children = listOf(child)
        )
        val superRoot = BookmarkNode.Group(
            uuid = BookmarkStore.SUPER_ROOT_UUID,
            name = BookmarkStore.ROOT_NODE_NAME,
            children = listOf(firstRoot)
        )
        val searchResult = BookmarkIndexService.SearchResult(
            visibleNodeIds = linkedSetOf(firstRoot.uuid, child.uuid),
            directMatchNodeIds = setOf(child.uuid),
            fullSubtreeRootIds = emptySet()
        )

        val treeRoot = BookmarkTreeModelBuilder(
            referenceCounts = emptyMap(),
            referenceTargets = emptySet(),
            expandedNodeIds = setOf(firstRoot.uuid),
            searchQuery = "alpha",
            searchResult = searchResult
        ).build(superRoot)
        val groupNode = treeRoot.getChildAt(0) as DefaultMutableTreeNode

        assertEquals(1, treeRoot.childCount)
        assertEquals(child.uuid, BookmarkTreeUtil.getNodeView(groupNode)?.node?.uuid)
        assertTrue(!BookmarkTreeUtil.hasPlaceholder(groupNode))
    }

    @Test
    fun `search query does not filter file roots`() {
        val child = BookmarkNode.Group(uuid = "group-1", name = "AlphaGroup")
        val firstRoot = BookmarkNode.Group(
            uuid = "root-1",
            name = "Root 1",
            children = listOf(child)
        )
        val secondRoot = BookmarkNode.Group(uuid = "root-2", name = "Root 2")
        val superRoot = BookmarkNode.Group(
            uuid = BookmarkStore.SUPER_ROOT_UUID,
            name = BookmarkStore.ROOT_NODE_NAME,
            children = listOf(firstRoot, secondRoot)
        )
        val searchResult = BookmarkIndexService.SearchResult(
            visibleNodeIds = linkedSetOf(firstRoot.uuid, child.uuid),
            directMatchNodeIds = setOf(child.uuid),
            fullSubtreeRootIds = emptySet()
        )

        val treeRoot = BookmarkTreeModelBuilder(
            referenceCounts = emptyMap(),
            referenceTargets = emptySet(),
            expandedNodeIds = emptySet(),
            searchQuery = "alpha",
            searchResult = searchResult
        ).build(superRoot)

        assertEquals(2, treeRoot.childCount)
    }
}
