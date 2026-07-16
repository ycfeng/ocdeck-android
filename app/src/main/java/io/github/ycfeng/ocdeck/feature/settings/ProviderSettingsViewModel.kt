package io.github.ycfeng.ocdeck.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.network.OpenCodeEventClient
import io.github.ycfeng.ocdeck.data.opencode.ProviderSettingsGateway
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProviderSummary
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthMethod
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthMethodType
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthSelectPrompt
import io.github.ycfeng.ocdeck.domain.model.ProviderMutationOutcome
import io.github.ycfeng.ocdeck.domain.model.ProviderOAuthAuthorization
import io.github.ycfeng.ocdeck.domain.model.ProviderOAuthMode
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.toErrorUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProviderSettingsViewModel(
    private val serverId: String,
    private val directory: String?,
    private val workspace: String?,
    private val gateway: ProviderSettingsGateway,
    private val eventClient: OpenCodeEventClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProviderSettingsUiState(isLoading = true))
    val uiState: StateFlow<ProviderSettingsUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private var mutationJob: Job? = null
    private var authJob: Job? = null
    private var authGeneration = 0L
    private var oauthCallbackJob: Job? = null
    private var pendingApiKeyMutation: PendingApiKeyMutation? = null
    private var hasResumed = false

    init {
        refresh()
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onScreenResumed() {
        if (hasResumed) {
            refresh()
        } else {
            hasResumed = true
        }
    }

    fun refresh() {
        if (mutationJob?.isActive == true || oauthCallbackJob?.isActive == true || _uiState.value.authentication != null) {
            return
        }
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            gateway.loadProviders(serverId, directory, workspace)
                .onSuccess { providers ->
                    if (generation != refreshGeneration) return@onSuccess
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            providers = providers,
                            error = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    if (generation != refreshGeneration) return@onFailure
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.toErrorUiText(R.string.settings_providers_load_failed),
                        )
                    }
                }
        }
    }

    fun beginAuthentication(providerId: String) {
        if (hasActiveMutation() || pendingApiKeyMutation != null) return
        val provider = _uiState.value.providers.firstOrNull { it.id == providerId } ?: return
        val generation = ++authGeneration
        authJob?.cancel()
        _uiState.update {
            it.copy(
                authentication = ProviderAuthenticationUiState(
                    providerId = provider.id,
                    providerName = provider.name,
                    isLoadingMethods = true,
                ),
                error = null,
                notice = null,
            )
        }
        authJob = viewModelScope.launch {
            gateway.loadAuthMethods(serverId, directory, workspace, providerId)
                .onSuccess { methods ->
                    if (generation != authGeneration) return@onSuccess
                    _uiState.update { state ->
                        val current = state.authentication
                        if (current?.providerId != providerId) return@update state
                        state.copy(
                            authentication = current.copy(
                                methods = methods,
                                selectedMethodWireIndex = methods.singleOrNull()?.wireIndex,
                                isLoadingMethods = false,
                            ),
                        )
                    }
                }
                .onFailure { throwable ->
                    if (generation != authGeneration) return@onFailure
                    _uiState.update {
                        it.copy(
                            authentication = null,
                            error = throwable.toErrorUiText(R.string.settings_providers_auth_methods_failed),
                        )
                    }
                }
        }
    }

    fun selectAuthenticationMethod(wireIndex: Int) {
        _uiState.update { state ->
            val authentication = state.authentication ?: return@update state
            if (authentication.isBusy || authentication.methods.none { it.wireIndex == wireIndex }) {
                return@update state
            }
            state.copy(
                authentication = authentication.copy(
                    selectedMethodWireIndex = wireIndex,
                    authorization = null,
                ),
                error = null,
            )
        }
    }

    fun dismissAuthentication() {
        val authentication = _uiState.value.authentication ?: return
        val wasRunning = authentication.isAuthorizing || authentication.isCompletingOAuth
        authGeneration++
        authJob?.cancel()
        authJob = null
        oauthCallbackJob?.cancel()
        oauthCallbackJob = null
        _uiState.update {
            it.copy(
                authentication = null,
                mutatingProviderId = null,
                notice = if (wasRunning) {
                    UiText.Resource(R.string.settings_providers_oauth_cancelled_check_state)
                } else {
                    it.notice
                },
            )
        }
        if (wasRunning) refresh()
    }

    fun submitApiAuthentication(apiKey: String, inputs: Map<String, String>) {
        val authentication = _uiState.value.authentication ?: return
        val method = authentication.selectedMethod ?: return
        if (method.type != ProviderAuthMethodType.Api) return
        val metadata = validatedInputs(method, inputs) ?: return
        _uiState.update { it.copy(authentication = null) }
        connectApiKey(authentication.providerId, apiKey, metadata)
    }

    fun authorizeOAuth(inputs: Map<String, String>) {
        if (hasActiveMutation()) return
        val authentication = _uiState.value.authentication ?: return
        val method = authentication.selectedMethod ?: return
        if (method.type != ProviderAuthMethodType.OAuth || authentication.isBusy) return
        val validatedInputs = validatedInputs(method, inputs) ?: return
        val generation = ++authGeneration
        authJob?.cancel()
        _uiState.update {
            it.copy(
                authentication = authentication.copy(isAuthorizing = true),
                error = null,
                notice = null,
            )
        }
        authJob = viewModelScope.launch {
            gateway.authorizeOAuth(
                serverId = serverId,
                directory = directory,
                workspace = workspace,
                providerId = authentication.providerId,
                method = method.wireIndex,
                inputs = validatedInputs,
            ).onSuccess { authorization ->
                if (generation != authGeneration) return@onSuccess
                _uiState.update { state ->
                    val current = state.authentication
                    if (current?.providerId != authentication.providerId) return@update state
                    state.copy(
                        authentication = current.copy(
                            isAuthorizing = false,
                            authorization = authorization,
                        ),
                    )
                }
            }.onFailure { throwable ->
                if (generation != authGeneration) return@onFailure
                _uiState.update { state ->
                    state.copy(
                        authentication = state.authentication?.copy(isAuthorizing = false),
                        error = throwable.toErrorUiText(R.string.settings_providers_oauth_authorize_failed),
                    )
                }
            }
        }
    }

    fun completeOAuth(code: String? = null) {
        if (hasActiveMutation()) return
        val authentication = _uiState.value.authentication ?: return
        val method = authentication.selectedMethod ?: return
        val authorization = authentication.authorization ?: return
        if (method.type != ProviderAuthMethodType.OAuth || authentication.isBusy) return
        val normalizedCode = code?.trim()?.takeIf(String::isNotEmpty)
        if (authorization.mode == ProviderOAuthMode.Code && normalizedCode == null) {
            _uiState.update { it.copy(error = UiText.Resource(R.string.settings_providers_oauth_code_required)) }
            return
        }
        _uiState.update {
            it.copy(
                authentication = authentication.copy(isCompletingOAuth = true),
                mutatingProviderId = authentication.providerId,
                error = null,
                notice = null,
            )
        }
        oauthCallbackJob = viewModelScope.launch {
            gateway.completeOAuth(
                serverId = serverId,
                directory = directory,
                workspace = workspace,
                providerId = authentication.providerId,
                method = method.wireIndex,
                code = normalizedCode,
            ).onSuccess { outcome ->
                authGeneration++
                _uiState.update { it.copy(authentication = null) }
                completeMutation(outcome, R.string.settings_providers_oauth_connected)
            }.onFailure { throwable ->
                authGeneration++
                _uiState.update {
                    it.copy(
                        authentication = null,
                        mutatingProviderId = null,
                        error = throwable.toErrorUiText(R.string.settings_providers_oauth_callback_failed),
                        notice = UiText.Resource(R.string.settings_providers_oauth_failed_check_state),
                    )
                }
            }
        }
    }

    fun cancelOAuth() {
        dismissAuthentication()
    }

    fun reportBrowserOpenFailure() {
        _uiState.update { it.copy(error = UiText.Resource(R.string.settings_providers_oauth_browser_failed)) }
    }

    fun connectApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String> = emptyMap(),
    ) {
        if (hasActiveMutation() || pendingApiKeyMutation != null) return
        if (providerId.isBlank() || apiKey.isBlank()) {
            _uiState.update { it.copy(error = UiText.Resource(R.string.settings_providers_api_key_required)) }
            return
        }
        val request = PendingApiKeyMutation(providerId = providerId, apiKey = apiKey, metadata = metadata)
        mutationJob = viewModelScope.launch {
            _uiState.update { it.copy(mutatingProviderId = providerId, error = null, notice = null) }
            gateway.requiresCleartextSecretConfirmation(serverId)
                .onSuccess { confirmationRequired ->
                    if (confirmationRequired) {
                        pendingApiKeyMutation = request
                        _uiState.update {
                            it.copy(
                                mutatingProviderId = null,
                                cleartextConfirmationProviderId = providerId,
                            )
                        }
                    } else {
                        performConnect(request)
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            mutatingProviderId = null,
                            error = throwable.toErrorUiText(R.string.settings_providers_connect_failed),
                        )
                    }
                }
        }
    }

    fun dismissCleartextConfirmation() {
        if (mutationJob?.isActive == true) return
        pendingApiKeyMutation = null
        _uiState.update { it.copy(cleartextConfirmationProviderId = null) }
    }

    fun confirmCleartextConnection() {
        if (mutationJob?.isActive == true) return
        val request = pendingApiKeyMutation ?: return
        pendingApiKeyMutation = null
        mutationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    cleartextConfirmationProviderId = null,
                    mutatingProviderId = request.providerId,
                    error = null,
                    notice = null,
                )
            }
            performConnect(request)
        }
    }

    fun disconnect(providerId: String) {
        if (hasActiveMutation() || providerId.isBlank()) return
        cancelRefreshForMutation()
        mutationJob = viewModelScope.launch {
            _uiState.update { it.copy(mutatingProviderId = providerId, error = null, notice = null) }
            gateway.disconnectProvider(serverId, providerId)
                .onSuccess { outcome -> completeMutation(outcome, R.string.settings_providers_disconnected) }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            mutatingProviderId = null,
                            error = throwable.toErrorUiText(R.string.settings_providers_disconnect_failed),
                        )
                    }
                }
        }
    }

    private suspend fun performConnect(request: PendingApiKeyMutation) {
        cancelRefreshForMutation()
        gateway.connectApiKey(serverId, request.providerId, request.apiKey, request.metadata)
            .onSuccess { outcome -> completeMutation(outcome, R.string.settings_providers_connected) }
            .onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        mutatingProviderId = null,
                        error = throwable.toErrorUiText(R.string.settings_providers_connect_failed),
                    )
                }
            }
    }

    private suspend fun completeMutation(outcome: ProviderMutationOutcome, successMessage: Int) {
        eventClient.refreshProviderCapabilities(serverId)
        gateway.loadProviders(serverId, directory, workspace)
            .onSuccess { providers ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        providers = providers,
                        mutatingProviderId = null,
                        error = null,
                        notice = UiText.Resource(
                            if (outcome.instanceRefreshFailure == null) {
                                successMessage
                            } else {
                                R.string.settings_providers_saved_refresh_failed
                            },
                        ),
                    )
                }
            }
            .onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        mutatingProviderId = null,
                        error = throwable.toErrorUiText(R.string.settings_providers_reload_failed),
                        notice = UiText.Resource(
                            if (outcome.instanceRefreshFailure == null) {
                                successMessage
                            } else {
                                R.string.settings_providers_saved_refresh_failed
                            },
                        ),
                    )
                }
            }
    }

    private fun cancelRefreshForMutation() {
        refreshGeneration++
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun validatedInputs(
        method: ProviderAuthMethod,
        inputs: Map<String, String>,
    ): Map<String, String>? {
        val visiblePrompts = method.visiblePrompts(inputs)
        val normalized = linkedMapOf<String, String>()
        for (prompt in visiblePrompts) {
            val value = inputs[prompt.key]?.trim().orEmpty()
            val valid = value.isNotEmpty() &&
                (prompt !is ProviderAuthSelectPrompt || prompt.options.any { it.value == value })
            if (!valid) {
                _uiState.update { it.copy(error = UiText.Resource(R.string.settings_providers_auth_fields_required)) }
                return null
            }
            normalized[prompt.key] = value
        }
        return normalized
    }

    private fun hasActiveMutation(): Boolean =
        mutationJob?.isActive == true || oauthCallbackJob?.isActive == true
}

data class ProviderSettingsUiState(
    val isLoading: Boolean = false,
    val providers: List<OpenCodeProviderSummary> = emptyList(),
    val searchQuery: String = "",
    val mutatingProviderId: String? = null,
    val cleartextConfirmationProviderId: String? = null,
    val authentication: ProviderAuthenticationUiState? = null,
    val error: UiText? = null,
    val notice: UiText? = null,
) {
    val visibleProviders: List<OpenCodeProviderSummary>
        get() {
            val query = searchQuery.trim()
            if (query.isEmpty()) return providers
            return providers.filter { provider ->
                provider.name.contains(query, ignoreCase = true) ||
                    provider.id.contains(query, ignoreCase = true)
            }
        }

    override fun toString(): String =
        "ProviderSettingsUiState(isLoading=$isLoading, providerCount=${providers.size}, " +
            "searchQueryPresent=${searchQuery.isNotEmpty()}, mutatingProviderPresent=${mutatingProviderId != null}, " +
            "cleartextConfirmationPresent=${cleartextConfirmationProviderId != null}, " +
            "authenticationPresent=${authentication != null}, errorPresent=${error != null}, " +
            "noticePresent=${notice != null})"
}

data class ProviderAuthenticationUiState(
    val providerId: String,
    val providerName: String,
    val methods: List<ProviderAuthMethod> = emptyList(),
    val selectedMethodWireIndex: Int? = null,
    val isLoadingMethods: Boolean = false,
    val isAuthorizing: Boolean = false,
    val authorization: ProviderOAuthAuthorization? = null,
    val isCompletingOAuth: Boolean = false,
) {
    val selectedMethod: ProviderAuthMethod?
        get() = methods.firstOrNull { it.wireIndex == selectedMethodWireIndex }

    val isBusy: Boolean
        get() = isLoadingMethods || isAuthorizing || isCompletingOAuth

    override fun toString(): String =
        "ProviderAuthenticationUiState(providerId=<redacted>, providerName=<redacted>, " +
            "methodCount=${methods.size}, selectedMethodPresent=${selectedMethod != null}, " +
            "isLoadingMethods=$isLoadingMethods, isAuthorizing=$isAuthorizing, " +
            "authorizationPresent=${authorization != null}, isCompletingOAuth=$isCompletingOAuth)"
}

private data class PendingApiKeyMutation(
    val providerId: String,
    val apiKey: String,
    val metadata: Map<String, String>,
) {
    override fun toString(): String =
        "PendingApiKeyMutation(providerId=<redacted>, apiKey=<redacted>, metadataCount=${metadata.size})"
}
