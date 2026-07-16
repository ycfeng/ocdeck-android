package io.github.ycfeng.ocdeck.data.opencode

import io.github.ycfeng.ocdeck.domain.model.OpenCodeProviderSource
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthMethodType
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthSelectPrompt
import io.github.ycfeng.ocdeck.domain.model.ProviderOAuthMode
import io.github.ycfeng.ocdeck.core.network.OpenCodeRequestException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSettingsParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun providerPayloadIsImmediatelyProjectedWithoutSensitiveFields() {
        val secret = "synthetic-provider-secret"
        val headerSecret = "synthetic-header-secret"
        val payload = json.parseToJsonElement(
            """
            {
              "all": [
                {
                  "id": "provider-alpha",
                  "name": "Provider Alpha",
                  "source": "api",
                  "key": "$secret",
                  "options": {
                    "headers": { "Authorization": "Bearer $headerSecret" }
                  },
                  "models": {
                    "model-one": {
                      "name": "Model One",
                      "headers": { "X-Secret": "$headerSecret" }
                    },
                    "model-two": { "name": "Model Two" }
                  }
                },
                {
                  "id": "provider-beta",
                  "name": "Provider Beta",
                  "source": "future-source",
                  "models": []
                }
              ],
              "connected": ["provider-alpha"]
            }
            """.trimIndent(),
        )

        val providers = payload.toSafeProviderSummaries()

        assertEquals(2, providers.size)
        assertEquals(OpenCodeProviderSource.Api, providers[0].source)
        assertTrue(providers[0].isConnected)
        assertEquals(2, providers[0].modelCount)
        assertEquals(OpenCodeProviderSource.Unknown, providers[1].source)
        assertFalse(providers[1].isConnected)
        assertFalse(providers.toString().contains(secret))
        assertFalse(providers.toString().contains(headerSecret))
    }

    @Test
    fun sourceDoesNotImplyConnectedWithoutConnectedField() {
        val payload = json.parseToJsonElement(
            """
            {
              "all": {
                "provider-alpha": {
                  "name": "Provider Alpha",
                  "source": "api",
                  "models": {}
                }
              }
            }
            """.trimIndent(),
        )

        val provider = payload.toSafeProviderSummaries().single()

        assertEquals("provider-alpha", provider.id)
        assertFalse(provider.isConnected)
    }

    @Test
    fun configProvidersFallbackTreatsReturnedEntriesAsLoaded() {
        val payload = json.parseToJsonElement(
            """
            {
              "providers": {
                "provider-alpha": {
                  "id": "provider-alpha",
                  "name": "Provider Alpha",
                  "models": { "model-one": {} }
                }
              }
            }
            """.trimIndent(),
        )

        val provider = payload.toSafeProviderSummaries().single()

        assertTrue(provider.isConnected)
        assertEquals(1, provider.modelCount)
    }

    @Test
    fun authMethodsPreserveWireIndexAndApplyConditions() {
        val payload = json.parseToJsonElement(
            """
            {
              "provider-alpha": [
                { "type": "future", "label": "Unsupported" },
                {
                  "type": "oauth",
                  "label": "Device login",
                  "prompts": [
                    {
                      "type": "select",
                      "key": "deployment",
                      "message": "Deployment",
                      "options": [
                        { "label": "Public", "value": "public" },
                        { "label": "Enterprise", "value": "enterprise", "hint": "Managed" }
                      ]
                    },
                    {
                      "type": "text",
                      "key": "enterpriseUrl",
                      "message": "Enterprise URL",
                      "when": { "key": "deployment", "op": "eq", "value": "enterprise" }
                    }
                  ]
                },
                { "type": "api", "label": "Token" }
              ]
            }
            """.trimIndent(),
        )

        val methods = payload.toSafeProviderAuthMethods("provider-alpha")

        assertEquals(listOf(1, 2), methods.map { it.wireIndex })
        assertEquals(ProviderAuthMethodType.OAuth, methods[0].type)
        assertEquals(ProviderAuthMethodType.Api, methods[1].type)
        assertEquals(1, methods[0].visiblePrompts(mapOf("deployment" to "public")).size)
        assertEquals(2, methods[0].visiblePrompts(mapOf("deployment" to "enterprise")).size)
        val select = methods[0].prompts.first() as ProviderAuthSelectPrompt
        assertEquals("Managed", select.options[1].hint)
    }

    @Test
    fun missingAuthEntryFallsBackToGenericApiMethod() {
        val methods = json.parseToJsonElement("{}").toSafeProviderAuthMethods("provider-alpha")

        assertEquals(1, methods.size)
        assertEquals(0, methods.single().wireIndex)
        assertEquals(ProviderAuthMethodType.Api, methods.single().type)
    }

    @Test
    fun oauthAuthorizationAcceptsSafeUrlAndRejectsUserInfo() {
        val authorization = json.parseToJsonElement(
            """{"url":"https://example.test/authorize?state=synthetic#step","method":"code","instructions":"Paste code"}""",
        ).toSafeOAuthAuthorization()

        assertEquals(ProviderOAuthMode.Code, authorization.mode)
        assertFalse(authorization.usesLoopbackUrl)
        assertFalse(authorization.toString().contains("synthetic"))

        assertThrows(OpenCodeRequestException::class.java) {
            json.parseToJsonElement(
                """{"url":"https://user:pass@example.test/authorize","method":"auto","instructions":""}""",
            ).toSafeOAuthAuthorization()
        }
    }

    @Test
    fun oauthAuthorizationMarksLoopbackWithoutDnsResolution() {
        val authorization = json.parseToJsonElement(
            """{"url":"http://127.0.0.2:1455/auth/callback","method":"auto","instructions":"Continue"}""",
        ).toSafeOAuthAuthorization()

        assertEquals(ProviderOAuthMode.Auto, authorization.mode)
        assertTrue(authorization.usesLoopbackUrl)
    }
}
