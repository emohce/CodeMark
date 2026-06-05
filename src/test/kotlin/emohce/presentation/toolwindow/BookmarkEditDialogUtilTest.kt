package emohce.presentation.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BookmarkEditDialogUtilTest {
    @Test
    fun `display path uses relative path inside project root`() {
        val displayPath = bookmarkDisplayPath(
            "/Users/dev/project/src/Main.kt",
            "/Users/dev/project"
        )

        assertEquals("src/Main.kt", displayPath)
    }

    @Test
    fun `display path keeps absolute path outside project root`() {
        val displayPath = bookmarkDisplayPath(
            "/Users/dev/other/Main.kt",
            "/Users/dev/project"
        )

        assertEquals("/Users/dev/other/Main.kt", displayPath)
    }

    @Test
    fun `storage path resolves relative display path against project root`() {
        val storagePath = bookmarkStoragePath(
            "src/Main.kt",
            "/Users/dev/project"
        )

        assertEquals("/Users/dev/project/src/Main.kt", storagePath)
    }
}
