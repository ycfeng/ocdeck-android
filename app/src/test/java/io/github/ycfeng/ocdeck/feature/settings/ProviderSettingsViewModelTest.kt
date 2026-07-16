package io.github.ycfeng.ocdeck.feature.settings

import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.network.eventClientEnvironment
import io.github.ycfeng.ocdeck.data.opencode.ProviderSettingsGateway
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProviderSource
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProviderSummary
import io.github.ycfeng.ocdeck.domain.model.CustomProviderCommitState
import io.github.ycfeng.ocdeck.domain.model.CustomProviderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderMutationOutcome
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCustomProviderConfiguration
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthCondition
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthConditionOperator
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthMethod
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthMethodType
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthSelectOption
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthSelectPrompt
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthTextPrompt
import io.github.ycfeng.ocdeck.domain.model.ProviderMutationOutcome
import io.github.ycfeng.ocdeck.domain.model.ProviderOAuthAuthorization
import io.github.ycfeng.ocdeck.domain.model.ProviderOAuthMode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSettingsViewModelTest {
    @Test
    fun resumingAfterLeavingScreenRefreshesProvidersWithoutDuplicatingInitialLoad() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeProviderSettingsGateway(cleartextConfirmationRequired = false).apply {
                providers = listOf(provider("provider-alpha", "Alpha Cloud"))
            }
            val viewModel = ProviderSettingsViewModel(
                serverId = "server",
                directory = null,
                workspace = null,
                gateway = gateway,
                eventClient = eventClientEnvironment(backgroundScope).client,
            )
            runCurrent()

            viewModel.onScreenResumed()
            runCurrent()

            assertEquals(1, gateway.loadProviderCalls)
            assertEquals(listOf("provider-alpha"), viewModel.uiState.value.providers.map { it.id })

            gateway.providers = listOf(provider("provider-custom", "Custom Cloud"))
            viewModel.onScreenResumed()
            runCurrent()

            assertEquals(2, gateway.loadProviderCalls)
            assertEquals(listOf("provider-custom"), viewModel.uiState.value.providers.map { it.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cleartextConfirmationFreezesSecretAndConsumesItOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeProviderSettingsGateway(cleartextConfirmationRequired = true)
            val viewModel = ProviderSettingsViewModel(
                serverId = "server",
                directory = "E:/work/app",
                workspace = "workspace",
                gateway = gateway,
                eventClient = eventClientEnvironment(backgroundScope).client,
            )
            runCurrent()
            val firstSecret = "synthetic-first-provider-key"
            val laterSecret = "synthetic-later-provider-key"

            viewModel.connectApiKey("provider-alpha", firstSecret)
            runCurrent()

            assertEquals("provider-alpha", viewModel.uiState.value.cleartextConfirmationProviderId)
            assertTrue(gateway.connectCalls.isEmpty())
            assertFalse(viewModel.uiState.value.toString().contains(firstSecret))

            viewModel.connectApiKey("provider-alpha", laterSecret)
            viewModel.confirmCleartextConnection()
            viewModel.confirmCleartextConnection()
            runCurrent()

            assertEquals(1, gateway.connectCalls.size)
            assertEquals(firstSecret, gateway.connectCalls.single().apiKey)
            assertNull(viewModel.uiState.value.cleartextConfirmationProviderId)
            assertEquals(UiText.Resource(R.string.settings_providers_connected), viewModel.uiState.value.notice)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun dismissingCleartextConfirmationPerformsNoWrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeProviderSettingsGateway(cleartextConfirmationRequired = true)
            val viewModel = ProviderSettingsViewModel(
                serverId = "server",
                directory = null,
                workspace = null,
                gateway = gateway,
                eventClient = eventClientEnvironment(backgroundScope).client,
            )
            runCurrent()

            viewModel.connectApiKey("provider-alpha", "synthetic-provider-key")
            runCurrent()
            viewModel.dismissCleartextConfirmation()
            viewModel.confirmCleartextConnection()
            runCurrent()

            assertTrue(gateway.connectCalls.isEmpty())
            assertNull(viewModel.uiState.value.cleartextConfirmationProviderId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun searchMatchesProviderNameAndIdWithoutCaseSensitivity() {
        val providers = listOf(
            provider("provider-alpha", "Alpha Cloud"),
            provider("provider-beta", "Beta Cloud"),
        )

        assertEquals(
            listOf("provider-alpha"),
            ProviderSettingsUiState(providers = providers, searchQuery = "ALPHA")
                .visibleProviders
                .map(OpenCodeProviderSummary::id),
        )
        assertEquals(
            listOf("provider-beta"),
            ProviderSettingsUiState(providers = providers, searchQuery = "PROVIDER-BETA")
                .visibleProviders
                .map(OpenCodeProviderSummary::id),
        )
    }

    @Test
    fun apiAuthenticationSubmitsOnlyVisiblePromptMetadata() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeProviderSettingsGateway(cleartextConfirmationRequired = false).apply {
                providers = listOf(provider("provider-alpha", "Alpha Cloud"))
                authMethods = listOf(
                    ProviderAuthMethod(
                        wireIndex = 4,
                        type = ProviderAuthMethodType.Api,
                        label = "API token",
                        prompts = listOf(
                            ProviderAuthSelectPrompt(
                                key = "deployment",
                                message = "Deployment",
                                options = listOf(ProviderAuthSelectOption("Public", "public")),
                            ),
                            ProviderAuthTextPrompt(
                                key = "enterpriseUrl",
                                message = "Enterprise URL",
                                condition = ProviderAuthCondition(
                                    key = "deployment",
                                    operator = ProviderAuthConditionOperator.Equals,
                                    value = "enterprise",
                                ),
                            ),
                        ),
                    ),
                )
            }
            val viewModel = ProviderSettingsViewModel(
                serverId = "server",
                directory = "E:/work/app",
                workspace = "workspace",
                gateway = gateway,
                eventClient = eventClientEnvironment(backgroundScope).client,
            )
            runCurrent()

            viewModel.beginAuthentication("provider-alpha")
            runCurrent()
            viewModel.submitApiAuthentication(
                apiKey = "synthetic-provider-key",
                inputs = mapOf(
                    "deployment" to "public",
                    "enterpriseUrl" to "https://hidden.example.test",
                ),
            )
            runCurrent()

            assertEquals(mapOf("deployment" to "public"), gateway.connectCalls.single().metadata)
            assertEquals("E:/work/app", gateway.authMethodCalls.single().directory)
            assertEquals("workspace", gateway.authMethodCalls.single().workspace)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun codeOAuthUsesOriginalMethodIndexAndProjectScope() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = FakeProviderSettingsGateway(cleartextConfirmationRequired = false).apply {
                providers = listOf(provider("provider-alpha", "Alpha Cloud"))
                authMethods = listOf(
                    ProviderAuthMethod(
                        wireIndex = 3,
                        type = ProviderAuthMethodType.OAuth,
                        label = "Device login",
                    ),
                )
                authorization = ProviderOAuthAuthorization(
                    url = "https://example.test/authorize",
                    mode = ProviderOAuthMode.Code,
                    instructions = "Paste code",
                    usesLoopbackUrl = false,
                )
            }
            val viewModel = ProviderSettingsViewModel(
                serverId = "server",
                directory = "E:/work/app",
                workspace = "workspace",
                gateway = gateway,
                eventClient = eventClientEnvironment(backgroundScope).client,
            )
            runCurrent()

            viewModel.beginAuthentication("provider-alpha")
            runCurrent()
            viewModel.authorizeOAuth(emptyMap())
            runCurrent()
            viewModel.completeOAuth(" synthetic-code ")
            runCurrent()

            assertEquals(3, gateway.authorizeCalls.single().method)
            assertEquals(3, gateway.completeOAuthCalls.single().method)
            assertEquals("synthetic-code", gateway.completeOAuthCalls.single().code)
            assertEquals("E:/work/app", gateway.completeOAuthCalls.single().directory)
            assertEquals("workspace", gateway.completeOAuthCalls.single().workspace)
            assertEquals(UiText.Resource(R.string.settings_providers_oauth_connected), viewModel.uiState.value.notice)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun provider(id: String, name: String) = OpenCodeProviderSummary(
        id = id,
        name = name,
        source = OpenCodeProviderSource.Unknown,
        isConnected = false,
        modelCount = 0,
    )
}

private class FakeProviderSettingsGateway(
    private val cleartextConfirmationRequired: Boolean,
) : ProviderSettingsGateway {
    val connectCalls = mutableListOf<ConnectCall>()
    val authMethodCalls = mutableListOf<AuthMethodCall>()
    val authorizeCalls = mutableListOf<OAuthCall>()
    val completeOAuthCalls = mutableListOf<OAuthCall>()
    var loadProviderCalls = 0
    var providers: List<OpenCodeProviderSummary> = emptyList()
    var authMethods: List<ProviderAuthMethod> = listOf(
        ProviderAuthMethod(wireIndex = 0, type = ProviderAuthMethodType.Api, label = null),
    )
    var authorization = ProviderOAuthAuthorization(
        url = "https://example.test/authorize",
        mode = ProviderOAuthMode.Auto,
        instructions = "Continue",
        usesLoopbackUrl = false,
    )
    var mutationOutcome: ProviderMutationOutcome = ProviderMutationOutcome()

    override suspend fun loadProviders(
        serverId: String,
        directory: String?,
        workspace: String?,
    ): Result<List<OpenCodeProviderSummary>> {
        loadProviderCalls++
        return Result.success(providers)
    }

    override suspend fun requiresCleartextSecretConfirmation(serverId: String): Result<Boolean> =
        Result.success(cleartextConfirmationRequired)

    override suspend fun loadAuthMethods(
        serverId: String,
        directory: String?,
        workspace: String?,
        providerId: String,
    ): Result<List<ProviderAuthMethod>> {
        authMethodCalls += AuthMethodCall(directory, workspace)
        return Result.success(authMethods)
    }

    override suspend fun connectApiKey(
        serverId: String,
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Result<ProviderMutationOutcome> {
        connectCalls += ConnectCall(providerId, apiKey, metadata)
        return Result.success(mutationOutcome)
    }

    override suspend fun disconnectProvider(
        serverId: String,
        providerId: String,
    ): Result<ProviderMutationOutcome> = Result.success(mutationOutcome)

    override suspend fun authorizeOAuth(
        serverId: String,
        directory: String?,
        workspace: String?,
        providerId: String,
        method: Int,
        inputs: Map<String, String>,
    ): Result<ProviderOAuthAuthorization> {
        authorizeCalls += OAuthCall(directory, workspace, method, null)
        return Result.success(authorization)
    }

    override suspend fun completeOAuth(
        serverId: String,
        directory: String?,
        workspace: String?,
        providerId: String,
        method: Int,
        code: String?,
    ): Result<ProviderMutationOutcome> {
        completeOAuthCalls += OAuthCall(directory, workspace, method, code)
        return Result.success(mutationOutcome)
    }

    override suspend fun loadCustomProvider(
        serverId: String,
        providerId: String,
    ): Result<OpenCodeCustomProviderConfiguration?> = Result.success(null)

    override suspend fun saveCustomProvider(
        serverId: String,
        draft: CustomProviderDraft,
    ): Result<CustomProviderMutationOutcome> = Result.success(
        CustomProviderMutationOutcome(CustomProviderCommitState.Enabled),
    )

    override suspend fun disableCustomProvider(
        serverId: String,
        providerId: String,
    ): Result<CustomProviderMutationOutcome> = Result.success(
        CustomProviderMutationOutcome(CustomProviderCommitState.Disabled),
    )
}

private data class ConnectCall(
    val providerId: String,
    val apiKey: String,
    val metadata: Map<String, String>,
) {
    override fun toString(): String =
        "ConnectCall(providerId=<redacted>, apiKey=<redacted>, metadataCount=${metadata.size})"
}

private data class AuthMethodCall(
    val directory: String?,
    val workspace: String?,
)

private data class OAuthCall(
    val directory: String?,
    val workspace: String?,
    val method: Int,
    val code: String?,
) {
    override fun toString(): String =
        "OAuthCall(directory=<redacted>, workspacePresent=${workspace != null}, method=$method, " +
            "code=${if (code == null) "null" else "<redacted>"})"
}
