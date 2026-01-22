package emohce.data.datasource

import com.intellij.openapi.project.Project
import emohce.data.persistence.BookmarkPersistentState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.time.Instant

class BookmarkPersistentDataSource(private val project: Project) {
    private val json = Json {
        prettyPrint = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private fun resolvePath(): Path {
        val basePath = project.basePath ?: System.getProperty("user.dir")
        return Path.of(basePath, ".bookmarkx", "bookmarkx.json")
    }

    fun load(): BookmarkPersistentState? {
        val path = resolvePath()
        if (!Files.exists(path)) return null
        return try {
            val raw = Files.readString(path)
            val decoded = json.decodeFromString<BookmarkPersistentState>(raw)
            migrateStateIfNeeded(decoded, path)
        } catch (e: Exception) {
            backupCorruptFile(path)
            null
        }
    }

    fun save(state: BookmarkPersistentState) {
        val path = resolvePath()
        Files.createDirectories(path.parent)
        if (Files.exists(path)) {
            backupFile(path, "bak-${Instant.now().epochSecond}")
        }
        val payload = json.encodeToString(state)
        Files.writeString(
            path,
            payload,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    private fun migrateStateIfNeeded(
        state: BookmarkPersistentState,
        path: Path
    ): BookmarkPersistentState? {
        val current = BookmarkPersistentState.CURRENT_VERSION
        return when {
            state.version > current -> {
                backupFile(path, "unsupported-${state.version}")
                null
            }
            state.version < current -> {
                backupFile(path, "migrate-${state.version}-to-$current")
                state.copy(version = current).also { save(it) }
            }
            else -> state
        }
    }

    private fun backupCorruptFile(path: Path) {
        try {
            val timestamp = Instant.now().epochSecond
            val backup = path.resolveSibling("${path.fileName}.corrupt-$timestamp")
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // Best-effort backup only.
        }
    }

    private fun backupFile(path: Path, suffix: String) {
        try {
            val backup = path.resolveSibling("${path.fileName}.$suffix")
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // Best-effort backup only.
        }
    }
}
