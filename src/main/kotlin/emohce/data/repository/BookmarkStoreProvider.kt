package emohce.data.repository

import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides a shared BookmarkStore per Project to keep actions, toolwindow,
 * and inlay providers reading/writing the same state.
 */
object BookmarkStoreProvider {
    private val stores = ConcurrentHashMap<Project, BookmarkStore>()

    fun get(project: Project): BookmarkStore {
        return stores.computeIfAbsent(project) { BookmarkStore(it) }
    }
}
