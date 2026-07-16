package io.github.ycfeng.ocdeck.data.opencode

import io.github.ycfeng.ocdeck.data.server.ServerBaseUrlValidationException
import io.github.ycfeng.ocdeck.data.server.normalizeServerBaseUrl
import io.github.ycfeng.ocdeck.domain.model.CustomProviderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderHeaderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderModelDraft
import okhttp3.Headers

internal const val CUSTOM_PROVIDER_MAX_MODELS = 100
internal const val CUSTOM_PROVIDER_MAX_HEADERS = 50

internal enum class CustomProviderValidationFailure {
    ProviderId,
    DisplayName,
    BaseUrl,
    ApiKey,
    ModelsRequired,
    ModelsLimit,
    Model,
    DuplicateModel,
    HeadersLimit,
    Header,
    DuplicateHeader,
}

internal sealed interface CustomProviderValidationResult {
    data class Valid(val draft: CustomProviderDraft) : CustomProviderValidationResult
    data class Invalid(val failure: CustomProviderValidationFailure) : CustomProviderValidationResult
}

internal fun validateCustomProviderDraft(draft: CustomProviderDraft): CustomProviderValidationResult {
    val providerId = draft.providerId.trim()
    if (providerId.length !in 1..128 || !PROVIDER_ID.matches(providerId)) {
        return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.ProviderId)
    }
    if (draft.originalProviderId != null && draft.originalProviderId != providerId) {
        return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.ProviderId)
    }
    val displayName = draft.displayName.trim()
    if (displayName.length !in 1..200) {
        return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.DisplayName)
    }
    val baseUrl = try {
        normalizeServerBaseUrl(draft.baseUrl.trim())
    } catch (_: ServerBaseUrlValidationException) {
        return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.BaseUrl)
    }
    val apiKey = draft.apiKey?.takeIf(String::isNotBlank)
    if (apiKey != null && apiKey.length > 65_536) {
        return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.ApiKey)
    }
    if (draft.models.isEmpty()) {
        return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.ModelsRequired)
    }
    if (draft.models.size > CUSTOM_PROVIDER_MAX_MODELS) {
        return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.ModelsLimit)
    }
    val models = mutableListOf<CustomProviderModelDraft>()
    val modelIds = mutableSetOf<String>()
    for (model in draft.models) {
        val id = model.id.trim()
        val name = model.name.trim().ifEmpty { id }
        if (id.length !in 1..256 || name.length !in 1..256) {
            return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.Model)
        }
        if (!modelIds.add(id)) {
            return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.DuplicateModel)
        }
        models += CustomProviderModelDraft(id = id, name = name)
    }
    if (draft.headers.size > CUSTOM_PROVIDER_MAX_HEADERS) {
        return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.HeadersLimit)
    }
    val headers = mutableListOf<CustomProviderHeaderDraft>()
    val headerNames = mutableSetOf<String>()
    for (header in draft.headers) {
        val name = header.name.trim()
        val value = header.value?.takeIf(String::isNotBlank)
        if (
            name.length !in 1..256 ||
            (value != null && value.length > 8_192) ||
            (value == null && !header.retainExisting)
        ) {
            return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.Header)
        }
        if (!headerNames.add(name.lowercase())) {
            return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.DuplicateHeader)
        }
        try {
            Headers.Builder().add(name, value ?: "retained")
        } catch (_: IllegalArgumentException) {
            return CustomProviderValidationResult.Invalid(CustomProviderValidationFailure.Header)
        }
        headers += CustomProviderHeaderDraft(
            name = name,
            value = value,
            retainExisting = header.retainExisting,
        )
    }
    return CustomProviderValidationResult.Valid(
        draft.copy(
            providerId = providerId,
            displayName = displayName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            models = models,
            headers = headers,
        ),
    )
}

private val PROVIDER_ID = Regex("^[a-z0-9_-]+$")
