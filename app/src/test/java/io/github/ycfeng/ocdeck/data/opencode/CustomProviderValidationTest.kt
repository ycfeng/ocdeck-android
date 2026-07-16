package io.github.ycfeng.ocdeck.data.opencode

import io.github.ycfeng.ocdeck.domain.model.CustomProviderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderHeaderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderModelDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderValidationTest {
    @Test
    fun validDraftIsNormalizedAndRetainedHeaderMayStayBlank() {
        val result = validateCustomProviderDraft(
            draft(
                providerId = " provider_alpha ",
                displayName = " Provider Alpha ",
                baseUrl = "https://example.test/v1/",
                models = listOf(CustomProviderModelDraft(" model-one ", "")),
                headers = listOf(
                    CustomProviderHeaderDraft(
                        name = "X-Synthetic",
                        value = null,
                        retainExisting = true,
                    ),
                ),
            ),
        ) as CustomProviderValidationResult.Valid

        assertEquals("provider_alpha", result.draft.providerId)
        assertEquals("Provider Alpha", result.draft.displayName)
        assertEquals("https://example.test/v1", result.draft.baseUrl)
        assertEquals(CustomProviderModelDraft("model-one", "model-one"), result.draft.models.single())
        assertTrue(result.draft.headers.single().retainExisting)
    }

    @Test
    fun editCannotChangeProviderIdAndRequiresModel() {
        val renamed = validateCustomProviderDraft(
            draft(originalProviderId = "provider-alpha", providerId = "provider-beta"),
        ) as CustomProviderValidationResult.Invalid
        val noModels = validateCustomProviderDraft(
            draft(models = emptyList()),
        ) as CustomProviderValidationResult.Invalid

        assertEquals(CustomProviderValidationFailure.ProviderId, renamed.failure)
        assertEquals(CustomProviderValidationFailure.ModelsRequired, noModels.failure)
    }

    @Test
    fun headersRejectCaseInsensitiveDuplicatesAndMissingNewValue() {
        val duplicate = validateCustomProviderDraft(
            draft(
                headers = listOf(
                    CustomProviderHeaderDraft("X-Synthetic", "first"),
                    CustomProviderHeaderDraft("x-synthetic", "second"),
                ),
            ),
        ) as CustomProviderValidationResult.Invalid
        val blankNewHeader = validateCustomProviderDraft(
            draft(headers = listOf(CustomProviderHeaderDraft("X-Synthetic", null))),
        ) as CustomProviderValidationResult.Invalid

        assertEquals(CustomProviderValidationFailure.DuplicateHeader, duplicate.failure)
        assertEquals(CustomProviderValidationFailure.Header, blankNewHeader.failure)
    }

    @Test
    fun modelAndHeaderLimitsAcceptMaximumAndRejectOverflow() {
        val maximumModels = List(CUSTOM_PROVIDER_MAX_MODELS) { index ->
            CustomProviderModelDraft("model-$index", "Model $index")
        }
        val maximumHeaders = List(CUSTOM_PROVIDER_MAX_HEADERS) { index ->
            CustomProviderHeaderDraft("X-Synthetic-$index", "value-$index")
        }

        assertTrue(validateCustomProviderDraft(draft(models = maximumModels)) is CustomProviderValidationResult.Valid)
        assertEquals(
            CustomProviderValidationFailure.ModelsLimit,
            (validateCustomProviderDraft(
                draft(models = maximumModels + CustomProviderModelDraft("model-overflow", "Model Overflow")),
            ) as CustomProviderValidationResult.Invalid).failure,
        )
        assertTrue(validateCustomProviderDraft(draft(headers = maximumHeaders)) is CustomProviderValidationResult.Valid)
        assertEquals(
            CustomProviderValidationFailure.HeadersLimit,
            (validateCustomProviderDraft(
                draft(headers = maximumHeaders + CustomProviderHeaderDraft("X-Synthetic-Overflow", "value")),
            ) as CustomProviderValidationResult.Invalid).failure,
        )
    }

    @Test
    fun sensitiveDraftSummariesDoNotExposeValues() {
        val secret = "synthetic-provider-secret"
        val headerSecret = "synthetic-header-secret"
        val value = draft(
            apiKey = secret,
            headers = listOf(CustomProviderHeaderDraft("Authorization", headerSecret)),
        )

        assertFalse(value.toString().contains(secret))
        assertFalse(value.toString().contains(headerSecret))
        assertFalse(value.headers.toString().contains(headerSecret))
    }

    private fun draft(
        originalProviderId: String? = null,
        providerId: String = "provider-alpha",
        displayName: String = "Provider Alpha",
        baseUrl: String = "https://example.test/v1",
        apiKey: String? = null,
        models: List<CustomProviderModelDraft> = listOf(CustomProviderModelDraft("model-one", "Model One")),
        headers: List<CustomProviderHeaderDraft> = emptyList(),
    ) = CustomProviderDraft(
        originalProviderId = originalProviderId,
        providerId = providerId,
        displayName = displayName,
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = models,
        headers = headers,
    )
}
