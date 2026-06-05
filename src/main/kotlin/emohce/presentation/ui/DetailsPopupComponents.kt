package emohce.presentation.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JPanel

class DetailsPopupPanel(
    private val accent: Color
) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = JBColor(0xF8FAFE, 0x1F2329)
            val border = JBColor(0xCAD3E1, 0x3A414D)
            g2.color = bg
            g2.fillRoundRect(0, 0, width - 1, height - 1, 14, 14)
            g2.color = accent
            g2.fillRoundRect(0, 0, 5, height - 1, 14, 14)
            g2.color = border
            g2.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

class ChipLabel(
    text: String,
    private val accent: Color,
    private val filled: Boolean = true
) : JLabel(text) {
    init {
        font = font.deriveFont(Font.BOLD, (font.size - 1).coerceAtLeast(10).toFloat())
        foreground = if (filled) accent else JBColor.GRAY
        border = JBUI.Borders.empty(4, 8)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val fillAlpha = if (filled) 34 else 0
            g2.color = Color(accent.red, accent.green, accent.blue, fillAlpha)
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.color = Color(accent.red, accent.green, accent.blue, 92)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

class RoundedPanel(
    private val bgColor: Color,
    private val borderColor: Color,
    private val radius: Int
) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = bgColor
            g2.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
            g2.color = borderColor
            g2.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
