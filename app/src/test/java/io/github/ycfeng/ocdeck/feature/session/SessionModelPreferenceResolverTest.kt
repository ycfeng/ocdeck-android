package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.data.server.ServerComposerModelPreference
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.feature.composer.selectPromptModel
import io.github.ycfeng.ocdeck.feature.composer.selectPromptVariant
import io.github.ycfeng.ocdeck.feature.composer.toValidPromptModelSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionModelPreferenceResolverTest {
    @Test
    fun restoresCachedModelAndSupportedVariant() {
        val selection = ServerComposerModelPreference(
            serverId = "server-fixture",
            providerId = "provider-alpha",
            modelId = "model-standard",
            variant = "high",
        ).toValidPromptModelSelection(models)

        assertEquals("provider-alpha", selection?.providerId)
        assertEquals("model-standard", selection?.modelId)
        assertEquals("high", selection?.variant)
    }

    @Test
    fun unsupportedVariantFallsBackToDefault() {
        val selection = ServerComposerModelPreference(
            serverId = "server-fixture",
            providerId = "provider-alpha",
            modelId = "model-standard",
            variant = "xhigh",
        ).toValidPromptModelSelection(models)

        assertEquals("provider-alpha", selection?.providerId)
        assertEquals("model-standard", selection?.modelId)
        assertNull(selection?.variant)
    }

    @Test
    fun missingModelIsNotRestored() {
        val selection = ServerComposerModelPreference(
            serverId = "server-fixture",
            providerId = "provider-beta",
            modelId = "model-missing",
            variant = "high",
        ).toValidPromptModelSelection(models)

        assertNull(selection)
    }

    @Test
    fun hiddenModelIsNotRestored() {
        val selection = ServerComposerModelPreference(
            serverId = "server-fixture",
            providerId = "provider-alpha",
            modelId = "model-hidden",
            variant = "high",
        ).toValidPromptModelSelection(models)

        assertNull(selection)
    }

    @Test
    fun disconnectedModelIsNotRestored() {
        val selection = ServerComposerModelPreference(
            serverId = "server-fixture",
            providerId = "provider-beta",
            modelId = "model-offline",
        ).toValidPromptModelSelection(models)

        assertNull(selection)
    }

    @Test
    fun blankPreferenceIsNotRestored() {
        val selection = ServerComposerModelPreference(
            serverId = "server-fixture",
            providerId = "",
            modelId = "model-standard",
        ).toValidPromptModelSelection(models)

        assertNull(selection)
    }

    @Test
    fun currentSessionModelWinsOverPreferenceAndServerDefault() {
        val selection = resolveInitialPromptModelSelection(
            session = session(providerId = "provider-beta", modelId = "model-fast"),
            messages = emptyList(),
            preference = preference("provider-alpha", "model-standard"),
            capabilities = capabilities(defaults = listOf(PromptModelSelection("provider-alpha", "model-standard"))),
        )

        assertEquals(PromptModelSelection("provider-beta", "model-fast"), selection)
    }

    @Test
    fun currentMessageModelRestoresWhenSessionModelIsMissing() {
        val selection = resolveInitialPromptModelSelection(
            session = session(providerId = null, modelId = null),
            messages = listOf(
                OpenCodeMessage(
                    id = "msg_fixture_001",
                    sessionId = "ses_fixture_001",
                    role = "user",
                    text = "hello",
                    modelProviderId = "provider-beta",
                    modelId = "model-fast",
                ),
            ),
            preference = preference("provider-alpha", "model-standard"),
            capabilities = capabilities(defaults = listOf(PromptModelSelection("provider-alpha", "model-standard"))),
        )

        assertEquals(PromptModelSelection("provider-beta", "model-fast"), selection)
    }

    @Test
    fun preferenceWinsOverUniqueServerDefault() {
        val selection = resolveInitialPromptModelSelection(
            session = null,
            messages = emptyList(),
            preference = preference("provider-beta", "model-fast"),
            capabilities = capabilities(defaults = listOf(PromptModelSelection("provider-alpha", "model-standard"))),
        )

        assertEquals(PromptModelSelection("provider-beta", "model-fast"), selection)
    }

    @Test
    fun validInitialSelectionWinsOverSessionAndPreference() {
        val selection = resolveInitialPromptModelSelection(
            initialSelection = PromptModelSelection("provider-alpha", "model-standard", "high"),
            session = session(providerId = "provider-beta", modelId = "model-fast"),
            messages = emptyList(),
            preference = preference("provider-beta", "model-fast"),
            capabilities = capabilities(),
        )

        assertEquals(PromptModelSelection("provider-alpha", "model-standard", "high"), selection)
    }

    @Test
    fun invalidInitialSelectionFallsBackToPreference() {
        val selection = resolveInitialPromptModelSelection(
            initialSelection = PromptModelSelection("provider-alpha", "model-missing", "high"),
            session = null,
            messages = emptyList(),
            preference = preference("provider-beta", "model-fast"),
            capabilities = capabilities(),
        )

        assertEquals(PromptModelSelection("provider-beta", "model-fast"), selection)
    }

    @Test
    fun switchingModelsKeepsOnlySupportedVariant() {
        val current = PromptModelSelection("provider-alpha", "model-standard", "high")

        assertEquals("high", current.selectPromptModel(models[0]).variant)
        assertNull(current.selectPromptModel(models[1]).variant)
    }

    @Test
    fun selectingVariantAcceptsSupportedValueAndUsesDefaultForUnsupportedValue() {
        val current = PromptModelSelection("provider-alpha", "model-standard")

        assertEquals("medium", current.selectPromptVariant("medium", models).variant)
        assertNull(current.selectPromptVariant("unsupported", models).variant)
        assertNull(current.selectPromptVariant(null, models).variant)
    }

    @Test
    fun uniqueLegalServerDefaultIsUsedAsLastFallback() {
        val selection = resolveInitialPromptModelSelection(
            session = null,
            messages = emptyList(),
            preference = null,
            capabilities = capabilities(defaults = listOf(PromptModelSelection("provider-alpha", "model-standard"))),
        )

        assertEquals(PromptModelSelection("provider-alpha", "model-standard"), selection)
    }

    @Test
    fun multipleLegalServerDefaultsRequireExplicitSelection() {
        val selection = resolveInitialPromptModelSelection(
            session = null,
            messages = emptyList(),
            preference = null,
            capabilities = capabilities(
                defaults = listOf(
                    PromptModelSelection("provider-alpha", "model-standard"),
                    PromptModelSelection("provider-beta", "model-fast"),
                ),
            ),
        )

        assertNull(selection)
    }

    @Test
    fun missingSessionPreferenceAndDefaultRequireExplicitSelection() {
        val selection = resolveInitialPromptModelSelection(
            session = null,
            messages = emptyList(),
            preference = null,
            capabilities = capabilities(),
        )

        assertNull(selection)
    }

    private fun preference(providerId: String, modelId: String) = ServerComposerModelPreference(
        serverId = "server-fixture",
        providerId = providerId,
        modelId = modelId,
    )

    private fun capabilities(defaults: List<PromptModelSelection> = emptyList()) = PromptCapabilities(
        models = models,
        serverDefaultModels = defaults,
        isLoaded = true,
        revision = 1,
    )

    private fun session(providerId: String?, modelId: String?) = OpenCodeSession(
        id = "ses_fixture_001",
        title = "Session",
        normalizedDirectory = "/workspace/sample-project",
        path = null,
        parentId = null,
        agent = "build",
        modelLabel = null,
        updatedAt = null,
        archivedAt = null,
        modelProviderId = providerId,
        modelId = modelId,
    )

    private companion object {
        val models = listOf(
            OpenCodeModel(
                providerId = "provider-alpha",
                modelId = "model-standard",
                name = "Model Standard",
                providerName = "Provider Alpha",
                isConnected = true,
                variants = listOf("medium", "high"),
            ),
            OpenCodeModel(
                providerId = "provider-beta",
                modelId = "model-fast",
                name = "Model Fast",
                providerName = "Provider Beta",
                isConnected = true,
            ),
            OpenCodeModel(
                providerId = "provider-alpha",
                modelId = "model-hidden",
                name = "Model Hidden",
                providerName = "Provider Alpha",
                isConnected = true,
                variants = listOf("high"),
                isEnabled = false,
            ),
            OpenCodeModel(
                providerId = "provider-beta",
                modelId = "model-offline",
                name = "Model Offline",
                providerName = "Provider Beta",
                isConnected = false,
            ),
        )
    }
}
