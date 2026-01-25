package emohce.core.di

import com.intellij.openapi.project.Project
import emohce.core.coroutine.CoroutineDispatchers
import emohce.data.repository.BookmarkRepositoryImpl
import emohce.data.repository.BookmarkStoreProvider
import emohce.data.repository.ReferenceRepositoryImpl
import emohce.domain.usecase.navigation.ProcessNavigationUseCase
import emohce.domain.usecase.reference.DetectCircularRefUseCase
import emohce.domain.usecase.reference.SyncReferencesUseCase
import emohce.presentation.toolwindow.BookmarkViewModel

class ServiceLocator(private val project: Project) {
    private val store by lazy { BookmarkStoreProvider.get(project) }

    val bookmarkRepository by lazy { BookmarkRepositoryImpl(store) }
    val referenceRepository by lazy { ReferenceRepositoryImpl(store) }
    val dispatchers by lazy { CoroutineDispatchers() }
    val bookmarkViewModel by lazy { BookmarkViewModel(project, bookmarkRepository, referenceRepository, processNavigationUseCase, syncReferencesUseCase, detectCircularRefUseCase, dispatchers) }

    val processNavigationUseCase by lazy { ProcessNavigationUseCase(bookmarkRepository) }
    val syncReferencesUseCase by lazy { SyncReferencesUseCase(bookmarkRepository, referenceRepository) }
    val detectCircularRefUseCase by lazy { DetectCircularRefUseCase(referenceRepository) }
}
