package emohce.presentation.toolwindow.panel.render

import com.intellij.icons.AllIcons
import emohce.domain.model.BookmarkNode
import javax.swing.Icon

object NodeIcons {
    val bookmark: Icon = AllIcons.Nodes.Bookmark
    val group: Icon = AllIcons.Nodes.Folder
    val process: Icon = AllIcons.Actions.Execute
    val note: Icon = AllIcons.Actions.Edit
    val reference: Icon = AllIcons.Actions.Find

    fun iconFor(node: BookmarkNode, isReferencedTarget: Boolean): Icon {
        return when (node) {
            is BookmarkNode.Bookmark -> if (isReferencedTarget) reference else bookmark
            is BookmarkNode.Group -> group
            is BookmarkNode.Process -> process
            is BookmarkNode.DescriptiveBookmark -> note
        }
    }
}
