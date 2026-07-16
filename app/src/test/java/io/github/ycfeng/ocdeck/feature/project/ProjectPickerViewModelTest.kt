package io.github.ycfeng.ocdeck.feature.project

import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.project.RecentProjectFailureReporter
import io.github.ycfeng.ocdeck.data.project.RecentProjectRecorder
import io.github.ycfeng.ocdeck.data.project.RecentProjectRepository
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import io.github.ycfeng.ocdeck.ui.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectPickerViewModelTest {
    @Test
    fun writerIOExceptionDoesNotBlockNavigation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FakeRecentProjectRepository(
                onAdd = { throw IOException("synthetic writer failure") },
            )
            val viewModel = viewModel(repository, RecentProjectRecorder(
                repository = repository,
                pathNormalizer = PathNormalizer(),
                scope = backgroundScope,
                failureReporter = RecentProjectFailureReporter { },
            ))
            var openedDirectory: String? = null

            viewModel.openProject("C:\\Work\\Alpha\\") { openedDirectory = it }

            assertEquals("C:/Work/Alpha", openedDirectory)
            assertTrue(viewModel.uiState.value.isOpening)
            runCurrent()
            assertEquals(2, repository.addCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun hangingWriterDoesNotBlockNavigation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val neverCompletes = CompletableDeferred<Unit>()
            val repository = FakeRecentProjectRepository(onAdd = { neverCompletes.await() })
            val viewModel = viewModel(repository, RecentProjectRecorder(
                repository = repository,
                pathNormalizer = PathNormalizer(),
                scope = backgroundScope,
                failureReporter = RecentProjectFailureReporter { },
            ))
            var navigationCount = 0

            viewModel.openProject("/workspace/alpha") { navigationCount++ }
            runCurrent()

            assertEquals(1, navigationCount)
            assertEquals(1, repository.addCalls)
            assertTrue(viewModel.uiState.value.isOpening)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rapidOpenIsSingleFlightAndReturningToPickerReleasesIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FakeRecentProjectRepository()
            val viewModel = viewModel(repository, RecentProjectRecorder(
                repository = repository,
                pathNormalizer = PathNormalizer(),
                scope = backgroundScope,
                failureReporter = RecentProjectFailureReporter { },
            ))
            var navigationCount = 0

            viewModel.openProject("/workspace/alpha") { navigationCount++ }
            viewModel.openProject("/workspace/alpha") { navigationCount++ }

            assertEquals(1, navigationCount)
            assertTrue(viewModel.uiState.value.isOpening)

            viewModel.onPickerVisible()
            viewModel.openProject("/workspace/beta") { navigationCount++ }

            assertEquals(2, navigationCount)
            assertTrue(viewModel.uiState.value.isOpening)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun navigationFailureReleasesSingleFlightAndUsesLocalFallback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FakeRecentProjectRepository()
            val viewModel = viewModel(repository, RecentProjectRecorder(
                repository = repository,
                pathNormalizer = PathNormalizer(),
                scope = backgroundScope,
                failureReporter = RecentProjectFailureReporter { },
            ))

            viewModel.openProject("/workspace/alpha") { throw IllegalStateException("synthetic navigation failure") }
            runCurrent()

            assertFalse(viewModel.uiState.value.isOpening)
            assertEquals(0, repository.addCalls)
            assertEquals(
                R.string.project_error_open_navigation_failed,
                (viewModel.uiState.value.error as UiText.Resource).id,
            )

            var opened = false
            viewModel.openProject("/workspace/alpha") { opened = true }
            assertTrue(opened)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun explicitDeleteWaitsAndUsesLocalFailureText() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FakeRecentProjectRepository(
                onRemove = { throw IllegalStateException("synthetic local failure") },
            )
            val viewModel = viewModel(repository, RecentProjectRecorder(
                repository = repository,
                pathNormalizer = PathNormalizer(),
                scope = backgroundScope,
                failureReporter = RecentProjectFailureReporter { },
            ))

            viewModel.deleteRecentProject(project("/workspace/alpha"))
            runCurrent()

            assertEquals(1, repository.removeCalls)
            assertEquals(
                R.string.project_error_delete_recent_failed,
                (viewModel.uiState.value.error as UiText.Resource).id,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(
        repository: RecentProjectRepository,
        recorder: RecentProjectRecorder,
    ) = ProjectPickerViewModel(
        serverId = "server-fixture",
        suggestionLoader = ProjectDirectorySuggestionLoader { _, _, _, _ -> Result.success(emptyList()) },
        recentProjectRepository = repository,
        recentProjectRecorder = recorder,
        pathNormalizer = PathNormalizer(),
    )

    private class FakeRecentProjectRepository(
        private val onAdd: suspend () -> Unit = {},
        private val onRemove: suspend () -> Unit = {},
    ) : RecentProjectRepository {
        var addCalls = 0
        var removeCalls = 0

        override fun observe(serverId: String): Flow<List<ProjectRef>> = flowOf(emptyList())

        override suspend fun add(serverId: String, directory: String): ProjectRef {
            addCalls++
            onAdd()
            return project(directory)
        }

        override suspend fun upsert(serverId: String, project: ProjectRef): ProjectRef = project

        override suspend fun remove(serverId: String, directory: String) {
            removeCalls++
            onRemove()
        }
    }

    private companion object {
        fun project(directory: String) = ProjectRef(
            normalizedDirectory = directory,
            projectId = null,
            displayName = directory.substringAfterLast('/'),
            vcs = null,
            icon = null,
        )
    }
}
