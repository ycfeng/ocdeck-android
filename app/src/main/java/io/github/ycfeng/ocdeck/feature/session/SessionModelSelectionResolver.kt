package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.data.server.ServerComposerModelPreference
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.feature.composer.toValidPromptModelSelection

internal fun resolveInitialPromptModelSelection(
    initialSelection: PromptModelSelection? = null,
    session: OpenCodeSession?,
    messages: List<OpenCodeMessage>,
    preference: ServerComposerModelPreference?,
    capabilities: PromptCapabilities,
): PromptModelSelection? {
    if (!capabilities.isLoaded) return null
    val models = capabilities.models
    initialSelection.toValidPromptModelSelection(models)?.let { return it }
    session?.explicitModelSelection()?.toValidPromptModelSelection(models)?.let { return it }

    val latestUserSelection = messages.asReversed()
        .asSequence()
        .filter { it.role.equals("user", ignoreCase = true) }
        .mapNotNull { it.explicitModelSelection().toValidPromptModelSelection(models) }
        .firstOrNull()
    if (latestUserSelection != null) return latestUserSelection

    val latestMessageSelection = messages.asReversed()
        .asSequence()
        .mapNotNull { it.explicitModelSelection().toValidPromptModelSelection(models) }
        .firstOrNull()
    if (latestMessageSelection != null) return latestMessageSelection

    preference.toValidPromptModelSelection(models)?.let { return it }

    return capabilities.serverDefaultModels
        .mapNotNull { it.toValidPromptModelSelection(models) }
        .distinctBy { it.providerId to it.modelId }
        .singleOrNull()
}

private fun OpenCodeSession.explicitModelSelection(): PromptModelSelection? {
    val label = modelLabel.modelLabelParts()
    val resolvedProviderId = modelProviderId?.takeIf { it.isNotBlank() } ?: label?.getOrNull(0)
    val resolvedModelId = modelId?.takeIf { it.isNotBlank() } ?: label?.getOrNull(1)
    if (resolvedProviderId.isNullOrBlank() || resolvedModelId.isNullOrBlank()) return null
    return PromptModelSelection(
        providerId = resolvedProviderId,
        modelId = resolvedModelId,
        variant = modelVariant?.takeIf { it.isNotBlank() } ?: label?.getOrNull(2),
    )
}

private fun OpenCodeMessage.explicitModelSelection(): PromptModelSelection? {
    val label = modelLabel.modelLabelParts()
    val resolvedProviderId = modelProviderId?.takeIf { it.isNotBlank() } ?: label?.getOrNull(0)
    val resolvedModelId = modelId?.takeIf { it.isNotBlank() } ?: label?.getOrNull(1)
    if (resolvedProviderId.isNullOrBlank() || resolvedModelId.isNullOrBlank()) return null
    return PromptModelSelection(
        providerId = resolvedProviderId,
        modelId = resolvedModelId,
        variant = modelVariant?.takeIf { it.isNotBlank() } ?: label?.getOrNull(2),
    )
}

private fun String?.modelLabelParts(): List<String>? = this
    ?.split('/')
    ?.map(String::trim)
    ?.takeIf { it.size >= 2 }
