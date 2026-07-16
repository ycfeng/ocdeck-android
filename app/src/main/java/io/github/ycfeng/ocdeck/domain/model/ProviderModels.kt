package io.github.ycfeng.ocdeck.domain.model

import io.github.ycfeng.ocdeck.core.network.OpenCodeFailure

enum class OpenCodeProviderSource {
    Env,
    Config,
    Custom,
    Api,
    Unknown,
}

data class OpenCodeProviderSummary(
    val id: String,
    val name: String,
    val source: OpenCodeProviderSource,
    val isConnected: Boolean,
    val modelCount: Int,
    val isCustomConfigured: Boolean = false,
) {
    override fun toString(): String =
        "OpenCodeProviderSummary(id=<redacted>, name=<redacted>, source=$source, " +
            "isConnected=$isConnected, modelCount=$modelCount, isCustomConfigured=$isCustomConfigured)"
}

data class ProviderMutationOutcome(
    val instanceRefreshFailure: OpenCodeFailure? = null,
) {
    override fun toString(): String =
        "ProviderMutationOutcome(instanceRefreshFailurePresent=${instanceRefreshFailure != null})"
}

enum class ProviderAuthMethodType {
    Api,
    OAuth,
}

data class ProviderAuthMethod(
    val wireIndex: Int,
    val type: ProviderAuthMethodType,
    val label: String?,
    val prompts: List<ProviderAuthPrompt> = emptyList(),
) {
    fun visiblePrompts(inputs: Map<String, String>): List<ProviderAuthPrompt> =
        prompts.filter { prompt -> prompt.condition?.matches(inputs) != false }

    override fun toString(): String =
        "ProviderAuthMethod(wireIndex=$wireIndex, type=$type, labelPresent=${!label.isNullOrBlank()}, " +
            "promptCount=${prompts.size})"
}

sealed interface ProviderAuthPrompt {
    val key: String
    val message: String
    val condition: ProviderAuthCondition?
}

data class ProviderAuthTextPrompt(
    override val key: String,
    override val message: String,
    val placeholder: String? = null,
    override val condition: ProviderAuthCondition? = null,
) : ProviderAuthPrompt {
    override fun toString(): String =
        "ProviderAuthTextPrompt(key=<redacted>, messagePresent=${message.isNotBlank()}, " +
            "placeholderPresent=${!placeholder.isNullOrBlank()}, conditionPresent=${condition != null})"
}

data class ProviderAuthSelectPrompt(
    override val key: String,
    override val message: String,
    val options: List<ProviderAuthSelectOption>,
    override val condition: ProviderAuthCondition? = null,
) : ProviderAuthPrompt {
    override fun toString(): String =
        "ProviderAuthSelectPrompt(key=<redacted>, messagePresent=${message.isNotBlank()}, " +
            "optionCount=${options.size}, conditionPresent=${condition != null})"
}

data class ProviderAuthSelectOption(
    val label: String,
    val value: String,
    val hint: String? = null,
) {
    override fun toString(): String =
        "ProviderAuthSelectOption(labelPresent=${label.isNotBlank()}, value=<redacted>, " +
            "hintPresent=${!hint.isNullOrBlank()})"
}

data class ProviderAuthCondition(
    val key: String,
    val operator: ProviderAuthConditionOperator,
    val value: String,
) {
    fun matches(inputs: Map<String, String>): Boolean {
        val current = inputs[key] ?: return false
        return when (operator) {
            ProviderAuthConditionOperator.Equals -> current == value
            ProviderAuthConditionOperator.NotEquals -> current != value
        }
    }

    override fun toString(): String =
        "ProviderAuthCondition(key=<redacted>, operator=$operator, value=<redacted>)"
}

enum class ProviderAuthConditionOperator {
    Equals,
    NotEquals,
}

data class ProviderOAuthAuthorization(
    val url: String,
    val mode: ProviderOAuthMode,
    val instructions: String,
    val usesLoopbackUrl: Boolean,
) {
    override fun toString(): String =
        "ProviderOAuthAuthorization(url=<redacted>, mode=$mode, instructions=<redacted>, " +
            "usesLoopbackUrl=$usesLoopbackUrl)"
}

enum class ProviderOAuthMode {
    Auto,
    Code,
}

data class OpenCodeCustomProviderConfiguration(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<OpenCodeCustomProviderModel>,
    val headers: List<OpenCodeCustomProviderHeader>,
    val isDisabled: Boolean,
) {
    override fun toString(): String =
        "OpenCodeCustomProviderConfiguration(id=<redacted>, name=<redacted>, baseUrl=<redacted>, " +
            "modelCount=${models.size}, headerCount=${headers.size}, isDisabled=$isDisabled)"
}

data class OpenCodeCustomProviderModel(
    val id: String,
    val name: String,
) {
    override fun toString(): String =
        "OpenCodeCustomProviderModel(id=<redacted>, name=<redacted>)"
}

data class OpenCodeCustomProviderHeader(
    val name: String,
) {
    override fun toString(): String =
        "OpenCodeCustomProviderHeader(name=<redacted>)"
}

data class CustomProviderDraft(
    val originalProviderId: String?,
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String?,
    val models: List<CustomProviderModelDraft>,
    val headers: List<CustomProviderHeaderDraft>,
) {
    val containsNewSecrets: Boolean
        get() = !apiKey.isNullOrBlank() || headers.any { !it.value.isNullOrBlank() }

    override fun toString(): String =
        "CustomProviderDraft(originalProviderIdPresent=${originalProviderId != null}, providerId=<redacted>, " +
            "displayName=<redacted>, baseUrl=<redacted>, apiKey=${if (apiKey == null) "null" else "<redacted>"}, " +
            "modelCount=${models.size}, headerCount=${headers.size})"
}

data class CustomProviderModelDraft(
    val id: String,
    val name: String,
) {
    override fun toString(): String =
        "CustomProviderModelDraft(id=<redacted>, name=<redacted>)"
}

data class CustomProviderHeaderDraft(
    val name: String,
    val value: String?,
    val retainExisting: Boolean = false,
) {
    override fun toString(): String =
        "CustomProviderHeaderDraft(name=<redacted>, value=${if (value == null) "null" else "<redacted>"}, " +
            "retainExisting=$retainExisting)"
}

enum class CustomProviderCommitState {
    Enabled,
    Disabled,
    Unknown,
}

data class CustomProviderMutationOutcome(
    val commitState: CustomProviderCommitState,
    val operationFailure: OpenCodeFailure? = null,
    val credentialCleanupFailure: OpenCodeFailure? = null,
    val instanceRefreshFailure: OpenCodeFailure? = null,
) {
    override fun toString(): String =
        "CustomProviderMutationOutcome(commitState=$commitState, " +
            "operationFailurePresent=${operationFailure != null}, " +
            "credentialCleanupFailurePresent=${credentialCleanupFailure != null}, " +
            "instanceRefreshFailurePresent=${instanceRefreshFailure != null})"
}
