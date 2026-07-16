package io.github.ycfeng.ocdeck.domain.prompt

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOperationCoordinatorTest {
    @Test
    fun heldSessionLeaseRejectsRevertWithoutCallingGateway() = runTest {
        val pathNormalizer = PathNormalizer()
        val gate = PromptOperationGate(pathNormalizer)
        val promptGateway = RecordingPromptGateway()
        val revertGateway = RecordingRevertGateway()
        val coordinator = SessionOperationCoordinator(promptGateway, revertGateway, gate, pathNormalizer)
        val lease = checkNotNull(gate.tryAcquire("server", "E:/Repo", "ses_1", "D:/Workspace"))

        val result = coordinator.revert(
            serverId = "server",
            directory = "e:\\repo\\",
            sessionId = "ses_1",
            messageId = "msg_1",
            workspace = "d:\\workspace\\",
        )

        assertTrue(result.exceptionOrNull() is PromptOperationInProgressException)
        assertTrue(revertGateway.operations.isEmpty())
        lease.close()
    }

    @Test
    fun abortAndRevertRunInOrderUnderOneCoordinatorOperation() = runTest {
        val pathNormalizer = PathNormalizer()
        val operations = mutableListOf<String>()
        val promptGateway = RecordingPromptGateway(operations)
        val revertGateway = RecordingRevertGateway(operations)
        val coordinator = SessionOperationCoordinator(
            promptGateway,
            revertGateway,
            PromptOperationGate(pathNormalizer),
            pathNormalizer,
        )

        val result = coordinator.revert(
            serverId = "server",
            directory = "E:\\Repo\\",
            sessionId = "ses_1",
            messageId = "msg_1",
            abortFirst = true,
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("abort:E:/Repo", "revert:E:/Repo"), operations)
    }

    private class RecordingPromptGateway(
        private val operations: MutableList<String> = mutableListOf(),
    ) : PromptGateway {
        override suspend fun createSession(
            serverId: String,
            directory: String,
            agent: String,
            model: PromptModelSelection,
            workspace: String?,
        ): Result<OpenCodeSession> = error("Not used")

        override suspend fun sendPromptAsync(
            serverId: String,
            directory: String,
            sessionId: String,
            messageId: String,
            partId: String,
            text: String,
            attachments: List<PromptAttachment>,
            projectFileContexts: List<ProjectFileContext>,
            agent: String,
            model: PromptModelSelection,
            variant: String?,
            workspace: String?,
        ): Result<Unit> = error("Not used")

        override suspend fun sendCommandAsync(
            serverId: String,
            directory: String,
            sessionId: String,
            messageId: String,
            command: String,
            arguments: String?,
            attachments: List<PromptAttachment>,
            projectFileContexts: List<ProjectFileContext>,
            agent: String,
            model: PromptModelSelection,
            variant: String?,
            workspace: String?,
        ): Result<Unit> = error("Not used")

        override suspend fun abortSession(
            serverId: String,
            directory: String,
            sessionId: String,
            workspace: String?,
        ): Result<Unit> {
            operations += "abort:$directory"
            return Result.success(Unit)
        }
    }

    private class RecordingRevertGateway(
        val operations: MutableList<String> = mutableListOf(),
    ) : SessionRevertGateway {
        override suspend fun revertSession(
            serverId: String,
            directory: String,
            sessionId: String,
            messageId: String,
            partId: String?,
            workspace: String?,
        ): Result<OpenCodeSession> {
            operations += "revert:$directory"
            return Result.success(session(sessionId))
        }

        override suspend fun unrevertSession(
            serverId: String,
            directory: String,
            sessionId: String,
            workspace: String?,
        ): Result<OpenCodeSession> = Result.success(session(sessionId))
    }

    private companion object {
        fun session(id: String) = OpenCodeSession(
            id = id,
            title = "Session",
            normalizedDirectory = "E:/Repo",
            path = null,
            parentId = null,
            agent = null,
            modelLabel = null,
            updatedAt = null,
            archivedAt = null,
        )
    }
}
