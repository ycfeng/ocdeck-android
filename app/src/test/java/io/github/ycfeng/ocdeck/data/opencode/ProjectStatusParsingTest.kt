package io.github.ycfeng.ocdeck.data.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectStatusParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun extractMcpsSupportsObjectMap() {
        val payload = json.parseToJsonElement(
            """
            {
              "local": { "status": "connected" },
              "remote": { "status": "failed", "error": "connection failed" }
            }
            """.trimIndent(),
        )

        val mcps = payload.extractMcps()

        assertEquals(listOf("local", "remote"), mcps.map { it.name })
        assertEquals(listOf("connected", "failed"), mcps.map { it.status })
        assertEquals("connection failed", mcps.single { it.name == "remote" }.error)
    }

    @Test
    fun extractMcpsSupportsArrayAndStringFallbacks() {
        val payload = json.parseToJsonElement(
            """
            [
              { "name": "remote", "status": "disabled" },
              "legacy"
            ]
            """.trimIndent(),
        )

        val mcps = payload.extractMcps()

        assertEquals(listOf("legacy", "remote"), mcps.map { it.name })
        assertEquals("disabled", mcps.single { it.name == "remote" }.status)
        assertNull(mcps.single { it.name == "legacy" }.status)
    }

    @Test
    fun extractLspsSupportsArrayObjectStringAndMap() {
        val arrayPayload = json.parseToJsonElement(
            """
            [
              { "id": "kotlin", "name": "Kotlin", "root": "/repo", "status": "connected" },
              "typescript"
            ]
            """.trimIndent(),
        )
        val mapPayload = json.parseToJsonElement(
            """
            {
              "lua": { "status": "error" }
            }
            """.trimIndent(),
        )

        val arrayLsps = arrayPayload.extractLsps()
        val mapLsps = mapPayload.extractLsps()

        assertEquals(listOf("kotlin", "typescript"), arrayLsps.map { it.id })
        assertEquals("Kotlin", arrayLsps.single { it.id == "kotlin" }.name)
        assertEquals("/repo", arrayLsps.single { it.id == "kotlin" }.root)
        assertEquals("lua", mapLsps.single().id)
        assertEquals("error", mapLsps.single().status)
    }

    @Test
    fun extractPluginsOnlyReadsPluginField() {
        val payload = json.parseToJsonElement(
            """
            {
              "plugin": [
                "@example/one",
                ["@example/two", { "enabled": true }],
                { "name": "@example/three" }
              ],
              "provider": {
                "custom": {
                  "options": { "apiKey": "not-a-real-secret" },
                  "headers": { "Authorization": "Bearer not-a-real-token" }
                }
              },
              "env": { "TOKEN": "not-a-real-env" }
            }
            """.trimIndent(),
        )

        val plugins = payload.extractPlugins()

        assertEquals(listOf("@example/one", "@example/two", "@example/three"), plugins.map { it.name })
    }
}
