package emohce.presentation.action

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SelectionReferenceFormatterTest {
    @Test
    fun `formats selected line range with file name and one-based lines`() {
        assertEquals(
            "@BookmarkTreeActions.kt#L157-159",
            formatSelectionReference("BookmarkTreeActions.kt", startLine = 156, endLine = 158)
        )
    }

    @Test
    fun `uses project relative path for selected reference target`() {
        assertEquals(
            "EzCodeMark/src/main/kotlin/emohce/presentation/toolwindow/panel/BookmarkTreeActions.kt",
            selectionReferenceTarget(
                projectBasePaths = listOf("/Users/gdkmjd/work/czz/EzCodeMark"),
                filePath = "/Users/gdkmjd/work/czz/EzCodeMark/src/main/kotlin/emohce/presentation/toolwindow/panel/BookmarkTreeActions.kt",
                fileName = "BookmarkTreeActions.kt"
            )
        )
    }

    @Test
    fun `uses first matching project base path when primary path is missing`() {
        assertEquals(
            "EzCodeMark/vibe/rules/project.md",
            selectionReferenceTarget(
                projectBasePaths = listOf(null, "/Users/gdkmjd/work/czz/EzCodeMark"),
                filePath = "/Users/gdkmjd/work/czz/EzCodeMark/vibe/rules/project.md",
                fileName = "project.md"
            )
        )
    }

    @Test
    fun `uses relative path from project root path`() {
        assertEquals(
            "EzCodeMark/src/main/resources/META-INF/plugin.xml",
            selectionReferenceTargetFromProjectRoot(
                projectRootPath = "/Users/gdkmjd/work/czz/EzCodeMark",
                filePath = "/Users/gdkmjd/work/czz/EzCodeMark/src/main/resources/META-INF/plugin.xml",
                fileName = "plugin.xml"
            )
        )
    }

    @Test
    fun `includes project root name for root level file`() {
        assertEquals(
            "EzCodeMark/AGENTS.md",
            selectionReferenceTargetFromProjectRoot(
                projectRootPath = "/Users/gdkmjd/work/czz/EzCodeMark",
                filePath = "/Users/gdkmjd/work/czz/EzCodeMark/AGENTS.md",
                fileName = "AGENTS.md"
            )
        )
    }

    @Test
    fun `uses previous offset when selection ends at next line start`() {
        val text = "first\nsecond\nthird"

        assertEquals(
            1,
            selectedEndLine(text, selectionStart = 0, selectionEnd = "first\nsecond\n".length)
        )
    }
}
