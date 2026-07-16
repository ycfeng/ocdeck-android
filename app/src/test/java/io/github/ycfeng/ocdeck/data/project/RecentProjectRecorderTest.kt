package io.github.ycfeng.ocdeck.data.project

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class RecentProjectRecorderTest {
    @Test
    fun recordsAThenBInOrderAndLeavesBAtMruFront() = runTest {
        val repository = FakeRecentProjectRepository()
        val recorder = recorder(repository, backgroundScope)

        recorder.recordAdd(SERVER_ID, DIRECTORY_A)
        recorder.recordAdd(SERVER_ID, DIRECTORY_B)
        runCurrent()

        assertEquals(listOf(DIRECTORY_A, DIRECTORY_B), repository.attempts.map { it.directory })
        assertEquals(listOf(DIRECTORY_B, DIRECTORY_A), repository.projects(SERVER_ID).map { it.normalizedDirectory })
    }

    @Test
    fun retriesAOnceBeforeBAndStillLeavesBAtMruFront() = runTest {
        val repository = FakeRecentProjectRepository { attempt, attemptNumber ->
            if (attempt.directory == DIRECTORY_A && attemptNumber == 1) throw IOException("synthetic I/O failure")
        }
        val failures = mutableListOf<RecentProjectFailure>()
        val recorder = recorder(repository, backgroundScope, failures::add)

        recorder.recordAdd(SERVER_ID, DIRECTORY_A)
        recorder.recordAdd(SERVER_ID, DIRECTORY_B)
        runCurrent()

        assertEquals(listOf(DIRECTORY_A, DIRECTORY_A, DIRECTORY_B), repository.attempts.map { it.directory })
        assertEquals(listOf(DIRECTORY_B, DIRECTORY_A), repository.projects(SERVER_ID).map { it.normalizedDirectory })
        assertTrue(failures.isEmpty())
    }

    @Test
    fun consecutiveWindowsAliasesMergeAndUpsertKeepsMetadata() = runTest {
        val repository = FakeRecentProjectRepository()
        val recorder = recorder(repository, backgroundScope)
        val project = ProjectRef(
            normalizedDirectory = "c:/work/alpha/",
            projectId = "project-fixture",
            displayName = "Renamed Alpha",
            vcs = "git",
            icon = null,
        )

        recorder.recordAdd(SERVER_ID, "C:\\Work\\Alpha")
        recorder.recordUpsert(SERVER_ID, project)
        runCurrent()

        assertEquals(1, repository.attempts.size)
        assertEquals(WriteKind.Upsert, repository.attempts.single().kind)
        assertEquals("Renamed Alpha", repository.projects(SERVER_ID).single().displayName)
        assertEquals("project-fixture", repository.projects(SERVER_ID).single().projectId)
    }

    @Test
    fun ordinaryFailureIsReportedAndDoesNotBlockFollowingWrite() = runTest {
        val repository = FakeRecentProjectRepository { attempt, _ ->
            if (attempt.directory == DIRECTORY_A) throw IllegalStateException("synthetic ordinary failure")
        }
        val failures = mutableListOf<RecentProjectFailure>()
        val recorder = recorder(repository, backgroundScope, failures::add)

        recorder.recordAdd(SERVER_ID, DIRECTORY_A)
        recorder.recordAdd(SERVER_ID, DIRECTORY_B)
        runCurrent()

        assertEquals(listOf(DIRECTORY_A, DIRECTORY_B), repository.attempts.map { it.directory })
        assertEquals(listOf(DIRECTORY_B), repository.projects(SERVER_ID).map { it.normalizedDirectory })
        assertEquals(listOf(RecentProjectFailure.WriteUnexpected), failures)
    }

    @Test
    fun serializationFailureIsNotRetriedAndFollowingWriteContinues() = runTest {
        val repository = FakeRecentProjectRepository { attempt, _ ->
            if (attempt.directory == DIRECTORY_A) throw SerializationException("synthetic invalid data")
        }
        val failures = mutableListOf<RecentProjectFailure>()
        val recorder = recorder(repository, backgroundScope, failures::add)

        recorder.recordAdd(SERVER_ID, DIRECTORY_A)
        recorder.recordAdd(SERVER_ID, DIRECTORY_B)
        runCurrent()

        assertEquals(listOf(DIRECTORY_A, DIRECTORY_B), repository.attempts.map { it.directory })
        assertEquals(listOf(RecentProjectFailure.WriteSerialization), failures)
    }

    @Test
    fun cancellationStopsConsumerWithoutOrdinaryFailureReporting() = runTest {
        val repository = FakeRecentProjectRepository { attempt, _ ->
            if (attempt.directory == DIRECTORY_A) throw CancellationException("synthetic cancellation")
        }
        val failures = mutableListOf<RecentProjectFailure>()
        val recorder = recorder(repository, backgroundScope, failures::add)

        recorder.recordAdd(SERVER_ID, DIRECTORY_A)
        recorder.recordAdd(SERVER_ID, DIRECTORY_B)
        runCurrent()

        assertTrue(recorder.workerJob.isCancelled)
        assertEquals(listOf(DIRECTORY_A), repository.attempts.map { it.directory })
        assertTrue(failures.isEmpty())
    }

    @Test
    fun jvmErrorEscapesConsumerWithoutOrdinaryFailureReporting() = runTest {
        val expected = AssertionError("synthetic JVM error")
        val uncaught = AtomicReference<Throwable?>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, throwable -> uncaught.set(throwable) },
        )
        try {
            val repository = FakeRecentProjectRepository { attempt, _ ->
                if (attempt.directory == DIRECTORY_A) throw expected
            }
            val failures = mutableListOf<RecentProjectFailure>()
            val recorder = recorder(repository, scope, failures::add)

            recorder.recordAdd(SERVER_ID, DIRECTORY_A)
            recorder.recordAdd(SERVER_ID, DIRECTORY_B)
            runCurrent()

            assertSame(expected, uncaught.get())
            assertTrue(recorder.workerJob.isCancelled)
            assertEquals(listOf(DIRECTORY_A), repository.attempts.map { it.directory })
            assertTrue(failures.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun queueIsBoundedAndReportsOnlySafeEnum() = runTest {
        val repository = FakeRecentProjectRepository()
        val failures = mutableListOf<RecentProjectFailure>()
        val recorder = RecentProjectRecorder(
            repository = repository,
            pathNormalizer = PathNormalizer(),
            scope = backgroundScope,
            failureReporter = RecentProjectFailureReporter { failures += it },
            queueCapacity = 1,
        )

        recorder.recordAdd("server-secret-one", "/private/alpha")
        recorder.recordAdd("server-secret-two", "/private/beta")

        assertEquals(listOf(RecentProjectFailure.QueueFull), failures)
        assertFalse(failures.single().name.contains("server-secret"))
        assertFalse(failures.single().name.contains("private"))
    }

    private fun recorder(
        repository: RecentProjectRepository,
        scope: CoroutineScope,
        reporter: RecentProjectFailureReporter = RecentProjectFailureReporter { },
    ) = RecentProjectRecorder(
        repository = repository,
        pathNormalizer = PathNormalizer(),
        scope = scope,
        failureReporter = reporter,
    )

    private class FakeRecentProjectRepository(
        private val onAttempt: suspend (WriteAttempt, Int) -> Unit = { _, _ -> },
    ) : RecentProjectRepository {
        val attempts = mutableListOf<WriteAttempt>()
        private val projectsByServer = linkedMapOf<String, MutableList<ProjectRef>>()

        override fun observe(serverId: String): Flow<List<ProjectRef>> = flowOf(projects(serverId))

        override suspend fun add(serverId: String, directory: String): ProjectRef {
            val attempt = WriteAttempt(WriteKind.Add, serverId, directory)
            attempts += attempt
            onAttempt(attempt, attempts.count { it.serverId == serverId && it.directory == directory })
            val existing = projects(serverId).firstOrNull { PathNormalizer().areSame(it.normalizedDirectory, directory) }
            val project = existing ?: project(directory)
            save(serverId, project)
            return project
        }

        override suspend fun upsert(serverId: String, project: ProjectRef): ProjectRef {
            val attempt = WriteAttempt(WriteKind.Upsert, serverId, project.normalizedDirectory)
            attempts += attempt
            onAttempt(
                attempt,
                attempts.count { it.serverId == serverId && it.directory == project.normalizedDirectory },
            )
            save(serverId, project)
            return project
        }

        override suspend fun remove(serverId: String, directory: String) = Unit

        fun projects(serverId: String): List<ProjectRef> = projectsByServer[serverId].orEmpty()

        private fun save(serverId: String, project: ProjectRef) {
            val projects = projectsByServer.getOrPut(serverId) { mutableListOf() }
            projects.removeAll { PathNormalizer().areSame(it.normalizedDirectory, project.normalizedDirectory) }
            projects.add(0, project)
        }
    }

    private data class WriteAttempt(
        val kind: WriteKind,
        val serverId: String,
        val directory: String,
    )

    private enum class WriteKind { Add, Upsert }

    private companion object {
        const val SERVER_ID = "server-fixture"
        const val DIRECTORY_A = "/workspace/alpha"
        const val DIRECTORY_B = "/workspace/beta"

        fun project(directory: String) = ProjectRef(
            normalizedDirectory = directory,
            projectId = null,
            displayName = directory.substringAfterLast('/'),
            vcs = null,
            icon = null,
        )
    }
}
