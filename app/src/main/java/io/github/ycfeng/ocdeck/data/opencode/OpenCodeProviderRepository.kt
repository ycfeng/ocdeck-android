package io.github.ycfeng.ocdeck.data.opencode

import io.github.ycfeng.ocdeck.core.network.OpenCodeFailure
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailureClassifier
import io.github.ycfeng.ocdeck.core.network.OpenCodeRequestException
import io.github.ycfeng.ocdeck.core.network.ProviderApiAuthRequestDto
import io.github.ycfeng.ocdeck.core.network.ProviderOAuthAuthorizeRequestDto
import io.github.ycfeng.ocdeck.core.network.ProviderOAuthCallbackRequestDto
import io.github.ycfeng.ocdeck.core.util.toSafeExternalHttpUrlOrNull
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.server.OpenCodeServerRepository
import io.github.ycfeng.ocdeck.data.server.isNonLoopbackHttpServerBaseUrl
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthCondition
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthConditionOperator
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthMethod
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthMethodType
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthPrompt
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthSelectOption
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthSelectPrompt
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthTextPrompt
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProviderSource
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProviderSummary
import io.github.ycfeng.ocdeck.domain.model.ProviderMutationOutcome
import io.github.ycfeng.ocdeck.domain.model.ProviderOAuthAuthorization
import io.github.ycfeng.ocdeck.domain.model.ProviderOAuthMode
import io.github.ycfeng.ocdeck.domain.model.CustomProviderCommitState
import io.github.ycfeng.ocdeck.domain.model.CustomProviderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderMutationOutcome
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCustomProviderConfiguration
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCustomProviderHeader
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCustomProviderModel
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

interface ProviderSettingsGateway {
    suspend fun loadProviders(
        serverId: String,
        directory: String? = null,
        workspace: String? = null,
    ): Result<List<OpenCodeProviderSummary>>

    suspend fun requiresCleartextSecretConfirmation(serverId: String): Result<Boolean>

    suspend fun loadAuthMethods(
        serverId: String,
        directory: String? = null,
        workspace: String? = null,
        providerId: String,
    ): Result<List<ProviderAuthMethod>>

    suspend fun connectApiKey(
        serverId: String,
        providerId: String,
        apiKey: String,
        metadata: Map<String, String> = emptyMap(),
    ): Result<ProviderMutationOutcome>

    suspend fun disconnectProvider(
        serverId: String,
        providerId: String,
    ): Result<ProviderMutationOutcome>

    suspend fun authorizeOAuth(
        serverId: String,
        directory: String? = null,
        workspace: String? = null,
        providerId: String,
        method: Int,
        inputs: Map<String, String> = emptyMap(),
    ): Result<ProviderOAuthAuthorization>

    suspend fun completeOAuth(
        serverId: String,
        directory: String? = null,
        workspace: String? = null,
        providerId: String,
        method: Int,
        code: String? = null,
    ): Result<ProviderMutationOutcome>

    suspend fun loadCustomProvider(
        serverId: String,
        providerId: String,
    ): Result<OpenCodeCustomProviderConfiguration?>

    suspend fun saveCustomProvider(
        serverId: String,
        draft: CustomProviderDraft,
    ): Result<CustomProviderMutationOutcome>

    suspend fun disableCustomProvider(
        serverId: String,
        providerId: String,
    ): Result<CustomProviderMutationOutcome>
}

class OpenCodeProviderRepository(
    private val serverRepository: OpenCodeServerRepository,
    private val pathNormalizer: PathNormalizer,
    private val json: Json,
) : ProviderSettingsGateway {
    override suspend fun loadProviders(
        serverId: String,
        directory: String?,
        workspace: String?,
    ): Result<List<OpenCodeProviderSummary>> = catching {
        val normalizedDirectory = directory
            ?.takeIf(String::isNotBlank)
            ?.let(pathNormalizer::normalize)
        val connection = serverRepository.getConnection(serverId)
        val providers = connection.api
            .getProviders(normalizedDirectory, workspace)
            .toSafeProviderSummaries()
        val globalConfig = try {
            connection.api.getGlobalConfig().toSafeGlobalProviderConfig()
        } catch (throwable: Throwable) {
            OpenCodeFailureClassifier.classify(throwable)
            null
        }
        providers.mergeCustomProviders(globalConfig)
    }

    override suspend fun requiresCleartextSecretConfirmation(serverId: String): Result<Boolean> = catching {
        val connection = serverRepository.getConnection(serverId)
        connection.server.sshTunnel == null &&
            connection.server.frpcStcpVisitor == null &&
            isNonLoopbackHttpServerBaseUrl(connection.server.baseUrl)
    }

    override suspend fun loadAuthMethods(
        serverId: String,
        directory: String?,
        workspace: String?,
        providerId: String,
    ): Result<List<ProviderAuthMethod>> = catching {
        requireOperation(providerId.isNotBlank())
        val normalizedDirectory = normalizeDirectory(directory)
        serverRepository.getConnection(serverId).api
            .getProviderAuthMethods(normalizedDirectory, workspace)
            .toSafeProviderAuthMethods(providerId)
    }

    override suspend fun connectApiKey(
        serverId: String,
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Result<ProviderMutationOutcome> = catching {
        requireOperation(providerId.isNotBlank() && apiKey.isNotBlank())
        val connection = serverRepository.getConnection(serverId)
        val saved = connection.api.putProviderAuth(
            providerId = providerId,
            body = ProviderApiAuthRequestDto(
                key = apiKey,
                metadata = metadata.takeIf(Map<String, String>::isNotEmpty),
            ),
        )
        if (!saved) operationRejected()
        ProviderMutationOutcome(instanceRefreshFailure = disposeFailure(connection.api::disposeGlobalInstances))
    }

    override suspend fun disconnectProvider(
        serverId: String,
        providerId: String,
    ): Result<ProviderMutationOutcome> = catching {
        requireOperation(providerId.isNotBlank())
        val connection = serverRepository.getConnection(serverId)
        if (!connection.api.deleteProviderAuth(providerId)) operationRejected()
        ProviderMutationOutcome(instanceRefreshFailure = disposeFailure(connection.api::disposeGlobalInstances))
    }

    override suspend fun authorizeOAuth(
        serverId: String,
        directory: String?,
        workspace: String?,
        providerId: String,
        method: Int,
        inputs: Map<String, String>,
    ): Result<ProviderOAuthAuthorization> = catching {
        requireOperation(providerId.isNotBlank() && method >= 0)
        val connection = serverRepository.getConnection(serverId)
        connection.api.authorizeProviderOAuth(
            providerId = providerId,
            directory = normalizeDirectory(directory),
            workspace = workspace,
            body = ProviderOAuthAuthorizeRequestDto(
                method = method,
                inputs = inputs.takeIf(Map<String, String>::isNotEmpty),
            ),
        ).use { body ->
            val text = body.string().trim()
            if (text.isEmpty() || text == "null") invalidResponse()
            val element = try {
                json.parseToJsonElement(text)
            } catch (_: Exception) {
                invalidResponse()
            }
            element.toSafeOAuthAuthorization()
        }
    }

    override suspend fun completeOAuth(
        serverId: String,
        directory: String?,
        workspace: String?,
        providerId: String,
        method: Int,
        code: String?,
    ): Result<ProviderMutationOutcome> = catching {
        requireOperation(providerId.isNotBlank() && method >= 0)
        val connection = serverRepository.getConnection(serverId)
        val completed = connection.providerOAuthApi.completeProviderOAuth(
            providerId = providerId,
            directory = normalizeDirectory(directory),
            workspace = workspace,
            body = ProviderOAuthCallbackRequestDto(
                method = method,
                code = code?.takeIf(String::isNotBlank),
            ),
        )
        if (!completed) operationRejected()
        ProviderMutationOutcome(instanceRefreshFailure = disposeFailure(connection.api::disposeGlobalInstances))
    }

    override suspend fun loadCustomProvider(
        serverId: String,
        providerId: String,
    ): Result<OpenCodeCustomProviderConfiguration?> = catching {
        requireOperation(providerId.isNotBlank())
        serverRepository.getConnection(serverId).api
            .getGlobalConfig()
            .toSafeGlobalProviderConfig()
            .customProviders[providerId]
    }

    override suspend fun saveCustomProvider(
        serverId: String,
        draft: CustomProviderDraft,
    ): Result<CustomProviderMutationOutcome> = catching {
        val validated = when (val validation = validateCustomProviderDraft(draft)) {
            is CustomProviderValidationResult.Valid -> validation.draft
            is CustomProviderValidationResult.Invalid -> operationRejected()
        }
        val connection = serverRepository.getConnection(serverId)
        val config = connection.api.getGlobalConfig().toSafeGlobalProviderConfig()
        val originalProviderId = validated.originalProviderId
        if (originalProviderId == null && validated.providerId in config.configuredProviderIds) {
            operationRejected()
        }
        if (originalProviderId != null && originalProviderId !in config.customProviders) {
            operationRejected()
        }
        if (originalProviderId != null) {
            val existing = config.customProviders.getValue(originalProviderId)
            val requestedModelIds = validated.models.map { it.id }.toSet()
            val requestedHeaderNames = validated.headers.map { it.name.lowercase() }.toSet()
            if (existing.models.any { it.id !in requestedModelIds }) operationRejected()
            if (existing.headers.any { it.name.lowercase() !in requestedHeaderNames }) operationRejected()
            if (
                validated.headers.any { header ->
                    header.retainExisting && existing.headers.none { it.name.equals(header.name, ignoreCase = true) }
                }
            ) {
                operationRejected()
            }
        }

        val stagedDisabled = config.disabledProviderIds + validated.providerId
        val stageFailure = mutationFailure {
            connection.api.updateGlobalConfig(validated.toStagedConfigPatch(stagedDisabled))
        }
        if (stageFailure != null) {
            return@catching CustomProviderMutationOutcome(
                commitState = CustomProviderCommitState.Unknown,
                operationFailure = stageFailure,
            )
        }

        val credentialFailure = validated.apiKey?.let { apiKey ->
            mutationFailure {
                if (!connection.api.putProviderAuth(
                        providerId = validated.providerId,
                        body = ProviderApiAuthRequestDto(key = apiKey),
                    )
                ) {
                    operationRejected()
                }
            }
        }
        if (credentialFailure != null) {
            return@catching CustomProviderMutationOutcome(
                commitState = CustomProviderCommitState.Disabled,
                operationFailure = credentialFailure,
                instanceRefreshFailure = disposeFailure(connection.api::disposeGlobalInstances),
            )
        }

        val enableFailure = mutationFailure {
            connection.api.updateGlobalConfig(
                disabledProvidersPatch(stagedDisabled - validated.providerId),
            )
        }
        if (enableFailure != null) {
            return@catching CustomProviderMutationOutcome(
                commitState = CustomProviderCommitState.Unknown,
                operationFailure = enableFailure,
                instanceRefreshFailure = disposeFailure(connection.api::disposeGlobalInstances),
            )
        }
        CustomProviderMutationOutcome(
            commitState = CustomProviderCommitState.Enabled,
            instanceRefreshFailure = disposeFailure(connection.api::disposeGlobalInstances),
        )
    }

    override suspend fun disableCustomProvider(
        serverId: String,
        providerId: String,
    ): Result<CustomProviderMutationOutcome> = catching {
        requireOperation(providerId.isNotBlank())
        val connection = serverRepository.getConnection(serverId)
        val config = connection.api.getGlobalConfig().toSafeGlobalProviderConfig()
        if (providerId !in config.customProviders) operationRejected()
        val disableFailure = mutationFailure {
            connection.api.updateGlobalConfig(
                disabledProvidersPatch(config.disabledProviderIds + providerId),
            )
        }
        if (disableFailure != null) {
            return@catching CustomProviderMutationOutcome(
                commitState = CustomProviderCommitState.Unknown,
                operationFailure = disableFailure,
            )
        }
        val cleanupFailure = mutationFailure {
            if (!connection.api.deleteProviderAuth(providerId)) operationRejected()
        }
        CustomProviderMutationOutcome(
            commitState = CustomProviderCommitState.Disabled,
            credentialCleanupFailure = cleanupFailure,
            instanceRefreshFailure = disposeFailure(connection.api::disposeGlobalInstances),
        )
    }

    private fun normalizeDirectory(directory: String?): String? =
        directory?.takeIf(String::isNotBlank)?.let(pathNormalizer::normalize)

    private suspend fun disposeFailure(dispose: suspend () -> Boolean): OpenCodeFailure? = try {
        if (dispose()) null else OpenCodeFailure.OperationRejected()
    } catch (throwable: Throwable) {
        OpenCodeFailureClassifier.classify(throwable)
    }

    private suspend fun mutationFailure(block: suspend () -> Unit): OpenCodeFailure? = try {
        block()
        null
    } catch (throwable: Throwable) {
        OpenCodeFailureClassifier.classify(throwable)
    }

    private suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (throwable: Throwable) {
        Result.failure(OpenCodeFailureClassifier.toRequestException(throwable))
    }

    private fun requireOperation(condition: Boolean) {
        if (!condition) operationRejected()
    }

    private fun operationRejected(): Nothing =
        throw OpenCodeRequestException(OpenCodeFailure.OperationRejected())

    private fun invalidResponse(): Nothing =
        throw OpenCodeRequestException(OpenCodeFailure.InvalidResponse)
}

internal fun JsonElement.toSafeProviderSummaries(): List<OpenCodeProviderSummary> {
    val root = this as? JsonObject
        ?: throw OpenCodeRequestException(OpenCodeFailure.InvalidResponse)
    val entries = root.providerEntries()
    val connected = root.connectedProviderIds(entries)
    return entries.mapNotNull { (fallbackId, element) ->
        val provider = element as? JsonObject ?: return@mapNotNull null
        val id = provider.string("id")?.takeIf(String::isNotBlank) ?: fallbackId.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val name = provider.string("name")?.takeIf(String::isNotBlank) ?: id
        val models = provider["models"]
        OpenCodeProviderSummary(
            id = id,
            name = name,
            source = provider.string("source").toProviderSource(),
            isConnected = id in connected,
            modelCount = when (models) {
                is JsonObject -> models.size
                is JsonArray -> models.count { it is JsonObject }
                else -> 0
            },
        )
    }.distinctBy(OpenCodeProviderSummary::id)
}

private fun JsonObject.providerEntries(): List<Pair<String, JsonElement>> {
    val all = this["all"]
    if (all is JsonArray) {
        return all.mapIndexed { index, provider ->
            val id = (provider as? JsonObject)?.string("id").orEmpty()
            (id.ifBlank { "provider-$index" }) to provider
        }
    }
    if (all is JsonObject) return all.entries.map { it.key to it.value }

    val providers = this["providers"]
    if (providers is JsonArray) {
        return providers.mapIndexed { index, provider ->
            val id = (provider as? JsonObject)?.string("id").orEmpty()
            (id.ifBlank { "provider-$index" }) to provider
        }
    }
    if (providers is JsonObject) return providers.entries.map { it.key to it.value }
    return emptyList()
}

private fun JsonObject.connectedProviderIds(entries: List<Pair<String, JsonElement>>): Set<String> {
    return when (val connected = this["connected"]) {
        is JsonArray -> connected.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        is JsonObject -> connected.mapNotNull { (id, value) ->
            val enabled = (value as? JsonPrimitive)?.booleanOrNull
            id.takeIf { enabled != false }
        }.toSet()
        else -> if ("providers" in this && "all" !in this) {
            entries.mapNotNull { (fallbackId, value) ->
                (value as? JsonObject)?.string("id")?.takeIf(String::isNotBlank)
                    ?: fallbackId.takeIf(String::isNotBlank)
            }.toSet()
        } else {
            emptySet()
        }
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonElement.toSafeProviderAuthMethods(providerId: String): List<ProviderAuthMethod> {
    val root = this as? JsonObject
        ?: throw OpenCodeRequestException(OpenCodeFailure.InvalidResponse)
    val methods = root[providerId]
    if (methods == null || methods is JsonNull) return listOf(fallbackApiAuthMethod())
    val entries = methods as? JsonArray
        ?: throw OpenCodeRequestException(OpenCodeFailure.InvalidResponse)
    val parsed = entries.mapIndexedNotNull { wireIndex, element ->
        (element as? JsonObject)?.toSafeProviderAuthMethod(wireIndex)
    }
    return parsed.ifEmpty { listOf(fallbackApiAuthMethod()) }
}

private fun JsonObject.toSafeProviderAuthMethod(wireIndex: Int): ProviderAuthMethod? {
    val type = when (string("type")?.lowercase()) {
        "api" -> ProviderAuthMethodType.Api
        "oauth" -> ProviderAuthMethodType.OAuth
        else -> return null
    }
    val prompts = (this["prompts"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.toSafeProviderAuthPrompt() }
        .orEmpty()
    return ProviderAuthMethod(
        wireIndex = wireIndex,
        type = type,
        label = string("label")?.takeIf(String::isNotBlank),
        prompts = prompts,
    )
}

private fun JsonObject.toSafeProviderAuthPrompt(): ProviderAuthPrompt? {
    val key = string("key")?.takeIf(String::isNotBlank) ?: return null
    val message = string("message")?.takeIf(String::isNotBlank) ?: return null
    val condition = (this["when"] as? JsonObject)?.toSafeProviderAuthCondition()
    return when (string("type")?.lowercase()) {
        "text" -> ProviderAuthTextPrompt(
            key = key,
            message = message,
            placeholder = string("placeholder")?.takeIf(String::isNotBlank),
            condition = condition,
        )
        "select" -> {
            val options = (this["options"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.toSafeProviderAuthSelectOption() }
                .orEmpty()
            if (options.isEmpty()) null else ProviderAuthSelectPrompt(
                key = key,
                message = message,
                options = options,
                condition = condition,
            )
        }
        else -> null
    }
}

private fun JsonObject.toSafeProviderAuthSelectOption(): ProviderAuthSelectOption? {
    val label = string("label")?.takeIf(String::isNotBlank) ?: return null
    val value = string("value") ?: return null
    return ProviderAuthSelectOption(
        label = label,
        value = value,
        hint = string("hint")?.takeIf(String::isNotBlank),
    )
}

private fun JsonObject.toSafeProviderAuthCondition(): ProviderAuthCondition? {
    val key = string("key")?.takeIf(String::isNotBlank) ?: return null
    val value = string("value") ?: return null
    val operator = when (string("op")?.lowercase()) {
        "eq" -> ProviderAuthConditionOperator.Equals
        "neq" -> ProviderAuthConditionOperator.NotEquals
        else -> return null
    }
    return ProviderAuthCondition(key = key, operator = operator, value = value)
}

private fun fallbackApiAuthMethod() = ProviderAuthMethod(
    wireIndex = 0,
    type = ProviderAuthMethodType.Api,
    label = null,
)

internal fun JsonElement.toSafeOAuthAuthorization(): ProviderOAuthAuthorization {
    val root = this as? JsonObject
        ?: throw OpenCodeRequestException(OpenCodeFailure.InvalidResponse)
    val safeUrl = root.string("url")?.toSafeExternalHttpUrlOrNull()
        ?: throw OpenCodeRequestException(OpenCodeFailure.InvalidResponse)
    val mode = when (root.string("method")?.lowercase()) {
        "auto" -> ProviderOAuthMode.Auto
        "code" -> ProviderOAuthMode.Code
        else -> throw OpenCodeRequestException(OpenCodeFailure.InvalidResponse)
    }
    val instructions = root.string("instructions").orEmpty()
    return ProviderOAuthAuthorization(
        url = safeUrl.value,
        mode = mode,
        instructions = instructions,
        usesLoopbackUrl = safeUrl.usesLoopbackHost,
    )
}

private data class SafeGlobalProviderConfig(
    val configuredProviderIds: Set<String>,
    val disabledProviderIds: Set<String>,
    val customProviders: Map<String, OpenCodeCustomProviderConfiguration>,
) {
    override fun toString(): String =
        "SafeGlobalProviderConfig(configuredProviderCount=${configuredProviderIds.size}, " +
            "disabledProviderCount=${disabledProviderIds.size}, customProviderCount=${customProviders.size})"
}

private fun JsonElement.toSafeGlobalProviderConfig(): SafeGlobalProviderConfig {
    val root = this as? JsonObject
        ?: throw OpenCodeRequestException(OpenCodeFailure.InvalidResponse)
    val disabledProviderIds = (root["disabled_providers"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        ?.toSet()
        .orEmpty()
    val providers = root["provider"] as? JsonObject ?: JsonObject(emptyMap())
    val customProviders = providers.mapNotNull { (fallbackId, element) ->
        val provider = element as? JsonObject ?: return@mapNotNull null
        if (provider.string("npm") != OPENAI_COMPATIBLE_NPM) return@mapNotNull null
        val id = provider.string("id")?.takeIf(String::isNotBlank) ?: fallbackId.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val options = provider["options"] as? JsonObject
        val models = (provider["models"] as? JsonObject)
            ?.map { (modelId, modelElement) ->
                val model = modelElement as? JsonObject
                OpenCodeCustomProviderModel(
                    id = modelId,
                    name = model?.string("name")?.takeIf(String::isNotBlank) ?: modelId,
                )
            }
            .orEmpty()
        val headers = (options?.get("headers") as? JsonObject)
            ?.keys
            ?.filter(String::isNotBlank)
            ?.map(::OpenCodeCustomProviderHeader)
            .orEmpty()
        id to OpenCodeCustomProviderConfiguration(
            id = id,
            name = provider.string("name")?.takeIf(String::isNotBlank) ?: id,
            baseUrl = options?.string("baseURL").orEmpty(),
            models = models,
            headers = headers,
            isDisabled = id in disabledProviderIds,
        )
    }.toMap()
    return SafeGlobalProviderConfig(
        configuredProviderIds = providers.keys,
        disabledProviderIds = disabledProviderIds,
        customProviders = customProviders,
    )
}

private fun List<OpenCodeProviderSummary>.mergeCustomProviders(
    config: SafeGlobalProviderConfig?,
): List<OpenCodeProviderSummary> {
    if (config == null || config.customProviders.isEmpty()) return this
    val merged = linkedMapOf<String, OpenCodeProviderSummary>()
    for (provider in this) {
        merged[provider.id] = if (provider.id in config.customProviders) {
            provider.copy(isCustomConfigured = true)
        } else {
            provider
        }
    }
    for ((id, custom) in config.customProviders) {
        if (id !in merged) {
            merged[id] = OpenCodeProviderSummary(
                id = id,
                name = custom.name,
                source = OpenCodeProviderSource.Config,
                isConnected = false,
                modelCount = custom.models.size,
                isCustomConfigured = true,
            )
        }
    }
    return merged.values.toList()
}

private fun CustomProviderDraft.toStagedConfigPatch(disabledProviders: Set<String>): JsonObject =
    buildJsonObject {
        put(
            "provider",
            buildJsonObject {
                put(
                    providerId,
                    buildJsonObject {
                        put("npm", JsonPrimitive(OPENAI_COMPATIBLE_NPM))
                        put("name", JsonPrimitive(displayName))
                        put(
                            "options",
                            buildJsonObject {
                                put("baseURL", JsonPrimitive(baseUrl))
                                val changedHeaders = headers.filter { !it.value.isNullOrBlank() }
                                if (changedHeaders.isNotEmpty()) {
                                    put(
                                        "headers",
                                        buildJsonObject {
                                            changedHeaders.forEach { header ->
                                                put(header.name, JsonPrimitive(requireNotNull(header.value)))
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "models",
                            buildJsonObject {
                                models.forEach { model ->
                                    put(
                                        model.id,
                                        buildJsonObject {
                                            put("name", JsonPrimitive(model.name))
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )
        put("disabled_providers", disabledProviderArray(disabledProviders))
    }

private fun disabledProvidersPatch(disabledProviders: Set<String>): JsonObject = buildJsonObject {
    put("disabled_providers", disabledProviderArray(disabledProviders))
}

private fun disabledProviderArray(disabledProviders: Set<String>): JsonArray = buildJsonArray {
    disabledProviders.sorted().forEach { add(JsonPrimitive(it)) }
}

private fun String?.toProviderSource(): OpenCodeProviderSource = when (this?.lowercase()) {
    "env" -> OpenCodeProviderSource.Env
    "config" -> OpenCodeProviderSource.Config
    "custom" -> OpenCodeProviderSource.Custom
    "api" -> OpenCodeProviderSource.Api
    else -> OpenCodeProviderSource.Unknown
}

private const val OPENAI_COMPATIBLE_NPM = "@ai-sdk/openai-compatible"
