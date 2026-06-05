package emohce.presentation.toolwindow.panel.render

import com.intellij.ui.SimpleTextAttributes
import emohce.domain.model.BookmarkNode
import emohce.presentation.toolwindow.panel.BookmarkPanel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class BookmarkTreeCellRendererTest {
    @Test
    fun `tree suffix renders inline markdown without marker characters`() {
        val renderer = renderNodeView(
            BookmarkPanel.NodeView(
                BookmarkNode.Bookmark(
                    uuid = "bookmark",
                    name = "Bookmark",
                    filePath = "src/main/App.kt",
                    line = 0
                ),
                referenceCount = 2,
                isReferencedTarget = true,
                pathLabel = "Bookmark"
            )
        )

        assertEquals("Bookmark [refs:2, ref]", renderer.getCharSequence(false).toString())
        assertFalse(renderer.getCharSequence(false).contains('*'))
        assertFalse(renderer.getCharSequence(false).contains('`'))

        val fragments = renderer.fragments()
        assertTrue(fragments.any { it.text == "refs:" && it.style and SimpleTextAttributes.STYLE_ITALIC != 0 })
        assertTrue(fragments.any { it.text == "2" && it.style == SimpleTextAttributes.STYLE_PLAIN })
        assertTrue(fragments.any { it.text == "ref" && it.style and SimpleTextAttributes.STYLE_ITALIC != 0 })
    }

    @Test
    fun `markdown suffix parser handles italic marker at text end`() {
        val renderer = BookmarkTreeCellRenderer()
        val method = BookmarkTreeCellRenderer::class.java.getDeclaredMethod(
            "appendMarkdownText",
            String::class.java,
            java.awt.Color::class.java
        )
        method.isAccessible = true

        method.invoke(renderer, "*ref*", java.awt.Color.GRAY)

        assertEquals("ref", renderer.getCharSequence(false).toString())
        assertTrue(renderer.fragments().single().style and SimpleTextAttributes.STYLE_ITALIC != 0)
    }

    private fun renderNodeView(nodeView: BookmarkPanel.NodeView): BookmarkTreeCellRenderer {
        val renderer = BookmarkTreeCellRenderer()
        val treeNode = DefaultMutableTreeNode(nodeView)
        renderer.getTreeCellRendererComponent(JTree(treeNode), treeNode, false, false, true, 0, false)
        return renderer
    }

    private fun BookmarkTreeCellRenderer.fragments(): List<Fragment> {
        val iterator = iterator()
        val fragments = mutableListOf<Fragment>()
        while (iterator.hasNext()) {
            val text = iterator.next()
            fragments.add(Fragment(text, iterator.textAttributes.style))
        }
        return fragments
    }

    private data class Fragment(val text: String, val style: Int)
}
