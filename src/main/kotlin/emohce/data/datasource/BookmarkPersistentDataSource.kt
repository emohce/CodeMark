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

    private fun resolveUndoPath(): Path {
        val basePath = project.basePath ?: System.getProperty("user.dir")
        return Path.of(basePath, ".bookmarkx", "bookmarkx.json.undo")
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

    fun save(state: BookmarkPersistentState, saveUndo: Boolean = true) {
        val path = resolvePath()
        Files.createDirectories(path.parent)
        
        // 保存撤销文件：在保存新状态前，将当前状态保存到撤销文件
        if (saveUndo && Files.exists(path)) {
            try {
                val currentContent = Files.readString(path)
                val undoPath = resolveUndoPath()
                Files.writeString(
                    undoPath,
                    currentContent,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                )
            } catch (e: Exception) {
                // 忽略撤销文件保存失败
            }
        }
        
        // 保存新状态
        val payload = json.encodeToString(state)
        Files.writeString(
            path,
            payload,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
    }

    fun loadUndo(): BookmarkPersistentState? {
        val undoPath = resolveUndoPath()
        if (!Files.exists(undoPath)) return null
        return try {
            val raw = Files.readString(undoPath)
            json.decodeFromString<BookmarkPersistentState>(raw)
        } catch (e: Exception) {
            null
        }
    }

    fun hasUndo(): Boolean {
        return Files.exists(resolveUndoPath())
    }

    private fun migrateStateIfNeeded(
        state: BookmarkPersistentState,
        path: Path
    ): BookmarkPersistentState? {
        val current = BookmarkPersistentState.CURRENT_VERSION
        return when {
            state.version > current -> {
                // 版本不支持，但不创建备份文件
                null
            }
            state.version < current -> {
                // 迁移版本，直接保存新版本
                state.copy(version = current).also { save(it) }
            }
            else -> state
        }
    }

    private fun backupCorruptFile(path: Path) {
        // 不再创建损坏文件的备份
        // 文件损坏时直接返回 null，由系统重新初始化
    }
}
