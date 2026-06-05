package emohce.presentation.toolwindow.panel

import com.intellij.util.ui.JBUI
import emohce.domain.model.BookmarkNode
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.InputMap
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke

internal class BookmarkTreeActions(
    private val tree: JComponent,
    private val callbacks: Callbacks
) {
    data class Callbacks(
        val refresh: () -> Unit,
        val collapseAll: () -> Unit,
        val expandCurrent: () -> Unit,
        val canExpandCurrent: () -> Boolean,
        val createRootFile: () -> Unit,
        val createGroup: () -> Unit,
        val createProcess: () -> Unit,
        val createBookmark: () -> Unit,
        val createDescriptive: () -> Unit,
        val editSelected: () -> Unit,
        val editSelectedBookmarkOrGroup: () -> Unit,
        val moveSelected: () -> Unit,
        val deleteSelected: () -> Unit,
        val addToProcess: () -> Unit,
        val copyToProcess: () -> Unit,
        val setProcessEntry: () -> Unit,
        val navigatePrev: () -> Unit,
        val navigateNext: () -> Unit,
        val moveSibling: (Int) -> Unit,
        val activateSelected: () -> Unit,
        val expandAllNestedUnderGroup: () -> Unit,
        val canExpandAllNestedUnderGroup: () -> Boolean,
        val selectedNode: () -> BookmarkNode?,
        val prepareContextMenu: (MouseEvent?) -> Unit,
        val toggleSearchMode: () -> Unit
    )

    fun createToolbar(): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            border = JBUI.Borders.empty(4, 4, 4, 4)
            isOpaque = false
            add(textButton("刷新", callbacks.refresh))
            add(textButton("收缩全部", callbacks.collapseAll))
            add(textButton("定位当前", callbacks.expandCurrent) { callbacks.canExpandCurrent() })
            add(textButton("新Root", callbacks.createRootFile))
        }
    }

    fun createPopupMenu(): JPopupMenu {
        val menu = JPopupMenu()
        menu.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {
                callbacks.prepareContextMenu(lastContextMenuMouseEvent)
                rebuildPopupMenu(menu)
            }

            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) {
                lastContextMenuMouseEvent = null
            }

            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) {
                lastContextMenuMouseEvent = null
            }
        })
        return menu
    }

    fun onContextMenuMouseEvent(event: MouseEvent) {
        if (event.isPopupTrigger) {
            lastContextMenuMouseEvent = event
        }
    }

    private var lastContextMenuMouseEvent: MouseEvent? = null

    private fun rebuildPopupMenu(menu: JPopupMenu) {
        menu.removeAll()
        when (val node = callbacks.selectedNode()) {
            is BookmarkNode.Bookmark, is BookmarkNode.DescriptiveBookmark -> buildBookmarkLeafMenu(menu)
            else -> buildFullMenu(menu)
        }
    }

    private fun buildBookmarkLeafMenu(menu: JPopupMenu) {
        menu.add(actionItem("上一个书签", callbacks.navigatePrev))
        menu.add(actionItem("下一个书签", callbacks.navigateNext))
        menu.addSeparator()
        menu.add(actionItem("编辑", callbacks.editSelected))
        menu.add(actionItem("移动", callbacks.moveSelected))
        menu.addSeparator()
        menu.add(actionItem("删除", callbacks.deleteSelected))
    }

    private fun buildFullMenu(menu: JPopupMenu) {
        menu.add(actionItem("新建组", callbacks.createGroup))
        menu.addSeparator()
        menu.add(actionItem("编辑", callbacks.editSelected))
        menu.add(actionItem("移动", callbacks.moveSelected))
        menu.addSeparator()
        menu.add(actionItem("删除", callbacks.deleteSelected))
        menu.addSeparator()
        menu.add(actionItem("上一个书签", callbacks.navigatePrev))
        menu.add(actionItem("下一个书签", callbacks.navigateNext))
        menu.addSeparator()
        menu.add(actionItem("刷新", callbacks.refresh))
        val jumpToCurrentItem = actionItem("定位当前", callbacks.expandCurrent)
        menu.add(jumpToCurrentItem)
        val expandAllNestedItem = actionItem("展开所有嵌套子项", callbacks.expandAllNestedUnderGroup)
        menu.add(expandAllNestedItem)
        jumpToCurrentItem.isEnabled = callbacks.canExpandCurrent()
        val showExpandAllNested = callbacks.canExpandAllNestedUnderGroup()
        expandAllNestedItem.isVisible = showExpandAllNested
        expandAllNestedItem.isEnabled = showExpandAllNested
    }

    fun installShortcuts() {
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), callbacks.deleteSelected)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK), callbacks.createGroup)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.ALT_DOWN_MASK), callbacks.createProcess)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK), callbacks.createBookmark)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK), callbacks.createDescriptive)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.CTRL_DOWN_MASK), callbacks.editSelected)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), callbacks.editSelectedBookmarkOrGroup)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_M, KeyEvent.CTRL_DOWN_MASK), callbacks.moveSelected)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_T, KeyEvent.CTRL_DOWN_MASK), callbacks.addToProcess)
        bindShortcut(
            KeyStroke.getKeyStroke(KeyEvent.VK_T, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK),
            callbacks.copyToProcess
        )
        bindShortcut(
            KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK),
            callbacks.setProcessEntry
        )
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK), callbacks.refresh)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_UP, KeyEvent.ALT_DOWN_MASK)) { callbacks.moveSibling(-1) }
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK)) { callbacks.moveSibling(1) }
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), callbacks.activateSelected)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), callbacks.toggleSearchMode)
        bindShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.META_DOWN_MASK), callbacks.toggleSearchMode)
    }

    private fun textButton(text: String, handler: () -> Unit, isEnabled: () -> Boolean = { true }): JButton {
        return JButton(text).apply {
            isFocusable = false
            margin = JBUI.insets(2, 8)
            addActionListener {
                if (isEnabled()) handler()
            }
            addChangeListener {
                this.isEnabled = isEnabled()
            }
            this.isEnabled = isEnabled()
        }
    }

    private fun actionItem(label: String, handler: () -> Unit): JMenuItem {
        return JMenuItem(label).apply {
            addActionListener { handler() }
        }
    }

    private fun bindShortcut(stroke: KeyStroke, action: () -> Unit) {
        val key = stroke.toString()
        val inputMap: InputMap = tree.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        inputMap.put(stroke, key)
        tree.actionMap.put(key, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                action()
            }
        })
    }
}
