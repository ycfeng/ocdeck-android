package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.core.util.ProjectFilePathNormalizer
import io.github.ycfeng.ocdeck.core.util.ProjectFileUrlBuilder
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionRevert
import io.github.ycfeng.ocdeck.domain.prompt.AttachmentBudget
import io.github.ycfeng.ocdeck.domain.prompt.AttachmentBudgetFailure
import io.github.ycfeng.ocdeck.domain.prompt.AttachmentLimits
import io.github.ycfeng.ocdeck.domain.prompt.DataUrlPayloadInspection
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachment
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContext
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContextLimits
import io.github.ycfeng.ocdeck.domain.prompt.inspectBase64DataUrl as inspectPromptBase64DataUrl

internal data class SessionMessageProjection(
    val visibleMessages: List<OpenCodeMessage>,
    val revertedUserMessages: List<OpenCodeMessage>,
) {
    override fun toString(): String =
        "SessionMessageProjection(visibleMessageCount=${visibleMessages.size}, " +
            "revertedUserMessageCount=${revertedUserMessages.size})"
}

internal fun projectSessionMessages(
    messages: List<OpenCodeMessage>,
    revert: OpenCodeSessionRevert?,
    branchStartMessageId: String? = null,
): SessionMessageProjection {
    val marker = revert ?: return SessionMessageProjection(
        visibleMessages = messages,
        revertedUserMessages = emptyList(),
    )
    val activeBranchStart = branchStartMessageId?.takeIf { it >= marker.messageId }
    return SessionMessageProjection(
        visibleMessages = messages.filter { message ->
            message.id < marker.messageId || activeBranchStart?.let { message.id >= it } == true
        },
        revertedUserMessages = if (activeBranchStart == null) {
            messages.filter { message ->
                message.role.equals("user", ignoreCase = true) && message.id >= marker.messageId
            }
        } else {
            emptyList()
        },
    )
}

internal data class SessionRevertDraft(
    val text: String,
    val attachments: List<PromptAttachment>,
    val projectFileContexts: List<ProjectFileContext> = emptyList(),
    val failure: SessionRevertDraftFailure? = null,
) {
    val hasRecoverableContent: Boolean
        get() = text.isNotBlank() || attachments.isNotEmpty() || projectFileContexts.isNotEmpty()

    override fun toString(): String =
        "SessionRevertDraft(textLength=${text.length}, attachmentCount=${attachments.size}, " +
            "projectFileContextCount=${projectFileContexts.size}, " +
            "failure=$failure, hasRecoverableContent=$hasRecoverableContent)"
}

internal enum class SessionRevertDraftFailure {
    AttachmentCountLimit,
    AttachmentTotalSizeLimit,
    AttachmentTooLarge,
    AttachmentInvalid,
    ProjectContextCountLimit,
    ProjectContextInvalid,
}

internal fun OpenCodeMessage.toSessionRevertDraft(
    projectDirectory: String? = null,
    projectFileUrlBuilder: ProjectFileUrlBuilder = ProjectFileUrlBuilder(),
    projectFilePathNormalizer: ProjectFilePathNormalizer = ProjectFilePathNormalizer(),
): SessionRevertDraft {
    var budget = AttachmentBudget.from(emptyList())
    var failure: SessionRevertDraftFailure? = null
    val attachments = buildList {
        parts.filter(::isFileAttachmentPart).forEach { part ->
            if (budget.remainingCount == 0) {
                if (failure == null) failure = SessionRevertDraftFailure.AttachmentCountLimit
                return@forEach
            }
            val dataUrl = part.url ?: run {
                if (failure == null) failure = SessionRevertDraftFailure.AttachmentInvalid
                return@forEach
            }
            val payload = inspectBase64DataUrl(dataUrl)
            if (payload !is DataUrlPayloadInspection.Valid) {
                if (failure == null) {
                    failure = when (payload) {
                        DataUrlPayloadInspection.Invalid -> SessionRevertDraftFailure.AttachmentInvalid
                        DataUrlPayloadInspection.TooLarge -> SessionRevertDraftFailure.AttachmentTooLarge
                        is DataUrlPayloadInspection.Valid -> null
                    }
                }
                return@forEach
            }
            val attachment = PromptAttachment(
                id = part.id,
                filename = part.filename?.takeIf { it.isNotBlank() } ?: part.id,
                mime = part.mime?.takeIf { it.isNotBlank() }
                    ?: payload.mediaType
                    ?: "application/octet-stream",
                dataUrl = dataUrl,
                sizeBytes = payload.sizeBytes,
            )
            val update = budget.add(payload.sizeBytes)
            if (update.failure == null) {
                add(attachment)
                budget = update.budget
            } else if (failure == null) {
                failure = update.failure.toSessionRevertDraftFailure()
            }
        }
    }
    val projectFileContexts = if (projectDirectory.isNullOrBlank()) {
        emptyList()
    } else {
        val windowsSeparators = projectFilePathNormalizer.usesWindowsSeparators(projectDirectory)
        val seen = mutableSetOf<String>()
        buildList {
            projectFileContextParts(parts).forEach { part ->
                if (part.id.isBlank()) {
                    if (failure == null) failure = SessionRevertDraftFailure.ProjectContextInvalid
                    return@forEach
                }
                val relativePath = part.url
                    ?.let { projectFileUrlBuilder.relativePath(projectDirectory, it) }
                    ?: run {
                        if (failure == null) failure = SessionRevertDraftFailure.ProjectContextInvalid
                        return@forEach
                    }
                val comparisonPath = if (windowsSeparators) relativePath.lowercase() else relativePath
                if (!seen.add(comparisonPath)) {
                    if (failure == null) failure = SessionRevertDraftFailure.ProjectContextInvalid
                    return@forEach
                }
                if (size >= ProjectFileContextLimits.MAX_CONTEXT_COUNT) {
                    if (failure == null) failure = SessionRevertDraftFailure.ProjectContextCountLimit
                    return@forEach
                }
                add(ProjectFileContext(id = part.id, relativePath = relativePath))
            }
        }
    }
    return SessionRevertDraft(
        text = text,
        attachments = attachments,
        projectFileContexts = projectFileContexts,
        failure = failure,
    )
}

internal fun inspectBase64DataUrl(
    dataUrl: String,
    maxBytes: Long = AttachmentLimits.MAX_FILE_BYTES,
): DataUrlPayloadInspection = inspectPromptBase64DataUrl(dataUrl, maxBytes)

private fun AttachmentBudgetFailure.toSessionRevertDraftFailure(): SessionRevertDraftFailure = when (this) {
    AttachmentBudgetFailure.CountLimit -> SessionRevertDraftFailure.AttachmentCountLimit
    AttachmentBudgetFailure.TotalSizeLimit -> SessionRevertDraftFailure.AttachmentTotalSizeLimit
    AttachmentBudgetFailure.TooLarge -> SessionRevertDraftFailure.AttachmentTooLarge
}
