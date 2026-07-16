package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.feature.composer.composerAgentOptions
import io.github.ycfeng.ocdeck.feature.composer.resolveComposerAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionComposerAgentResolverTest {
    @Test
    fun newSessionDefaultsToBuild() {
        assertEquals("build", resolveComposerAgent(null, null, emptyList(), composerAgents()))
    }

    @Test
    fun restoresPlanFromSession() {
        assertEquals("plan", resolveComposerAgent(null, "plan", emptyList(), composerAgents()))
    }

    @Test
    fun restoresPlanFromLatestUserMessageWhenSessionAgentIsMissing() {
        val messages = listOf(
            message(id = "msg_1", role = "user", agent = "build"),
            message(id = "msg_2", role = "assistant", agent = "build"),
            message(id = "msg_3", role = "user", agent = "plan"),
        )

        assertEquals("plan", resolveComposerAgent(null, null, messages, composerAgents()))
    }

    @Test
    fun localBuildSelectionOverridesStoredPlan() {
        val messages = listOf(message(id = "msg_1", role = "user", agent = "plan"))

        assertEquals("build", resolveComposerAgent("build", "plan", messages, composerAgents()))
    }

    @Test
    fun localPlanSelectionOverridesStoredBuild() {
        val messages = listOf(message(id = "msg_1", role = "user", agent = "build"))

        assertEquals("plan", resolveComposerAgent("plan", "build", messages, composerAgents()))
    }

    @Test
    fun unsupportedAgentsAreIgnored() {
        val messages = listOf(message(id = "msg_1", role = "user", agent = "general"))

        assertEquals("build", resolveComposerAgent("explore", "general", messages, composerAgents()))
    }

    @Test
    fun supportedAgentsAreTrimmedAndNormalized() {
        assertEquals("plan", resolveComposerAgent(" PLAN ", null, emptyList(), composerAgents()))
    }

    @Test
    fun materializedSessionKeepsPlanSelection() {
        val optimisticMessage = message(id = "msg_1", role = "user", agent = "plan")

        val beforeMaterialization = resolveComposerAgent("plan", null, listOf(optimisticMessage), composerAgents())
        val afterMaterialization = resolveComposerAgent(
            localOverride = null,
            sessionAgent = "plan",
            messages = listOf(optimisticMessage.copy(sessionId = "ses_1")),
            agents = composerAgents(),
        )

        assertEquals("plan", beforeMaterialization)
        assertEquals("plan", afterMaterialization)
    }

    @Test
    fun planIsSelectedWhenBuildIsUnavailable() {
        assertEquals(
            "plan",
            resolveComposerAgent(null, null, emptyList(), listOf(agent("plan"))),
        )
    }

    @Test
    fun unavailableSelectionFallsBackToAvailableAgent() {
        assertEquals(
            "build",
            resolveComposerAgent("plan", "plan", emptyList(), listOf(agent("build"))),
        )
    }

    @Test
    fun noComposerAgentReturnsNull() {
        assertNull(resolveComposerAgent(null, null, emptyList(), listOf(agent("general"))))
    }

    @Test
    fun agentOptionsDoNotCreateSyntheticAgents() {
        assertEquals(emptyList<OpenCodeAgent>(), composerAgentOptions(emptyList()))
        assertEquals(emptyList<OpenCodeAgent>(), composerAgentOptions(listOf(agent("general"))))
    }

    @Test
    fun agentOptionsOnlyExposeBuildAndPlanInServerOrder() {
        assertEquals(
            listOf("plan", "build"),
            composerAgentOptions(listOf(agent("general"), agent("plan"), agent("build"))).map { it.id },
        )
    }

    @Test
    fun slashSuggestionsOnlyRemainActiveWhileEditingTheCommandToken() {
        assertEquals("", slashCommandQueryOrNull("/"))
        assertEquals("review", slashCommandQueryOrNull("  /review"))
        assertNull(slashCommandQueryOrNull("/review "))
        assertNull(slashCommandQueryOrNull("/review target"))
        assertNull(slashCommandQueryOrNull("/review\nargument"))
        assertNull(slashCommandQueryOrNull("plain prompt"))
    }

    private fun message(id: String, role: String, agent: String?) = OpenCodeMessage(
        id = id,
        sessionId = "new",
        role = role,
        text = "",
        agent = agent,
    )

    private fun composerAgents() = listOf(agent("build"), agent("plan"))

    private fun agent(id: String) = OpenCodeAgent(
        id = id,
        name = id,
        description = null,
    )
}
