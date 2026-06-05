package emohce.presentation.toolwindow.panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

class TreeSearchModeTest {
    @Test
    fun `ctrl f starts full search when search is inactive`() {
        assertEquals(
            TreeSearchMode.FULL,
            nextTreeSearchModeOnFind(currentMode = TreeSearchMode.VISIBLE_ONLY, isSearchActive = false)
        )
    }

    @Test
    fun `ctrl f remains full when invoked repeatedly before query input`() {
        assertEquals(TreeSearchMode.FULL, treeSearchModeBeforeQueryInput())
    }

    @Test
    fun `ctrl f toggles full search to visible only while searching`() {
        assertEquals(
            TreeSearchMode.VISIBLE_ONLY,
            nextTreeSearchModeOnFind(currentMode = TreeSearchMode.FULL, isSearchActive = true)
        )
    }

    @Test
    fun `ctrl f toggles visible only search to full while searching`() {
        assertEquals(
            TreeSearchMode.FULL,
            nextTreeSearchModeOnFind(currentMode = TreeSearchMode.VISIBLE_ONLY, isSearchActive = true)
        )
    }

    @Test
    fun `search mode badge text stays compact`() {
        assertEquals("Full", treeSearchModeBadgeText(TreeSearchMode.FULL))
        assertEquals("Visible", treeSearchModeBadgeText(TreeSearchMode.VISIBLE_ONLY))
    }

    @Test
    fun `search exits to visible so direct typing remains visible by default`() {
        assertEquals(TreeSearchMode.VISIBLE_ONLY, treeSearchModeAfterExit())
    }

    @Test
    fun `pending search mode does not show mode badge before query input`() {
        assertEquals(
            false,
            shouldShowTreeSearchModeBadge(pendingSearchMode = true, hasQuery = false, isPopupActive = false)
        )
    }

    @Test
    fun `mode badge shows after query input or popup display`() {
        assertEquals(
            true,
            shouldShowTreeSearchModeBadge(pendingSearchMode = true, hasQuery = true, isPopupActive = false)
        )
        assertEquals(
            true,
            shouldShowTreeSearchModeBadge(pendingSearchMode = false, hasQuery = false, isPopupActive = true)
        )
    }

    @Test
    fun `first query after ctrl f is forced to full mode`() {
        assertEquals(
            TreeSearchMode.FULL,
            resolveTreeSearchModeForPrefix(currentMode = TreeSearchMode.VISIBLE_ONLY, forceNextSearchFull = true)
        )
    }

    @Test
    fun `find shortcut can be handled after recent panel interaction even if focus context is stale`() {
        assertEquals(
            true,
            shouldHandleTreeFindShortcut(
                hasFocusContext = false,
                isToolWindowActive = false,
                hasRecentPanelInteraction = true
            )
        )
    }

    @Test
    fun `find shortcut strokes include control and command f`() {
        assertEquals(
            setOf(
                KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK),
                KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.META_DOWN_MASK)
            ),
            treeFindShortcutStrokes().toSet()
        )
    }
}
