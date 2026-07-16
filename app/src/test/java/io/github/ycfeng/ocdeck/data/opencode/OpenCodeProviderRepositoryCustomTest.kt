package io.github.ycfeng.ocdeck.data.opencode

import io.github.ycfeng.ocdeck.core.network.OpenCodeApi
import io.github.ycfeng.ocdeck.core.network.ProviderApiAuthRequestDto
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.server.OpenCodeServerRepository
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerConnection
import io.github.ycfeng.ocdeck.data.server.ServerHiddenModelPreference
import io.github.ycfeng.ocdeck.domain.model.CustomProviderCommitState
import io.github.ycfeng.ocdeck.domain.model.CustomProviderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderHeaderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderModelDraft
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class OpenCodeProviderRepositoryCustomTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun saveStagesDisabledThenWritesCredentialAndEnables() = runTest {
        val api = RecordingProviderApi(globalConfig = emptyGlobalConfig())
        val repository = repository(api.proxy)
        val apiKey = "synthetic-provider-key"

        val outcome = repository.saveCustomProvider(
            SERVER_ID,
            draft(
                apiKey = apiKey,
                headers = listOf(CustomProviderHeaderDraft("X-Synthetic", "synthetic-header-value")),
            ),
        ).getOrThrow()

        assertEquals(CustomProviderCommitState.Enabled, outcome.commitState)
        assertNull(outcome.operationFailure)
        assertEquals(
            listOf("getGlobalConfig", "updateGlobalConfig", "putProviderAuth", "updateGlobalConfig", "disposeGlobalInstances"),
            api.calls,
        )
        assertEquals(setOf("existing-disabled", PROVIDER_ID), api.updateBodies[0].disabledProviders())
        assertEquals(setOf("existing-disabled"), api.updateBodies[1].disabledProviders())
        assertEquals(apiKey, api.authWrites.single().key)
        assertFalse(api.updateBodies[0].toString().contains(apiKey))
    }

    @Test
    fun credentialFailureLeavesStagedConfigurationDisabled() = runTest {
        val api = RecordingProviderApi(globalConfig = emptyGlobalConfig(), putAuthResult = false)
        val repository = repository(api.proxy)

        val outcome = repository.saveCustomProvider(
            SERVER_ID,
            draft(apiKey = "synthetic-provider-key"),
        ).getOrThrow()

        assertEquals(CustomProviderCommitState.Disabled, outcome.commitState)
        assertNotNull(outcome.operationFailure)
        assertEquals(
            listOf("getGlobalConfig", "updateGlobalConfig", "putProviderAuth", "disposeGlobalInstances"),
            api.calls,
        )
        assertEquals(setOf("existing-disabled", PROVIDER_ID), api.updateBodies.single().disabledProviders())
    }

    @Test
    fun disableCommitsConfigurationBeforeBestEffortCredentialCleanup() = runTest {
        val api = RecordingProviderApi(globalConfig = customGlobalConfig(), deleteAuthResult = false)
        val repository = repository(api.proxy)

        val outcome = repository.disableCustomProvider(SERVER_ID, PROVIDER_ID).getOrThrow()

        assertEquals(CustomProviderCommitState.Disabled, outcome.commitState)
        assertNotNull(outcome.credentialCleanupFailure)
        assertEquals(
            listOf("getGlobalConfig", "updateGlobalConfig", "deleteProviderAuth", "disposeGlobalInstances"),
            api.calls,
        )
        assertEquals(setOf(PROVIDER_ID), api.updateBodies.single().disabledProviders())
    }

    @Test
    fun customProjectionDropsApiKeysAndHeaderValues() = runTest {
        val apiKey = "synthetic-config-api-key"
        val headerValue = "synthetic-config-header-value"
        val api = RecordingProviderApi(globalConfig = customGlobalConfig(apiKey, headerValue))
        val repository = repository(api.proxy)

        val configuration = repository.loadCustomProvider(SERVER_ID, PROVIDER_ID).getOrThrow()

        assertNotNull(configuration)
        requireNotNull(configuration)
        assertEquals(listOf("Authorization"), configuration.headers.map { it.name })
        assertFalse(configuration.toString().contains(apiKey))
        assertFalse(configuration.toString().contains(headerValue))
        assertFalse(configuration.headers.toString().contains(headerValue))
    }

    @Test
    fun editRejectsOmittingPersistedModelsOrHeaders() = runTest {
        val api = RecordingProviderApi(globalConfig = customGlobalConfig())
        val repository = repository(api.proxy)
        val result = repository.saveCustomProvider(
            SERVER_ID,
            CustomProviderDraft(
                originalProviderId = PROVIDER_ID,
                providerId = PROVIDER_ID,
                displayName = "Provider Alpha",
                baseUrl = "https://example.test/v1",
                apiKey = null,
                models = listOf(CustomProviderModelDraft("replacement-model", "Replacement Model")),
                headers = emptyList(),
            ),
        )

        assertTrue(result.isFailure)
        assertEquals(listOf("getGlobalConfig"), api.calls)
        assertTrue(api.updateBodies.isEmpty())
        assertTrue(api.authWrites.isEmpty())
    }

    private fun repository(api: OpenCodeApi) = OpenCodeProviderRepository(
        serverRepository = FakeServerRepository(api),
        pathNormalizer = PathNormalizer(),
        json = json,
    )

    private fun emptyGlobalConfig(): JsonElement = json.parseToJsonElement(
        """
        {
          "provider": {},
          "disabled_providers": ["existing-disabled"]
        }
        """.trimIndent(),
    )

    private fun customGlobalConfig(
        apiKey: String = "synthetic-api-key",
        headerValue: String = "synthetic-header-value",
    ): JsonElement = json.parseToJsonElement(
        """
        {
          "provider": {
            "$PROVIDER_ID": {
              "npm": "@ai-sdk/openai-compatible",
              "name": "Provider Alpha",
              "options": {
                "baseURL": "https://example.test/v1",
                "apiKey": "$apiKey",
                "headers": { "Authorization": "$headerValue" }
              },
              "models": {
                "model-one": { "name": "Model One" }
              }
            }
          },
          "disabled_providers": []
        }
        """.trimIndent(),
    )

    private fun draft(
        apiKey: String? = null,
        headers: List<CustomProviderHeaderDraft> = emptyList(),
    ) = CustomProviderDraft(
        originalProviderId = null,
        providerId = PROVIDER_ID,
        displayName = "Provider Alpha",
        baseUrl = "https://example.test/v1",
        apiKey = apiKey,
        models = listOf(CustomProviderModelDraft("model-one", "Model One")),
        headers = headers,
    )

    private class RecordingProviderApi(
        private val globalConfig: JsonElement,
        private val putAuthResult: Boolean = true,
        private val deleteAuthResult: Boolean = true,
    ) {
        val calls = mutableListOf<String>()
        val updateBodies = mutableListOf<JsonElement>()
        val authWrites = mutableListOf<AuthWrite>()

        val proxy: OpenCodeApi = Proxy.newProxyInstance(
            OpenCodeApi::class.java.classLoader,
            arrayOf(OpenCodeApi::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "toString" -> "RecordingProviderApi"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "getGlobalConfig" -> {
                        calls += method.name
                        globalConfig
                    }
                    "updateGlobalConfig" -> {
                        calls += method.name
                        val body = args!![0] as JsonElement
                        updateBodies += body
                        body
                    }
                    "putProviderAuth" -> {
                        calls += method.name
                        val body = args!![1] as ProviderApiAuthRequestDto
                        authWrites += AuthWrite(args[0] as String, body.key)
                        putAuthResult
                    }
                    "deleteProviderAuth" -> {
                        calls += method.name
                        deleteAuthResult
                    }
                    "disposeGlobalInstances" -> {
                        calls += method.name
                        true
                    }
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as OpenCodeApi
    }

    private class FakeServerRepository(api: OpenCodeApi) : OpenCodeServerRepository {
        private val connection = ServerConnection(
            server = ServerConfig(SERVER_ID, "Server", "https://example.test"),
            password = null,
            effectiveBaseUrl = "https://example.test",
            api = api,
        )

        override suspend fun getConnection(serverId: String): ServerConnection = connection

        override suspend fun getHiddenModelPreferences(serverId: String): List<ServerHiddenModelPreference> =
            emptyList()

        override suspend fun setModelHidden(
            serverId: String,
            providerId: String,
            modelId: String,
            hidden: Boolean,
        ) = Unit
    }

    private data class AuthWrite(val providerId: String, val key: String) {
        override fun toString(): String = "AuthWrite(providerId=<redacted>, key=<redacted>)"
    }

    private fun JsonElement.disabledProviders(): Set<String> =
        jsonObject.getValue("disabled_providers").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private companion object {
        const val SERVER_ID = "server"
        const val PROVIDER_ID = "provider-alpha"
    }
}
