package emohce.data.datasource

import com.intellij.openapi.project.Project
import emohce.data.persistence.BookmarkTreeUiState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class BookmarkTreeUiDataSource(private val project: Project) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun load(): BookmarkTreeUiState? {
        val path = resolvePath() ?: return null
        if (!Files.exists(path)) return null
        return try {
            json.decodeFromString<BookmarkTreeUiState>(Files.readString(path))
        } catch (_: Exception) {
            null
        }
    }

    fun save(state: BookmarkTreeUiState) {
        val path = resolvePath() ?: return
        Files.createDirectories(path.parent)
        val payload = json.encodeToString(state)
        Files.writeString(
            path,
            payload,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    private fun resolvePath(): Path? {
        val basePath = project.basePath ?: return null
        return treeUiPath(basePath)
    }

    companion object {
        const val TREE_UI_FILE_NAME = "tree-ui.json"

        fun treeUiPath(basePath: String): Path =
            BookmarkPersistentDataSource.dataDirPath(basePath).resolve(TREE_UI_FILE_NAME)
    }
}
