package emohce.presentation.toolwindow.panel.util

import emohce.domain.model.BookmarkNode
import emohce.presentation.toolwindow.panel.BookmarkPanel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class BookmarkTreeUtilTest {
    @Test
    fun `collapse visible rows collapses expanded descendants`() {
        val root = DefaultMutableTreeNode("root")
        val group = DefaultMutableTreeNode("group")
        val child = DefaultMutableTreeNode("child")
        val leaf = DefaultMutableTreeNode("leaf")
        root.add(group)
        group.add(child)
        child.add(leaf)

        val tree = JTree(root).apply {
            isRootVisible = false
        }
        val groupPath = TreePath(group.path)
        val childPath = TreePath(child.path)
        tree.expandPath(groupPath)
        tree.expandPath(childPath)

        BookmarkTreeUtil.collapseVisibleRows(tree)

        assertFalse(tree.isExpanded(childPath))
        assertFalse(tree.isExpanded(groupPath))
    }

    @Test
    fun `disclosure click resolves row path only inside row and before node bounds`() {
        val root = DefaultMutableTreeNode("root")
        val group = DefaultMutableTreeNode("group")
        root.add(group)

        val tree = JTree(root).apply {
            isRootVisible = false
            setSize(240, 120)
            doLayout()
        }
        val groupPath = TreePath(group.path)
        val bounds = tree.getPathBounds(groupPath)

        val disclosurePath = BookmarkTreeUtil.pathForDisclosureClick(
            tree,
            bounds.x - 1,
            bounds.y + bounds.height / 2
        )
        val blankPath = BookmarkTreeUtil.pathForDisclosureClick(
            tree,
            bounds.x - 1,
            bounds.y + bounds.height + 20
        )

        assertEquals(groupPath, disclosurePath)
        assertNull(blankPath)
    }

    @Test
    fun `node icon click resolves only the leading node bounds area`() {
        val root = DefaultMutableTreeNode("root")
        val group = DefaultMutableTreeNode("group")
        root.add(group)

        val tree = JTree(root).apply {
            isRootVisible = false
            setSize(240, 120)
            doLayout()
        }
        val groupPath = TreePath(group.path)
        val bounds = tree.getPathBounds(groupPath)

        val iconPath = BookmarkTreeUtil.pathForNodeIconClick(
            tree,
            bounds.x + 4,
            bounds.y + bounds.height / 2
        )
        val textPath = BookmarkTreeUtil.pathForNodeIconClick(
            tree,
            bounds.x + 40,
            bounds.y + bounds.height / 2
        )

        assertEquals(groupPath, iconPath)
        assertNull(textPath)
    }

    @Test
    fun `collapse selected path keeps selection on current node`() {
        val root = DefaultMutableTreeNode("root")
        val group = DefaultMutableTreeNode("group")
        val child = DefaultMutableTreeNode("child")
        root.add(group)
        group.add(child)

        val tree = JTree(root)
        val groupPath = TreePath(group.path)
        tree.expandPath(groupPath)
        tree.selectionPath = groupPath

        BookmarkTreeUtil.collapseSelectedPath(tree)

        assertFalse(tree.isExpanded(groupPath))
        assertSame(groupPath, tree.selectionPath)
    }

    @Test
    fun `collapse for navigation collapses expanded selected group without moving selection`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val group = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "g", name = "group"), 0, false, "group")
        )
        group.add(
            DefaultMutableTreeNode(
                BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "b", name = "b", filePath = "a.kt", line = 1), 0, false, "b")
            )
        )
        hiddenRoot.add(group)

        val tree = JTree(hiddenRoot).apply { isRootVisible = false }
        val groupPath = TreePath(arrayOf(hiddenRoot, group))
        tree.expandPath(groupPath)
        tree.selectionPath = groupPath

        BookmarkTreeUtil.collapseForNavigation(tree)

        assertFalse(tree.isExpanded(groupPath))
        assertEquals(groupPath, tree.selectionPath)
    }

    @Test
    fun `collapse for navigation collapses parent when selected group is collapsed`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val parent = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "p", name = "parent"), 0, false, "parent")
        )
        val group = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "g", name = "group"), 0, false, "group")
        )
        group.add(
            DefaultMutableTreeNode(
                BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "b", name = "b", filePath = "a.kt", line = 1), 0, false, "b")
            )
        )
        parent.add(group)
        hiddenRoot.add(parent)

        val tree = JTree(hiddenRoot).apply { isRootVisible = false }
        val parentPath = TreePath(arrayOf(hiddenRoot, parent))
        val groupPath = TreePath(arrayOf(hiddenRoot, parent, group))
        tree.expandPath(parentPath)
        tree.collapsePath(groupPath)
        tree.selectionPath = groupPath

        BookmarkTreeUtil.collapseForNavigation(tree)

        assertFalse(tree.isExpanded(parentPath))
        assertEquals(parentPath, tree.selectionPath)
    }

    @Test
    fun `collapse path keeping selection does not jump when child was selected before collapse`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val group = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "g", name = "group"), 0, false, "group")
        )
        val bookmark = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "b", name = "b", filePath = "a.kt", line = 1), 0, false, "b")
        )
        group.add(bookmark)
        hiddenRoot.add(group)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 120)
            doLayout()
        }
        val groupPath = TreePath(arrayOf(hiddenRoot, group))
        val bookmarkPath = TreePath(arrayOf(hiddenRoot, group, bookmark))
        tree.expandPath(groupPath)
        tree.selectionPath = bookmarkPath

        BookmarkTreeUtil.collapseForNavigation(tree)

        assertFalse(tree.isExpanded(groupPath))
        assertEquals(groupPath, tree.selectionPath)
    }

    @Test
    fun `collapse for navigation collapses parent group when bookmark selected`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val group = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "g", name = "group"), 0, false, "group")
        )
        val bookmark = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "b", name = "b", filePath = "a.kt", line = 1), 0, false, "b")
        )
        group.add(bookmark)
        hiddenRoot.add(group)

        val tree = JTree(hiddenRoot).apply { isRootVisible = false }
        val groupPath = TreePath(arrayOf(hiddenRoot, group))
        val bookmarkPath = TreePath(arrayOf(hiddenRoot, group, bookmark))
        tree.expandPath(groupPath)
        tree.selectionPath = bookmarkPath

        BookmarkTreeUtil.collapseForNavigation(tree)

        assertFalse(tree.isExpanded(groupPath))
        assertEquals(groupPath, tree.selectionPath)
    }

    @Test
    fun `expand selected path keeps selection on current node`() {
        val root = DefaultMutableTreeNode("root")
        val group = DefaultMutableTreeNode("group")
        val child = DefaultMutableTreeNode("child")
        root.add(group)
        group.add(child)

        val tree = JTree(root)
        val groupPath = TreePath(group.path)
        tree.collapsePath(groupPath)
        tree.selectionPath = groupPath

        BookmarkTreeUtil.expandSelectedPath(tree)

        assertEquals(groupPath, tree.selectionPath)
    }

    @Test
    fun `toggle path alternates expanded state and keeps selection`() {
        val root = DefaultMutableTreeNode("root")
        val group = DefaultMutableTreeNode("group")
        val child = DefaultMutableTreeNode("child")
        root.add(group)
        group.add(child)

        val tree = JTree(root)
        val groupPath = TreePath(group.path)
        tree.collapsePath(groupPath)
        tree.selectionPath = groupPath

        BookmarkTreeUtil.togglePathExpansion(tree, groupPath)
        assertEquals(groupPath, tree.selectionPath)
        assertTrue(tree.isExpanded(groupPath))

        BookmarkTreeUtil.togglePathExpansion(tree, groupPath)
        assertEquals(groupPath, tree.selectionPath)
        assertFalse(tree.isExpanded(groupPath))
    }

    @Test
    fun `move selection by visible row crosses sibling roots`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val firstRoot = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "root-1", name = "root-1"), 0, false, "root-1")
        )
        val secondRoot = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "root-2", name = "root-2"), 0, false, "root-2")
        )
        hiddenRoot.add(firstRoot)
        hiddenRoot.add(secondRoot)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
            selectionPath = TreePath(arrayOf(hiddenRoot, firstRoot))
        }
        val secondRootPath = TreePath(arrayOf(hiddenRoot, secondRoot))

        BookmarkTreeUtil.moveSelectionByVisibleRow(tree, 1)

        assertEquals(secondRootPath, tree.selectionPath)
    }

    @Test
    fun `move selection by visible row enters expanded child before next root`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val firstRoot = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "root-1", name = "root-1"), 0, false, "root-1")
        )
        val child = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "child", name = "child"), 0, false, "child")
        )
        val secondRoot = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "root-2", name = "root-2"), 0, false, "root-2")
        )
        firstRoot.add(child)
        hiddenRoot.add(firstRoot)
        hiddenRoot.add(secondRoot)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
        }
        val firstRootPath = TreePath(arrayOf(hiddenRoot, firstRoot))
        val childPath = TreePath(arrayOf(hiddenRoot, firstRoot, child))
        tree.expandPath(firstRootPath)
        tree.selectionPath = firstRootPath

        BookmarkTreeUtil.moveSelectionByVisibleRow(tree, 1)

        assertEquals(childPath, tree.selectionPath)
    }

    @Test
    fun `non search move down steps exactly one sibling row`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val first = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "a", name = "A"), 0, false, "A")
        )
        val second = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "b", name = "B"), 0, false, "B")
        )
        val third = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "c", name = "C"), 0, false, "C")
        )
        hiddenRoot.add(first)
        hiddenRoot.add(second)
        hiddenRoot.add(third)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
            selectionPath = TreePath(arrayOf(hiddenRoot, first))
        }
        val secondPath = TreePath(arrayOf(hiddenRoot, second))

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1, null)

        assertEquals(secondPath, tree.selectionPath)
        assertEquals(1, tree.getRowForPath(secondPath))
    }

    @Test
    fun `lazy load move down from collapsed top level root jumps to next file root`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val firstRoot = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "root-1", name = "1234"), 0, false, "1234")
        )
        firstRoot.add(BookmarkTreeUtil.createPlaceholderNode())
        val secondRoot = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "root-2", name = "CodeMarks"), 0, false, "CodeMarks")
        )
        hiddenRoot.add(firstRoot)
        hiddenRoot.add(secondRoot)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
            selectionPath = TreePath(arrayOf(hiddenRoot, firstRoot))
        }
        val secondRootPath = TreePath(arrayOf(hiddenRoot, secondRoot))

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1)

        assertEquals(secondRootPath, tree.selectionPath)
    }

    @Test
    fun `lazy load move down does not expand single collapsed root`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val domainGroup = BookmarkNode.Group(uuid = "g", name = "group")
        val group = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(domainGroup, 0, false, "group")
        )
        group.add(BookmarkTreeUtil.createPlaceholderNode())
        hiddenRoot.add(group)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 120)
            doLayout()
            selectionPath = TreePath(group.path)
        }
        val groupPath = TreePath(arrayOf(hiddenRoot, group))

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1)

        assertEquals(groupPath, tree.selectionPath)
        assertFalse(tree.isExpanded(groupPath))
    }

    @Test
    fun `lazy load move down from nested group goes to next visible row not into placeholder`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val firstRoot = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "root-1", name = "1234"), 0, false, "1234")
        )
        val nestedGroup = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "group-1", name = "New Group"), 0, false, "New Group")
        )
        nestedGroup.add(BookmarkTreeUtil.createPlaceholderNode())
        firstRoot.add(nestedGroup)
        val secondRoot = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "root-2", name = "CodeMarks"), 0, false, "CodeMarks")
        )
        hiddenRoot.add(firstRoot)
        hiddenRoot.add(secondRoot)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
        }
        val firstRootPath = TreePath(arrayOf(hiddenRoot, firstRoot))
        val nestedPath = TreePath(arrayOf(hiddenRoot, firstRoot, nestedGroup))
        val secondRootPath = TreePath(arrayOf(hiddenRoot, secondRoot))
        tree.expandPath(firstRootPath)
        tree.selectionPath = nestedPath

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1)

        assertEquals(secondRootPath, tree.selectionPath)
    }

    @Test
    fun `multi file root down up and right expand stay separated`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val firstRoot = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "root-1", name = "1234"), 0, false, "1234")
        )
        firstRoot.add(BookmarkTreeUtil.createPlaceholderNode())
        val secondRoot = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "root-2", name = "CodeMarks"), 0, false, "CodeMarks")
        )
        hiddenRoot.add(firstRoot)
        hiddenRoot.add(secondRoot)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
            selectionPath = TreePath(arrayOf(hiddenRoot, firstRoot))
        }
        val firstRootPath = TreePath(arrayOf(hiddenRoot, firstRoot))
        val secondRootPath = TreePath(arrayOf(hiddenRoot, secondRoot))
        val child = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "group-1", name = "New Group"), 0, false, "New Group")
        )
        val childPath = TreePath(arrayOf(hiddenRoot, firstRoot, child))

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1)
        assertEquals(secondRootPath, tree.selectionPath)

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, -1)
        assertEquals(firstRootPath, tree.selectionPath)

        BookmarkTreeUtil.expandForNavigation(tree) { parent, _ ->
            parent.removeAllChildren()
            parent.add(child)
        }
        assertEquals(childPath, tree.selectionPath)
        assertTrue(tree.isExpanded(firstRootPath))

        BookmarkTreeUtil.collapseForNavigation(tree)
        assertEquals(firstRootPath, tree.selectionPath)
        assertFalse(tree.isExpanded(firstRootPath))

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1)
        assertEquals(secondRootPath, tree.selectionPath)
    }

    @Test
    fun `search move down skips group with no match in subtree`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val firstMatch = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "match-1", name = "First"), 0, false, "First")
        )
        val gap = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "gap", name = "Gap"), 0, false, "Gap")
        )
        val secondMatch = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "match-2", name = "Second"), 0, false, "Second")
        )
        hiddenRoot.add(firstMatch)
        hiddenRoot.add(gap)
        hiddenRoot.add(secondMatch)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
            selectionPath = TreePath(arrayOf(hiddenRoot, firstMatch))
        }
        val secondPath = TreePath(arrayOf(hiddenRoot, secondMatch))
        val searchRelevantIds = setOf("match-1", "match-2")

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1, searchRelevantIds)

        assertEquals(secondPath, tree.selectionPath)
    }

    @Test
    fun `search move down from non match row selects next relevant node`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val gap = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "gap", name = "Gap"), 0, false, "Gap")
        )
        val match = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "match-1", name = "Match"), 0, false, "Match")
        )
        hiddenRoot.add(gap)
        hiddenRoot.add(match)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
            selectionPath = TreePath(arrayOf(hiddenRoot, gap))
        }
        val matchPath = TreePath(arrayOf(hiddenRoot, match))

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1, setOf("match-1"))

        assertEquals(matchPath, tree.selectionPath)
    }

    @Test
    fun `visible row move matches cleared search behavior when rows are adjacent`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val first = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "first", name = "First"), 0, false, "First")
        )
        val adjacent = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "adjacent", name = "Adjacent", filePath = "a.kt", line = 1), 0, false, "Adjacent")
        )
        val laterMatch = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "later", name = "Later", filePath = "a.kt", line = 2), 0, false, "Later")
        )
        hiddenRoot.add(first)
        hiddenRoot.add(adjacent)
        hiddenRoot.add(laterMatch)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
            selectionPath = TreePath(arrayOf(hiddenRoot, first))
        }
        val adjacentPath = TreePath(arrayOf(hiddenRoot, adjacent))

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1)

        assertEquals(adjacentPath, tree.selectionPath)
    }

    @Test
    fun `search move down selects collapsed parent when child matches`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val parent = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "parent", name = "Parent"), 0, false, "Parent")
        )
        val child = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "child", name = "Hit", filePath = "a.kt", line = 1), 0, false, "Hit")
        )
        val after = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "after", name = "After"), 0, false, "After")
        )
        parent.add(child)
        hiddenRoot.add(parent)
        hiddenRoot.add(after)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
        }
        val parentPath = TreePath(arrayOf(hiddenRoot, parent))
        val afterPath = TreePath(arrayOf(hiddenRoot, after))
        tree.collapsePath(parentPath)
        tree.selectionPath = afterPath
        val searchRelevantIds = setOf("parent", "child")

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, -1, searchRelevantIds)

        assertEquals(parentPath, tree.selectionPath)
        assertFalse(tree.isExpanded(parentPath))
    }

    @Test
    fun `search move down selects group when group name matches`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val group = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "g1", name = "AlphaGroup"), 0, false, "AlphaGroup")
        )
        val bookmark = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "b1", name = "Other", filePath = "a.kt", line = 1), 0, false, "Other")
        )
        hiddenRoot.add(group)
        hiddenRoot.add(bookmark)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
            selectionPath = TreePath(arrayOf(hiddenRoot, bookmark))
        }
        val groupPath = TreePath(arrayOf(hiddenRoot, group))

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, -1, setOf("g1"))

        assertEquals(groupPath, tree.selectionPath)
    }

    @Test
    fun `lazy load move up selects collapsed group row without expanding`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val collapsedGroup = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "group-hidden", name = "Hidden"), 0, false, "Hidden")
        )
        val child = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "child-inside", name = "Inside"), 0, false, "Inside")
        )
        val afterGroup = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "after", name = "After"), 0, false, "After")
        )
        collapsedGroup.add(child)
        hiddenRoot.add(collapsedGroup)
        hiddenRoot.add(afterGroup)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
        }
        val collapsedPath = TreePath(arrayOf(hiddenRoot, collapsedGroup))
        val afterPath = TreePath(arrayOf(hiddenRoot, afterGroup))
        tree.collapsePath(collapsedPath)
        tree.selectionPath = afterPath

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, -1)

        assertEquals(collapsedPath, tree.selectionPath)
        assertFalse(tree.isExpanded(collapsedPath))
    }

    @Test
    fun `expand for navigation populates placeholder and selects first child`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val domainGroup = BookmarkNode.Group(uuid = "g", name = "group")
        val group = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(domainGroup, 0, false, "group")
        )
        group.add(BookmarkTreeUtil.createPlaceholderNode())
        hiddenRoot.add(group)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 120)
            doLayout()
            selectionPath = TreePath(group.path)
        }
        val child = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "c", name = "child"), 0, false, "child")
        )
        val expectedPath = TreePath(arrayOf(hiddenRoot, group, child))

        BookmarkTreeUtil.expandForNavigation(tree) { parent, _ ->
            parent.removeAllChildren()
            parent.add(child)
        }

        assertEquals(expectedPath, tree.selectionPath)
    }

    @Test
    fun `search move down reaches last direct match on final visible row`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val codemarks = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "codemarks", name = "CodeMarks"), 0, false, "CodeMarks")
        )
        val first = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "match-1", name = "2.21", filePath = "a.kt", line = 1), 0, false, "2.21")
        )
        val group = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "group", name = "testGroup"), 0, false, "testGroup")
        )
        val gap1 = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "gap-1", name = "2.1", filePath = "a.kt", line = 2), 0, false, "2.1")
        )
        val second = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "match-2", name = "2.2", filePath = "a.kt", line = 3), 0, false, "2.2")
        )
        val last = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "match-3", name = "2.22", filePath = "a.kt", line = 4), 0, false, "2.22")
        )
        val gap2 = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "gap-2", name = "3.333", filePath = "a.kt", line = 5), 0, false, "3.333")
        )
        val gap3 = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Bookmark(uuid = "gap-3", name = "2.3", filePath = "a.kt", line = 6), 0, false, "2.3")
        )
        group.add(gap1)
        group.add(second)
        group.add(last)
        group.add(gap2)
        group.add(gap3)
        codemarks.add(first)
        codemarks.add(group)
        hiddenRoot.add(codemarks)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(320, 240)
            doLayout()
        }
        val codemarksPath = TreePath(arrayOf(hiddenRoot, codemarks))
        val secondPath = TreePath(arrayOf(hiddenRoot, codemarks, group, second))
        val lastPath = TreePath(arrayOf(hiddenRoot, codemarks, group, last))
        tree.expandPath(codemarksPath)
        tree.selectionPath = secondPath

        val searchStops = setOf("codemarks", "group", "match-1", "match-2", "match-3")
        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, 1, searchStops)

        assertEquals(lastPath, tree.selectionPath)
    }

    @Test
    fun `search move up skips group with no match in subtree`() {
        val hiddenRoot = DefaultMutableTreeNode("hidden-root")
        val firstMatch = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "match-1", name = "First"), 0, false, "First")
        )
        val gap = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "gap", name = "Gap"), 0, false, "Gap")
        )
        val secondMatch = DefaultMutableTreeNode(
            BookmarkPanel.NodeView(BookmarkNode.Group(uuid = "match-2", name = "Second"), 0, false, "Second")
        )
        hiddenRoot.add(firstMatch)
        hiddenRoot.add(gap)
        hiddenRoot.add(secondMatch)

        val tree = JTree(hiddenRoot).apply {
            isRootVisible = false
            setSize(240, 160)
            doLayout()
            selectionPath = TreePath(arrayOf(hiddenRoot, secondMatch))
        }
        val firstPath = TreePath(arrayOf(hiddenRoot, firstMatch))

        BookmarkTreeUtil.moveSelectionConsideringLazyLoad(tree, -1, setOf("match-1", "match-2"))

        assertEquals(firstPath, tree.selectionPath)
    }
}
