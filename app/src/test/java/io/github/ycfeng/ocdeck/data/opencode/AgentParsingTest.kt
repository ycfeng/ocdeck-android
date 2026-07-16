package io.github.ycfeng.ocdeck.data.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun nameOnlyArrayUsesNameAsAgentId() {
        val payload = json.parseToJsonElement(
            """
            [
              { "name": "build", "description": "Build agent" },
              { "name": "plan", "description": "Plan agent" }
            ]
            """.trimIndent(),
        )

        val agents = payload.extractAgents()

        assertEquals(listOf("build", "plan"), agents.map { it.id })
        assertEquals(listOf("build", "plan"), agents.map { it.name })
    }

    @Test
    fun explicitIdTakesPriorityOverName() {
        val payload = json.parseToJsonElement(
            """
            [
              { "id": "legacy-build", "name": "build" }
            ]
            """.trimIndent(),
        )

        val agent = payload.extractAgents().single()

        assertEquals("legacy-build", agent.id)
        assertEquals("build", agent.name)
    }

    @Test
    fun objectKeyIsUsedWhenIdIsMissing() {
        val payload = json.parseToJsonElement(
            """
            {
              "build": { "name": "Builder" },
              "plan": { "name": "Planner" }
            }
            """.trimIndent(),
        )

        val agents = payload.extractAgents()

        assertEquals(listOf("build", "plan"), agents.map { it.id })
        assertEquals(listOf("Builder", "Planner"), agents.map { it.name })
    }

    @Test
    fun blankAndUnidentifiedArrayEntriesAreIgnored() {
        val payload = json.parseToJsonElement(
            """
            [
              { "id": "  ", "name": " plan " },
              { "id": "", "name": "" },
              { "description": "missing identity" },
              "not-an-object"
            ]
            """.trimIndent(),
        )

        val agents = payload.extractAgents()

        assertEquals(listOf("plan"), agents.map { it.id })
        assertEquals(listOf("plan"), agents.map { it.name })
    }
}
