package io.github.ycfeng.ocdeck.feature.composer

import io.github.ycfeng.ocdeck.data.server.ServerComposerModelPreference
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import java.util.Locale

internal fun ServerComposerModelPreference?.toValidPromptModelSelection(models: List<OpenCodeModel>): PromptModelSelection? {
    val preference = this ?: return null
    if (preference.providerId.isBlank() || preference.modelId.isBlank()) return null
    return PromptModelSelection(
        providerId = preference.providerId,
        modelId = preference.modelId,
        variant = preference.variant,
    ).toValidPromptModelSelection(models)
}

internal fun PromptModelSelection.findModel(models: List<OpenCodeModel>): OpenCodeModel? = models.firstOrNull {
    providerId.isNotBlank() &&
        modelId.isNotBlank() &&
        it.providerId == providerId &&
        it.modelId == modelId &&
        it.isConnected &&
        it.isEnabled
}

internal fun PromptModelSelection?.toValidPromptModelSelection(models: List<OpenCodeModel>): PromptModelSelection? {
    val selection = this ?: return null
    val model = selection.findModel(models) ?: return null
    return selection.copy(variant = selection.variant?.takeIf { it in model.variants })
}

internal fun PromptModelSelection?.selectPromptModel(model: OpenCodeModel): PromptModelSelection = PromptModelSelection(
    providerId = model.providerId,
    modelId = model.modelId,
    variant = this?.variant?.takeIf { it in model.variants },
)

internal fun PromptModelSelection.selectPromptVariant(
    variant: String?,
    models: List<OpenCodeModel>,
): PromptModelSelection {
    val model = findModel(models)
    return copy(variant = variant?.takeIf { model?.variants?.contains(it) == true })
}

internal fun String.toVariantDisplayLabel(): String = replaceFirstChar { char ->
    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
}
