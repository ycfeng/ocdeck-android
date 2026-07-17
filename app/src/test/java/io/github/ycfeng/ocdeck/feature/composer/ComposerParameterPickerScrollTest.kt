package io.github.ycfeng.ocdeck.feature.composer

import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerParameterPickerScrollTest {
    @Test
    fun modelIndexIncludesProviderHeaders() {
        val models = listOf(
            model("provider-alpha", "model-one", "Provider Alpha"),
            model("provider-alpha", "model-two", "Provider Alpha"),
            model("provider-beta", "model-three", "Provider Beta"),
            model("provider-beta", "model-four", "Provider Beta"),
        )

        assertEquals(
            5,
            modelPickerSelectedItemIndex(
                models = models,
                selectedModel = PromptModelSelection("provider-beta", "model-four"),
            ),
        )
    }

    @Test
    fun missingModelHasNoScrollTarget() {
        val models = listOf(model("provider-alpha", "model-one", "Provider Alpha"))

        assertNull(
            modelPickerSelectedItemIndex(
                models = models,
                selectedModel = PromptModelSelection("provider-alpha", "model-missing"),
            ),
        )
        assertNull(modelPickerSelectedItemIndex(models, null))
    }

    @Test
    fun variantIndexAccountsForDefaultOption() {
        val variants = listOf("none", "low", "high")

        assertEquals(0, variantPickerSelectedItemIndex(variants, null))
        assertEquals(3, variantPickerSelectedItemIndex(variants, "high"))
        assertNull(variantPickerSelectedItemIndex(variants, "max"))
    }

    @Test
    fun centerDeltaUsesMeasuredItemAndViewportBounds() {
        assertEquals(
            20f,
            centeredLazyListScrollDelta(
                itemOffset = 100,
                itemSize = 40,
                viewportStartOffset = 0,
                viewportEndOffset = 200,
            ),
            0f,
        )
        assertEquals(
            -50f,
            centeredLazyListScrollDelta(
                itemOffset = 30,
                itemSize = 40,
                viewportStartOffset = 0,
                viewportEndOffset = 200,
            ),
            0f,
        )
        assertEquals(
            0f,
            centeredLazyListScrollDelta(
                itemOffset = 48,
                itemSize = 48,
                viewportStartOffset = -8,
                viewportEndOffset = 152,
            ),
            0f,
        )
    }

    private fun model(providerId: String, modelId: String, providerName: String) = OpenCodeModel(
        providerId = providerId,
        modelId = modelId,
        name = modelId,
        providerName = providerName,
    )
}
