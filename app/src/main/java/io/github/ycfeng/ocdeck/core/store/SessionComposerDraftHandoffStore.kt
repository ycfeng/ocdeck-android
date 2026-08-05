package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachment
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContext
import java.util.concurrent.ConcurrentHashMap

data class SessionComposerDraftHandoff(
    val text: String,
    val attachments: List<PromptAttachment>,
    val projectFileContexts: List<ProjectFileContext>,
    val selectedAgentId: String?,
    val selectedModel: PromptModelSelection?,
    val selectedVariant: String?,
) {
    override fun toString(): String =
        "SessionComposerDraftHandoff(textLength=${text.length}, attachmentCount=${attachments.size}, " +
            "projectFileContextCount=${projectFileContexts.size}, selectedAgentPresent=${selectedAgentId != null}, " +
            "selectedModelPresent=${selectedModel != null}, selectedVariantPresent=${selectedVariant != null})"
}

class SessionComposerDraftHandoffStore(
    private val pathNormalizer: PathNormalizer,
) {
    private val drafts = ConcurrentHashMap<Key, SessionComposerDraftHandoff>()

    fun put(
        serverId: String,
        directory: String,
        workspace: String?,
        sessionId: String,
        draft: SessionComposerDraftHandoff,
    ) {
        drafts[key(serverId, directory, workspace, sessionId)] = draft
    }

    fun consume(
        serverId: String,
        directory: String,
        workspace: String?,
        sessionId: String,
    ): SessionComposerDraftHandoff? = drafts.remove(key(serverId, directory, workspace, sessionId))

    private fun key(serverId: String, directory: String, workspace: String?, sessionId: String): Key = Key(
        serverId = serverId,
        directory = pathNormalizer.comparisonKey(pathNormalizer.normalize(directory)),
        workspace = workspace
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(pathNormalizer::normalize)
            ?.let(pathNormalizer::comparisonKey),
        sessionId = sessionId,
    )

    private data class Key(
        val serverId: String,
        val directory: String,
        val workspace: String?,
        val sessionId: String,
    ) {
        override fun toString(): String =
            "SessionComposerDraftHandoffKey(serverId=<redacted>, directory=<redacted>, " +
                "workspace=${if (workspace == null) "null" else "<redacted>"}, sessionId=<redacted>)"
    }
}
