package emohce.presentation.editor.inlay

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage

/**
 * TextAttributesKey for remark inlay hints
 * Similar to CodeReadingMarkNotePro's remarkInlay style
 */
object BookmarkInlayTextAttributes {
    val REMARK_INLAY: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "CodeRemarkTour.REMARK_INLAY",
        TextAttributesKey.find("INLAY_DEFAULT")
    )
}
