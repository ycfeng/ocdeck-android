package io.github.ycfeng.ocdeck.feature.settings

import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.network.OpenCodeEventClient
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailure
import io.github.ycfeng.ocdeck.core.network.eventClientEnvironment
import io.github.ycfeng.ocdeck.data.opencode.CUSTOM_PROVIDER_MAX_HEADERS
import io.github.ycfeng.ocdeck.data.opencode.CUSTOM_PROVIDER_MAX_MODELS
import io.github.ycfeng.ocdeck.data.opencode.ProviderSettingsGateway
import io.github.ycfeng.ocdeck.domain.model.CustomProviderCommitState
import io.github.ycfeng.ocdeck.domain.model.CustomProviderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderMutationOutcome
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCustomProviderConfiguration
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCustomProviderHeader
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCustomProviderModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProviderSummary
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthMethod
import io.github.ycfeng.ocdeck.domain.model.ProviderMutationOutcome
import io.github.ycfeng.ocdeck.domain.model.ProviderOAuthAuthorization
import io.github.ycfeng.ocdeck.ui.text.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CustomProviderFormViewModelTest {
    @Test
    fun editLoadKeepsPersistedIdentifiersReadOnlyAndSecretsAbsent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeCustomProviderGateway(
                configuration = OpenCodeCustomProviderConfiguration(
                    id = "provider-alpha",
                    name = "Provider Alpha",
                    baseUrl = "https://example.test/v1",
                    models = listOf(OpenCodeCustomProviderModel("model-one", "Model One")),
                    headers = listOf(OpenCodeCustomProviderHeader("Authorization")),
                    isDisabled = false,
                ),
            )
            val viewModel = CustomProviderFormViewModel(
                serverId = "server",
                providerId = "provider-alpha",
                gateway = gateway,
                eventClient = eventClientEnvironment(backgroundScope).client,
            )
            runCurrent()

            val loaded = viewModel.uiState.value
            val model = loaded.models.single()
            val header = loaded.headers.single()
            assertTrue(loaded.isFormReady)
            assertTrue(model.isPersisted)
            assertTrue(header.isPersisted)
            assertEquals("", header.value)

            viewModel.updateModelId(model.rowId, "renamed-model")
            viewModel.removeModel(model.rowId)
            viewModel.updateHeaderName(header.rowId, "X-Renamed")
            viewModel.removeHeader(header.rowId)

            val unchanged = viewModel.uiState.value
            assertEquals(listOf("model-one"), unchanged.models.map { it.modelId })
            assertEquals(listOf("Authorization"), unchanged.headers.map { it.name })
            assertFalse(unchanged.toString().contains("provider-alpha"))
            assertFalse(unchanged.toString().contains("https://example.test/v1"))
            assertFalse(unchanged.models.toString().contains("model-one"))
            assertFalse(unchanged.headers.toString().contains("Authorization"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun addActionsStopAtLimitsAndReenableAfterRemovingNewRows() = runTest {
        val viewModel = createViewModel(
            FakeCustomProviderGateway(),
            eventClientEnvironment(backgroundScope).client,
        )

        repeat(CUSTOM_PROVIDER_MAX_MODELS - viewModel.uiState.value.modelCount) {
            viewModel.addModel()
        }
        repeat(CUSTOM_PROVIDER_MAX_HEADERS) {
            viewModel.addHeader()
        }

        val maximum = viewModel.uiState.value
        assertEquals(CUSTOM_PROVIDER_MAX_MODELS, maximum.modelCount)
        assertEquals(CUSTOM_PROVIDER_MAX_HEADERS, maximum.headerCount)
        assertFalse(maximum.canAddModel)
        assertFalse(maximum.canAddHeader)

        viewModel.addModel()
        viewModel.addHeader()
        assertEquals(CUSTOM_PROVIDER_MAX_MODELS, viewModel.uiState.value.modelCount)
        assertEquals(CUSTOM_PROVIDER_MAX_HEADERS, viewModel.uiState.value.headerCount)

        viewModel.removeModel(viewModel.uiState.value.models.last().rowId)
        viewModel.removeHeader(viewModel.uiState.value.headers.last().rowId)
        assertEquals(CUSTOM_PROVIDER_MAX_MODELS - 1, viewModel.uiState.value.modelCount)
        assertEquals(CUSTOM_PROVIDER_MAX_HEADERS - 1, viewModel.uiState.value.headerCount)
        assertTrue(viewModel.uiState.value.canAddModel)
        assertTrue(viewModel.uiState.value.canAddHeader)
    }

    @Test
    fun loadedMaximumDisablesAddsAndNoOpsPreserveNotice() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeCustomProviderGateway(
                configuration = configuration(
                    modelCount = CUSTOM_PROVIDER_MAX_MODELS,
                    headerCount = CUSTOM_PROVIDER_MAX_HEADERS,
                ),
            )
            val viewModel = CustomProviderFormViewModel(
                serverId = "server",
                providerId = "provider-alpha",
                gateway = gateway,
                eventClient = eventClientEnvironment(backgroundScope).client,
            )
            runCurrent()

            assertFalse(viewModel.uiState.value.canAddModel)
            assertFalse(viewModel.uiState.value.canAddHeader)
            viewModel.save()
            runCurrent()
            val notice = viewModel.uiState.value.notice

            viewModel.addModel()
            viewModel.addHeader()

            assertEquals(CUSTOM_PROVIDER_MAX_MODELS, viewModel.uiState.value.modelCount)
            assertEquals(CUSTOM_PROVIDER_MAX_HEADERS, viewModel.uiState.value.headerCount)
            assertEquals(UiText.Resource(R.string.settings_custom_provider_saved), notice)
            assertEquals(notice, viewModel.uiState.value.notice)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun loadedOverflowDisablesAddsAndNoOpsPreserveValidationError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeCustomProviderGateway(
                configuration = configuration(
                    modelCount = CUSTOM_PROVIDER_MAX_MODELS + 1,
                    headerCount = CUSTOM_PROVIDER_MAX_HEADERS + 1,
                ),
            )
            val viewModel = CustomProviderFormViewModel(
                serverId = "server",
                providerId = "provider-alpha",
                gateway = gateway,
                eventClient = eventClientEnvironment(backgroundScope).client,
            )
            runCurrent()

            assertFalse(viewModel.uiState.value.canAddModel)
            assertFalse(viewModel.uiState.value.canAddHeader)
            viewModel.save()
            val error = viewModel.uiState.value.error

            viewModel.addModel()
            viewModel.addHeader()

            assertEquals(CUSTOM_PROVIDER_MAX_MODELS + 1, viewModel.uiState.value.modelCount)
            assertEquals(CUSTOM_PROVIDER_MAX_HEADERS + 1, viewModel.uiState.value.headerCount)
            assertEquals(UiText.Resource(R.string.settings_custom_provider_error_models_limit), error)
            assertEquals(error, viewModel.uiState.value.error)
            assertTrue(gateway.saveCalls.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cleartextConfirmationFreezesFirstDraftAndConsumesItOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeCustomProviderGateway(cleartextConfirmationRequired = true)
            val viewModel = createViewModel(gateway, eventClientEnvironment(backgroundScope).client)
            val firstApiKey = "synthetic-first-api-key"
            val firstHeaderValue = "synthetic-first-header-value"
            populateCreateForm(viewModel, firstApiKey, firstHeaderValue)

            viewModel.save()
            runCurrent()

            assertTrue(viewModel.uiState.value.cleartextConfirmationPending)
            assertTrue(gateway.saveCalls.isEmpty())
            assertFalse(viewModel.uiState.value.toString().contains(firstApiKey))
            assertFalse(viewModel.uiState.value.headers.toString().contains(firstHeaderValue))

            viewModel.updateApiKey("synthetic-later-api-key")
            viewModel.updateHeaderValue(
                viewModel.uiState.value.headers.single().rowId,
                "synthetic-later-header-value",
            )
            viewModel.confirmCleartextSave()
            viewModel.confirmCleartextSave()
            runCurrent()

            assertEquals(1, gateway.saveCalls.size)
            assertEquals(firstApiKey, gateway.saveCalls.single().apiKey)
            assertEquals(firstHeaderValue, gateway.saveCalls.single().headers.single().value)
            assertEquals("", viewModel.uiState.value.apiKey)
            assertTrue(viewModel.uiState.value.headers.all { it.value.isEmpty() && it.isPersisted })
            assertEquals(
                UiText.Resource(R.string.settings_custom_provider_saved),
                viewModel.uiState.value.notice,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun dismissingCleartextConfirmationPerformsNoWrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeCustomProviderGateway(cleartextConfirmationRequired = true)
            val viewModel = createViewModel(gateway, eventClientEnvironment(backgroundScope).client)
            populateCreateForm(viewModel, "synthetic-api-key", "synthetic-header-value")

            viewModel.save()
            runCurrent()
            viewModel.dismissCleartextConfirmation()
            viewModel.confirmCleartextSave()
            runCurrent()

            assertTrue(gateway.saveCalls.isEmpty())
            assertFalse(viewModel.uiState.value.cleartextConfirmationPending)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun disabledOutcomeClearsSecretsAndKeepsCommittedConfigurationEditable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeCustomProviderGateway(
                saveOutcome = CustomProviderMutationOutcome(
                    commitState = CustomProviderCommitState.Disabled,
                    operationFailure = OpenCodeFailure.OperationRejected(),
                ),
            )
            val viewModel = createViewModel(gateway, eventClientEnvironment(backgroundScope).client)
            populateCreateForm(viewModel, "synthetic-api-key", "synthetic-header-value")

            viewModel.save()
            runCurrent()

            val state = viewModel.uiState.value
            assertTrue(state.isEditing)
            assertTrue(state.isDisabled)
            assertEquals("", state.apiKey)
            assertTrue(state.headers.all { it.value.isEmpty() && it.isPersisted })
            assertEquals(
                UiText.Resource(R.string.settings_custom_provider_saved_disabled),
                state.notice,
            )
            assertTrue(state.error != null)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unknownOutcomeClearsSecretsAndWarnsAgainstImmediateRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeCustomProviderGateway(
                saveOutcome = CustomProviderMutationOutcome(
                    commitState = CustomProviderCommitState.Unknown,
                    operationFailure = OpenCodeFailure.NetworkUnavailable,
                ),
            )
            val viewModel = createViewModel(gateway, eventClientEnvironment(backgroundScope).client)
            populateCreateForm(viewModel, "synthetic-api-key", "synthetic-header-value")

            viewModel.save()
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals("", state.apiKey)
            assertTrue(state.headers.all { it.value.isEmpty() })
            assertEquals(
                UiText.Resource(R.string.settings_custom_provider_save_unknown),
                state.notice,
            )
            assertTrue(state.error != null)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        gateway: FakeCustomProviderGateway,
        eventClient: OpenCodeEventClient,
    ) = CustomProviderFormViewModel(
        serverId = "server",
        providerId = null,
        gateway = gateway,
        eventClient = eventClient,
    )

    private fun populateCreateForm(
        viewModel: CustomProviderFormViewModel,
        apiKey: String,
        headerValue: String,
    ) {
        viewModel.updateProviderId("provider-alpha")
        viewModel.updateDisplayName("Provider Alpha")
        viewModel.updateBaseUrl("https://example.test/v1")
        viewModel.updateApiKey(apiKey)
        val model = viewModel.uiState.value.models.single()
        viewModel.updateModelId(model.rowId, "model-one")
        viewModel.updateModelName(model.rowId, "Model One")
        viewModel.addHeader()
        val header = viewModel.uiState.value.headers.single()
        viewModel.updateHeaderName(header.rowId, "Authorization")
        viewModel.updateHeaderValue(header.rowId, headerValue)
    }

    private fun configuration(
        modelCount: Int,
        headerCount: Int,
    ) = OpenCodeCustomProviderConfiguration(
        id = "provider-alpha",
        name = "Provider Alpha",
        baseUrl = "https://example.test/v1",
        models = List(modelCount) { index ->
            OpenCodeCustomProviderModel("model-$index", "Model $index")
        },
        headers = List(headerCount) { index ->
            OpenCodeCustomProviderHeader("X-Synthetic-$index")
        },
        isDisabled = false,
    )
}

private class FakeCustomProviderGateway(
    private val cleartextConfirmationRequired: Boolean = false,
    private val configuration: OpenCodeCustomProviderConfiguration? = null,
    private val saveOutcome: CustomProviderMutationOutcome = CustomProviderMutationOutcome(
        CustomProviderCommitState.Enabled,
    ),
) : ProviderSettingsGateway {
    val saveCalls = mutableListOf<CustomProviderDraft>()

    override suspend fun loadProviders(
        serverId: String,
        directory: String?,
        workspace: String?,
    ): Result<List<OpenCodeProviderSummary>> = Result.success(emptyList())

    override suspend fun requiresCleartextSecretConfirmation(serverId: String): Result<Boolean> =
        Result.success(cleartextConfirmationRequired)

    override suspend fun loadAuthMethods(
        serverId: String,
        directory: String?,
        workspace: String?,
        providerId: String,
    ): Result<List<ProviderAuthMethod>> = error("Not used")

    override suspend fun connectApiKey(
        serverId: String,
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Result<ProviderMutationOutcome> = error("Not used")

    override suspend fun disconnectProvider(
        serverId: String,
        providerId: String,
    ): Result<ProviderMutationOutcome> = error("Not used")

    override suspend fun authorizeOAuth(
        serverId: String,
        directory: String?,
        workspace: String?,
        providerId: String,
        method: Int,
        inputs: Map<String, String>,
    ): Result<ProviderOAuthAuthorization> = error("Not used")

    override suspend fun completeOAuth(
        serverId: String,
        directory: String?,
        workspace: String?,
        providerId: String,
        method: Int,
        code: String?,
    ): Result<ProviderMutationOutcome> = error("Not used")

    override suspend fun loadCustomProvider(
        serverId: String,
        providerId: String,
    ): Result<OpenCodeCustomProviderConfiguration?> = Result.success(configuration)

    override suspend fun saveCustomProvider(
        serverId: String,
        draft: CustomProviderDraft,
    ): Result<CustomProviderMutationOutcome> {
        saveCalls += draft
        return Result.success(saveOutcome)
    }

    override suspend fun disableCustomProvider(
        serverId: String,
        providerId: String,
    ): Result<CustomProviderMutationOutcome> = Result.success(
        CustomProviderMutationOutcome(CustomProviderCommitState.Disabled),
    )
}
