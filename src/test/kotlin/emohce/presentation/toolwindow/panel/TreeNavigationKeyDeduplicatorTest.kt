package emohce.presentation.toolwindow.panel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.event.KeyEvent

class TreeNavigationKeyDeduplicatorTest {
    private val source = object : Component() {}

    @Test
    fun `same key press event signature is handled once`() {
        val deduplicator = TreeNavigationKeyDeduplicator()
        val first = keyPress(KeyEvent.VK_DOWN, 1000L)
        val duplicate = keyPress(KeyEvent.VK_DOWN, 1000L)

        assertTrue(deduplicator.shouldHandle(first))
        assertFalse(deduplicator.shouldHandle(duplicate))
    }

    @Test
    fun `auto repeat with new timestamp is still handled`() {
        val deduplicator = TreeNavigationKeyDeduplicator()

        assertTrue(deduplicator.shouldHandle(keyPress(KeyEvent.VK_DOWN, 1000L)))
        assertTrue(deduplicator.shouldHandle(keyPress(KeyEvent.VK_DOWN, 1100L)))
    }

    @Test
    fun `duplicate dispatch with adjacent timestamp is handled once`() {
        val deduplicator = TreeNavigationKeyDeduplicator()

        assertTrue(deduplicator.shouldHandle(keyPress(KeyEvent.VK_DOWN, 1000L)))
        assertFalse(deduplicator.shouldHandle(keyPress(KeyEvent.VK_DOWN, 1001L)))
    }

    private fun keyPress(keyCode: Int, whenMillis: Long): KeyEvent {
        return KeyEvent(source, KeyEvent.KEY_PRESSED, whenMillis, 0, keyCode, KeyEvent.CHAR_UNDEFINED)
    }
}
