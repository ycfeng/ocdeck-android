package io.github.ycfeng.ocdeck.domain.prompt

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import kotlinx.coroutines.CancellationException

interface SessionRevertGateway {
    suspend fun revertSession(
        serverId: String,
        directory: String,
        sessionId: String,
        messageId: String,
        partId: String? = null,
        workspace: String? = null,
    ): Result<OpenCodeSession>

    suspend fun unrevertSession(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ): Result<OpenCodeSession>
}

class SessionOperationCoordinator(
    private val promptGateway: PromptGateway,
    private val revertGateway: SessionRevertGateway,
    private val operationGate: PromptOperationGate,
    private val pathNormalizer: PathNormalizer,
) {
    suspend fun revert(
        serverId: String,
        directory: String,
        sessionId: String,
        messageId: String,
        partId: String? = null,
        workspace: String? = null,
        abortFirst: Boolean = false,
    ): Result<OpenCodeSession> = withSessionLease(serverId, directory, sessionId, workspace) { normalized, normalizedWorkspace ->
        if (abortFirst) {
            promptGateway.abortSession(serverId, normalized, sessionId, normalizedWorkspace).getOrThrow()
        }
        revertGateway.revertSession(
            serverId = serverId,
            directory = normalized,
            sessionId = sessionId,
            messageId = messageId,
            partId = partId,
            workspace = normalizedWorkspace,
        )
    }

    suspend fun unrevert(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ): Result<OpenCodeSession> = withSessionLease(serverId, directory, sessionId, workspace) { normalized, normalizedWorkspace ->
        revertGateway.unrevertSession(
            serverId = serverId,
            directory = normalized,
            sessionId = sessionId,
            workspace = normalizedWorkspace,
        )
    }

    private suspend fun <T> withSessionLease(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String?,
        block: suspend (normalizedDirectory: String, normalizedWorkspace: String?) -> Result<T>,
    ): Result<T> {
        val normalized = pathNormalizer.normalize(directory)
        val normalizedWorkspace = normalizeOptionalPromptWorkspace(workspace, pathNormalizer)
        val lease = operationGate.tryAcquire(serverId, normalized, sessionId, normalizedWorkspace)
            ?: return Result.failure(PromptOperationInProgressException())
        return try {
            block(normalized, normalizedWorkspace)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        } finally {
            lease.close()
        }
    }
}
