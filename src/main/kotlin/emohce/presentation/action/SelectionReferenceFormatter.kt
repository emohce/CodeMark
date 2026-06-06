package emohce.presentation.action

fun formatSelectionReference(fileName: String, startLine: Int, endLine: Int): String {
    val firstLine = startLine.coerceAtLeast(0) + 1
    val lastLine = endLine.coerceAtLeast(startLine).coerceAtLeast(0) + 1
    return "@$fileName#L$firstLine-$lastLine"
}

fun selectionReferenceTarget(projectBasePaths: List<String?>, filePath: String, fileName: String): String {
    projectBasePaths.forEach { projectRootPath ->
        selectionReferenceTargetFromProjectRoot(projectRootPath, filePath, fileName)
            .takeIf { it != fileName }
            ?.let { return it }
    }
    return fileName
}

fun selectionReferenceTargetFromProjectRoot(projectRootPath: String?, filePath: String, fileName: String): String {
    val normalizedFilePath = filePath.toSystemIndependentPath()
    val normalizedRootPath = projectRootPath?.toSystemIndependentPath()?.trimEnd('/')
    if (!normalizedRootPath.isNullOrBlank()) {
        val rootName = normalizedRootPath.substringAfterLast('/').ifBlank { null }
        if (normalizedFilePath == normalizedRootPath) return rootName ?: fileName
        val rootPrefix = "$normalizedRootPath/"
        if (normalizedFilePath.startsWith(rootPrefix)) {
            val relativePath = normalizedFilePath.removePrefix(rootPrefix)
            return rootName?.let { "$it/$relativePath" } ?: relativePath
        }
    }
    return fileName
}

fun adjustedSelectionEndOffset(selectionStart: Int, selectionEnd: Int): Int {
    return if (selectionEnd > selectionStart) selectionEnd - 1 else selectionEnd
}

fun selectedEndLine(text: String, selectionStart: Int, selectionEnd: Int): Int {
    val adjustedEnd = adjustedSelectionEndOffset(selectionStart, selectionEnd).coerceIn(0, text.length)
    return text.take(adjustedEnd).count { it == '\n' }
}

private fun String.toSystemIndependentPath(): String = replace('\\', '/')
