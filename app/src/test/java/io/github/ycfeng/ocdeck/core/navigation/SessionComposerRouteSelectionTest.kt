package io.github.ycfeng.ocdeck.core.navigation

import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.domain.prompt.OpenCodePromptSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionComposerRouteSelectionTest {
    @Test
    fun newSessionRestoresCompleteComposerSelection() {
        val selection = resolveSessionComposerRouteSelection(
            sessionId = OpenCodePromptSender.NEW_SESSION_ID,
            initialAgentId = "plan",
            initialProviderId = "provider/alpha",
            initialModelId = "model?reasoning",
            initialVariant = "high",
        )

        assertEquals("plan", selection.agentId)
        assertEquals(
            PromptModelSelection("provider/alpha", "model?reasoning", "high"),
            selection.modelSelection,
        )
    }

    @Test
    fun incompleteModelSelectionIsIgnoredWithoutDroppingAgent() {
        val selection = resolveSessionComposerRouteSelection(
            sessionId = OpenCodePromptSender.NEW_SESSION_ID,
            initialAgentId = "build",
            initialProviderId = "provider-alpha",
            initialModelId = null,
            initialVariant = "high",
        )

        assertEquals("build", selection.agentId)
        assertNull(selection.modelSelection)
    }

    @Test
    fun blankVariantMeansDefault() {
        val selection = resolveSessionComposerRouteSelection(
            sessionId = OpenCodePromptSender.NEW_SESSION_ID,
            initialAgentId = null,
            initialProviderId = "provider-alpha",
            initialModelId = "model-standard",
            initialVariant = " ",
        )

        assertEquals(
            PromptModelSelection("provider-alpha", "model-standard", null),
            selection.modelSelection,
        )
    }

    @Test
    fun existingSessionIgnoresComposerRouteSelection() {
        val selection = resolveSessionComposerRouteSelection(
            sessionId = "ses-existing",
            initialAgentId = "plan",
            initialProviderId = "provider-alpha",
            initialModelId = "model-standard",
            initialVariant = "high",
        )

        assertNull(selection.agentId)
        assertNull(selection.modelSelection)
    }
}
