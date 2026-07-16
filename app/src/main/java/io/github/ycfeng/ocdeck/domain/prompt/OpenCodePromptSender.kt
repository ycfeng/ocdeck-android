package io.github.ycfeng.ocdeck.domain.prompt

import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.store.OptimisticMessageRemovalResult
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.core.util.ProjectFilePathNormalizer
import io.github.ycfeng.ocdeck.core.util.ProjectFileUrlBuilder
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenCodePromptSender(
    private val gateway: PromptGateway,
    private val store: InMemoryOpenCodeStore,
    private val pathNormalizer: PathNormalizer,
    private val projectFilePathNormalizer: ProjectFilePathNormalizer = ProjectFilePathNormalizer(),
    private val operationGate: PromptOperationGate = PromptOperationGate(pathNormalizer),
    private val stateMachine: PromptSendStateMachine = PromptSendStateMachine(),
) {
    private val projectFileUrlBuilder = ProjectFileUrlBuilder(pathNormalizer, projectFilePathNormalizer)

    suspend fun sendPrompt(
        serverId: String,
        directory: String,
        sessionId: String?,
        text: String,
        attachments: List<PromptAttachment> = emptyList(),
        projectFileContexts: List<ProjectFileContext> = emptyList(),
        agent: String? = null,
        model: PromptModelSelection? = null,
        variant: String? = model?.variant,
        workspace: String? = null,
        capabilities: PromptCapabilities,
        onOptimisticMessageCreated: (String) -> Unit = {},
        onSessionMaterialized: (String) -> Unit = {},
    ): Result<PromptSendResult> {
        val action = stateMachine.evaluate(
            PromptSendInput(
                text = text,
                hasAttachments = attachments.isNotEmpty(),
                hasContext = projectFileContexts.isNotEmpty(),
            ),
        )
        if (action != PromptSendAction.SendPrompt) {
            return Result.failure(IllegalStateException("Prompt is empty"))
        }

        val normalized = pathNormalizer.normalize(directory)
        val normalizedWorkspace = normalizeOptionalPromptWorkspace(workspace, pathNormalizer)
        val attachmentSnapshot = try {
            val snapshot = attachments.toList()
            withContext(Dispatchers.Default) { validatePromptAttachments(snapshot) }
        } catch (throwable: PromptSendException) {
            return Result.failure(throwable)
        }
        val projectFileContextSnapshot = try {
            withContext(Dispatchers.Default) {
                normalizeProjectFileContexts(
                    contexts = projectFileContexts.toList(),
                    projectDirectory = normalized,
                    pathNormalizer = projectFilePathNormalizer,
                    urlBuilder = projectFileUrlBuilder,
                )
            }
        } catch (throwable: PromptSendException) {
            return Result.failure(throwable)
        }
        val capabilitySnapshot = store.currentProject(serverId, normalized, normalizedWorkspace)
            .promptCapabilities
            .frozenCopy()
        if (!capabilitySnapshot.isLoaded) {
            return Result.failure(PromptCapabilitiesNotLoadedException())
        }
        if (!capabilities.isLoaded || capabilities.revision != capabilitySnapshot.revision) {
            return Result.failure(PromptCapabilitiesStaleException())
        }
        val validated = try {
            capabilitySnapshot.validatePromptSelection(agent, model, variant)
        } catch (throwable: PromptSendException) {
            return Result.failure(throwable)
        }
        val trimmedText = text.trimPromptWhitespace()
        val command = trimmedText.parseKnownSlashCommand(capabilitySnapshot.commands)
        val effectiveModel = validated.model
        val modelLabel = listOfNotNull(effectiveModel.providerId, effectiveModel.modelId, effectiveModel.variant)
            .joinToString("/")
        val initialSessionId = sessionId?.takeUnless { it == NEW_SESSION_ID }
        var targetSessionId = initialSessionId ?: NEW_SESSION_ID
        val operationLease = operationGate.tryAcquire(
            serverId = serverId,
            directory = normalized,
            sessionId = targetSessionId,
            workspace = normalizedWorkspace,
        )
            ?: return Result.failure(PromptOperationInProgressException())
        val messageId = OpenCodeIdGenerator.messageId()
        val partId = OpenCodeIdGenerator.partId()
        var optimisticMessagePresent = false
        try {
            val inserted = store.upsertOptimisticMessageIfPromptCapabilitiesRevision(
                serverId = serverId,
                directory = normalized,
                message = OpenCodeMessage(
                    id = messageId,
                    sessionId = targetSessionId,
                    role = "user",
                    text = trimmedText,
                    agent = validated.agent,
                    modelLabel = modelLabel,
                    modelProviderId = effectiveModel.providerId,
                    modelId = effectiveModel.modelId,
                    modelVariant = effectiveModel.variant,
                    createdAt = System.currentTimeMillis(),
                    isOptimistic = true,
                    parts = projectFileContextSnapshot.map {
                        it.toOptimisticPart(messageId, targetSessionId, normalized, projectFileUrlBuilder)
                    } + attachmentSnapshot.map { it.toOptimisticPart(messageId, targetSessionId) },
                ),
                expectedCapabilitiesRevision = capabilitySnapshot.revision,
                workspace = normalizedWorkspace,
            )
            if (!inserted) throw PromptCapabilitiesStaleException()
            optimisticMessagePresent = true
            onOptimisticMessageCreated(messageId)

            if (initialSessionId == null) {
                val created = gateway.createSession(
                    serverId = serverId,
                    directory = normalized,
                    agent = validated.agent,
                    model = effectiveModel,
                    workspace = normalizedWorkspace,
                ).getOrThrow()
                check(created.id.isNotBlank()) { "Created session ID is empty" }
                if (!operationLease.addSessionAlias(created.id)) {
                    throw PromptOperationInProgressException()
                }
                targetSessionId = created.id
                store.upsertSession(serverId, normalized, created, normalizedWorkspace)
                store.moveMessage(
                    serverId,
                    normalized,
                    NEW_SESSION_ID,
                    targetSessionId,
                    messageId,
                    normalizedWorkspace,
                )
                onSessionMaterialized(targetSessionId)
            }

            val sendResult = if (command == null) {
                gateway.sendPromptAsync(
                    serverId = serverId,
                    directory = normalized,
                    sessionId = targetSessionId,
                    messageId = messageId,
                    partId = partId,
                    text = trimmedText,
                    attachments = attachmentSnapshot,
                    projectFileContexts = projectFileContextSnapshot,
                    agent = validated.agent,
                    model = effectiveModel,
                    variant = effectiveModel.variant,
                    workspace = normalizedWorkspace,
                )
            } else {
                gateway.sendCommandAsync(
                    serverId = serverId,
                    directory = normalized,
                    sessionId = targetSessionId,
                    messageId = messageId,
                    command = command.name,
                    arguments = command.arguments,
                    attachments = attachmentSnapshot,
                    projectFileContexts = projectFileContextSnapshot,
                    agent = validated.agent,
                    model = effectiveModel,
                    variant = effectiveModel.variant,
                    workspace = normalizedWorkspace,
                )
            }
            sendResult.getOrThrow()
            store.markMessageAccepted(serverId, normalized, targetSessionId, messageId, normalizedWorkspace)
            optimisticMessagePresent = false
            return Result.success(
                PromptSendResult(
                    sessionId = targetSessionId,
                    messageId = messageId,
                    materializedNewSession = initialSessionId == null,
                ),
            )
        } catch (cancelled: CancellationException) {
            if (optimisticMessagePresent) {
                store.removeMessageIfOptimistic(serverId, normalized, targetSessionId, messageId, normalizedWorkspace)
            }
            throw cancelled
        } catch (exception: Exception) {
            val failure = if (optimisticMessagePresent) {
                when (
                    store.removeMessageIfOptimistic(
                        serverId,
                        normalized,
                        targetSessionId,
                        messageId,
                        normalizedWorkspace,
                    )
                ) {
                    OptimisticMessageRemovalResult.Confirmed -> PromptSendOutcomeUncertainException()
                    OptimisticMessageRemovalResult.Removed,
                    OptimisticMessageRemovalResult.Missing -> exception
                }
            } else {
                exception
            }
            return Result.failure(failure)
        } finally {
            operationLease.close()
        }
    }

    suspend fun stop(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ): Result<Unit> {
        val normalized = pathNormalizer.normalize(directory)
        val normalizedWorkspace = normalizeOptionalPromptWorkspace(workspace, pathNormalizer)
        val operationLease = operationGate.tryAcquire(
            serverId = serverId,
            directory = normalized,
            sessionId = sessionId,
            workspace = normalizedWorkspace,
        )
            ?: return Result.failure(PromptOperationInProgressException())
        return try {
            gateway.abortSession(serverId, normalized, sessionId, normalizedWorkspace)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Result.failure(exception)
        } finally {
            operationLease.close()
        }
    }

    companion object {
        const val NEW_SESSION_ID = "new"
    }
}

private fun PromptAttachment.toOptimisticPart(messageId: String, sessionId: String): OpenCodeMessagePart = OpenCodeMessagePart(
    id = id,
    messageId = messageId,
    sessionId = sessionId,
    type = "file",
    text = null,
    synthetic = false,
    filename = filename,
    mime = mime,
    url = dataUrl,
)

private fun ProjectFileContext.toOptimisticPart(
    messageId: String,
    sessionId: String,
    projectDirectory: String,
    urlBuilder: ProjectFileUrlBuilder,
): OpenCodeMessagePart = OpenCodeMessagePart(
    id = id,
    messageId = messageId,
    sessionId = sessionId,
    type = "file",
    text = null,
    synthetic = false,
    filename = filename,
    mime = "text/plain",
    url = urlBuilder.build(projectDirectory, relativePath),
)

private fun normalizeProjectFileContexts(
    contexts: List<ProjectFileContext>,
    projectDirectory: String,
    pathNormalizer: ProjectFilePathNormalizer,
    urlBuilder: ProjectFileUrlBuilder,
): List<ProjectFileContext> {
    if (contexts.size > ProjectFileContextLimits.MAX_CONTEXT_COUNT) {
        throw PromptProjectContextCountLimitException()
    }
    val windowsSeparators = pathNormalizer.usesWindowsSeparators(projectDirectory)
    val seen = mutableSetOf<String>()
    return contexts.map { context ->
        if (context.id.isBlank()) throw PromptProjectContextInvalidException()
        val normalizedPath = try {
            pathNormalizer.normalize(
                context.relativePath,
                backslashIsSeparator = windowsSeparators,
            )
        } catch (_: IllegalArgumentException) {
            throw PromptProjectContextInvalidException()
        }
        val comparisonPath = if (windowsSeparators) normalizedPath.lowercase() else normalizedPath
        if (!seen.add(comparisonPath)) throw PromptProjectContextInvalidException()
        val normalized = context.copy(relativePath = normalizedPath)
        try {
            urlBuilder.build(projectDirectory, normalized.relativePath)
        } catch (_: IllegalArgumentException) {
            throw PromptProjectContextInvalidException()
        }
        normalized
    }
}

private data class ValidatedPromptSelection(
    val agent: String,
    val model: PromptModelSelection,
) {
    override fun toString(): String =
        "ValidatedPromptSelection(agentPresent=${agent.isNotEmpty()}, modelPresent=true)"
}

private fun PromptCapabilities.validatePromptSelection(
    agent: String?,
    model: PromptModelSelection?,
    variant: String?,
): ValidatedPromptSelection {
    if (!isLoaded) throw PromptCapabilitiesNotLoadedException()
    val requestedModel = model ?: throw PromptModelSelectionRequiredException()
    if (requestedModel.providerId.isBlank() || requestedModel.modelId.isBlank()) {
        throw PromptModelUnavailableException()
    }
    val modelDefinition = models.firstOrNull {
        it.providerId == requestedModel.providerId && it.modelId == requestedModel.modelId
    } ?: throw PromptModelUnavailableException()
    if (!modelDefinition.isConnected || !modelDefinition.isEnabled) {
        throw PromptModelUnavailableException()
    }
    if (variant != null && variant !in modelDefinition.variants) {
        throw PromptVariantUnavailableException()
    }
    val requestedAgent = agent ?: throw PromptAgentUnavailableException()
    if (
        requestedAgent.isBlank() ||
        requestedAgent !in SUPPORTED_PROMPT_AGENTS ||
        agents.none { it.id == requestedAgent }
    ) {
        throw PromptAgentUnavailableException()
    }
    return ValidatedPromptSelection(
        agent = requestedAgent,
        model = requestedModel.copy(variant = variant),
    )
}

private fun PromptCapabilities.frozenCopy(): PromptCapabilities = copy(
    models = models.map { it.copy(variants = it.variants.toList()) },
    agents = agents.toList(),
    commands = commands.toList(),
    serverDefaultModels = serverDefaultModels.toList(),
)

private data class ParsedSlashCommand(
    val name: String,
    val arguments: String?,
) {
    override fun toString(): String =
        "ParsedSlashCommand(name=<redacted>, arguments=${if (arguments == null) "null" else "<redacted>"})"
}

private fun String.parseKnownSlashCommand(commands: List<io.github.ycfeng.ocdeck.domain.model.OpenCodeCommand>): ParsedSlashCommand? {
    val tokenEnd = indexOfFirst(Char::isPromptWhitespace)
    val token = if (tokenEnd == -1) this else substring(0, tokenEnd)
    if (token.length <= 1 || token.firstOrNull() != '/' || token.drop(1).contains('/')) return null
    val commandName = token.drop(1)
    if (commands.none { it.name == commandName }) return null
    val arguments = if (tokenEnd == -1) null else substring(tokenEnd).trimPromptWhitespace().takeIf { it.isNotEmpty() }
    return ParsedSlashCommand(commandName, arguments)
}

private fun String.trimPromptWhitespace(): String = trim(Char::isPromptWhitespace)

private fun Char.isPromptWhitespace(): Boolean = isWhitespace() || Character.isSpaceChar(this)

sealed class PromptSendException(message: String) : IllegalStateException(message)

class PromptCapabilitiesNotLoadedException : PromptSendException("Prompt capabilities are not loaded")

class PromptCapabilitiesStaleException : PromptSendException("Prompt capabilities changed before the operation started")

class PromptModelSelectionRequiredException : PromptSendException("A prompt model must be selected")

class PromptModelUnavailableException : PromptSendException("The selected prompt model is unavailable")

class PromptAgentUnavailableException : PromptSendException("The selected prompt agent is unavailable")

class PromptVariantUnavailableException : PromptSendException("The selected prompt variant is unavailable")

class PromptOperationInProgressException : PromptSendException("Another prompt operation is already in progress")

class PromptAttachmentCountLimitException : PromptSendException("Prompt attachment count exceeds the allowed limit")

class PromptAttachmentFileTooLargeException : PromptSendException("A prompt attachment exceeds the allowed size")

class PromptAttachmentTotalSizeLimitException : PromptSendException("Prompt attachments exceed the allowed total size")

class PromptAttachmentMalformedException : PromptSendException("Prompt attachment data is malformed")

class PromptAttachmentMetadataInvalidException : PromptSendException("Prompt attachment size metadata is invalid")

class PromptProjectContextCountLimitException : PromptSendException("Project file context count exceeds the allowed limit")

class PromptProjectContextInvalidException : PromptSendException("Project file context is invalid")

class PromptSendOutcomeUncertainException : PromptSendException("The prompt may have been accepted despite the request failure")

private val SUPPORTED_PROMPT_AGENTS = setOf("build", "plan")

data class PromptSendResult(
    val sessionId: String,
    val messageId: String,
    val materializedNewSession: Boolean,
) {
    override fun toString(): String =
        "PromptSendResult(sessionId=<redacted>, messageId=<redacted>, " +
            "materializedNewSession=$materializedNewSession)"
}
