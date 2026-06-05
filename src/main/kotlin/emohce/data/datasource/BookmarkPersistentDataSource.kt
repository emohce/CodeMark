package emohce.data.datasource

import com.intellij.openapi.project.Project
import emohce.data.persistence.BookmarkPersistentState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.extension

class BookmarkPersistentDataSource(private val project: Project) {
    private val json = Json {
        prettyPrint = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private fun resolvePath(): Path {
        val basePath = project.basePath ?: System.getProperty("user.dir")
        return dataPath(basePath)
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
        saveTo(path, state)
    }

    /** 从指定路径加载 BookmarkPersistentState */
    fun loadFrom(path: Path): BookmarkPersistentState? {
        if (!Files.exists(path)) return null
        return try {
            val raw = Files.readString(path)
            val decoded = json.decodeFromString<BookmarkPersistentState>(raw)
            migrateStateIfNeeded(decoded, path)
        } catch (e: Exception) {
            null
        }
    }

    /** 保存 BookmarkPersistentState 到指定路径 */
    fun saveTo(path: Path, state: BookmarkPersistentState) {
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
                // 执行版本迁移：v1 (nested root) -> v2 (flat nodes/children/roots)
                migrateToCurrentVersion(state)
            }
            else -> state
        }
    }

    private fun migrateToCurrentVersion(state: BookmarkPersistentState): BookmarkPersistentState {
        // v1 格式使用 root 字段（嵌套结构），v2 使用 nodes/children/roots（扁平结构）
        val rootData = state.root
        return if (rootData != null && state.nodes.isEmpty()) {
            // 从 v1 格式迁移到 v2
            BookmarkPersistentState.fromRoot(rootData, state.references)
        } else {
            // 已经是 v2 或更高格式，只更新版本号
            state.copy(version = BookmarkPersistentState.CURRENT_VERSION)
        }
    }

    private fun backupCorruptFile(path: Path) {
        // 不再创建损坏文件的备份
        // 文件损坏时直接返回 null，由系统重新初始化
    }

    companion object {
        const val DATA_DIRECTORY = ".codemark"
        const val DATA_FILE_NAME = "codemark.json"

        fun dataPath(basePath: String): Path = Path.of(basePath, DATA_DIRECTORY, DATA_FILE_NAME)

        /** .codemark 目录路径 */
        fun dataDirPath(basePath: String): Path = Path.of(basePath, DATA_DIRECTORY)

        /** 扫描 .codemark/ 目录下所有 *.json 根文件（排除非数据文件） */
        fun listRootFiles(basePath: String): List<Path> {
            val dir = dataDirPath(basePath)
            if (!Files.exists(dir) || !Files.isDirectory(dir)) return emptyList()
            return Files.list(dir).use { stream ->
                stream.filter { it.extension == "json" }
                    .sorted()
                    .toList()
            }
        }

        /** 创建新的根文件，返回文件路径。文件名规则：去空格、小写、.json 后缀 */
        fun createRootFilePath(basePath: String, name: String): Path {
            val sanitized = name.trim().replace("\\s+".toRegex(), "-").lowercase()
            val fileName = if (sanitized.endsWith(".json")) sanitized else "$sanitized.json"
            return Path.of(basePath, DATA_DIRECTORY, fileName)
        }
    }
}
