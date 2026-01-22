package emohce.domain.model

data class ProcessProgress(
    val processName: String,
    val currentStep: Int,
    val totalSteps: Int,
    val currentBookmark: BookmarkNode.Bookmark
)
