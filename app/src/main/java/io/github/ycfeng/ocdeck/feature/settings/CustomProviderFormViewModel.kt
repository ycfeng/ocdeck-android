package io.github.ycfeng.ocdeck.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.network.OpenCodeEventClient
import io.github.ycfeng.ocdeck.data.opencode.CUSTOM_PROVIDER_MAX_HEADERS
import io.github.ycfeng.ocdeck.data.opencode.CUSTOM_PROVIDER_MAX_MODELS
import io.github.ycfeng.ocdeck.data.opencode.CustomProviderValidationFailure
import io.github.ycfeng.ocdeck.data.opencode.CustomProviderValidationResult
import io.github.ycfeng.ocdeck.data.opencode.ProviderSettingsGateway
import io.github.ycfeng.ocdeck.data.opencode.validateCustomProviderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderCommitState
import io.github.ycfeng.ocdeck.domain.model.CustomProviderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderHeaderDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderModelDraft
import io.github.ycfeng.ocdeck.domain.model.CustomProviderMutationOutcome
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCustomProviderConfiguration
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.toErrorUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CustomProviderFormViewModel(
    private val serverId: String,
    providerId: String?,
    private val gateway: ProviderSettingsGateway,
    private val eventClient: OpenCodeEventClient,
) : ViewModel() {
    private var originalProviderId: String? = providerId
    private var nextRowId = 1L
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var pendingSave: PendingCustomProviderSave? = null

    private val _uiState = MutableStateFlow(
        CustomProviderFormUiState(
            isEditing = providerId != null,
            isLoading = providerId != null,
            isFormReady = providerId == null,
            providerId = providerId.orEmpty(),
            models = if (providerId == null) listOf(newModelRow()) else emptyList(),
        ),
    )
    val uiState: StateFlow<CustomProviderFormUiState> = _uiState.asStateFlow()

    init {
        providerId?.let(::loadProvider)
    }

    fun retryLoad() {
        originalProviderId?.let(::loadProvider)
    }

    fun updateProviderId(value: String) = updateForm { state ->
        if (state.isEditing) state else state.copy(providerId = value)
    }

    fun updateDisplayName(value: String) = updateForm { it.copy(displayName = value) }

    fun updateBaseUrl(value: String) = updateForm { it.copy(baseUrl = value) }

    fun updateApiKey(value: String) = updateForm { it.copy(apiKey = value) }

    fun addModel() = updateForm { state ->
        if (!state.canAddModel) state else state.copy(models = state.models + newModelRow())
    }

    fun updateModelId(rowId: Long, value: String) = updateForm { state ->
        state.copy(
            models = state.models.map { row ->
                if (row.rowId == rowId && !row.isPersisted) row.copy(modelId = value) else row
            },
        )
    }

    fun updateModelName(rowId: Long, value: String) = updateForm { state ->
        state.copy(models = state.models.map { row -> if (row.rowId == rowId) row.copy(name = value) else row })
    }

    fun removeModel(rowId: Long) = updateForm { state ->
        val row = state.models.firstOrNull { it.rowId == rowId }
        if (row == null || row.isPersisted) state else state.copy(models = state.models.filterNot { it.rowId == rowId })
    }

    fun addHeader() = updateForm { state ->
        if (!state.canAddHeader) state else state.copy(headers = state.headers + newHeaderRow())
    }

    fun updateHeaderName(rowId: Long, value: String) = updateForm { state ->
        state.copy(
            headers = state.headers.map { row ->
                if (row.rowId == rowId && !row.isPersisted) row.copy(name = value) else row
            },
        )
    }

    fun updateHeaderValue(rowId: Long, value: String) = updateForm { state ->
        state.copy(headers = state.headers.map { row -> if (row.rowId == rowId) row.copy(value = value) else row })
    }

    fun removeHeader(rowId: Long) = updateForm { state ->
        val row = state.headers.firstOrNull { it.rowId == rowId }
        if (row == null || row.isPersisted) state else state.copy(headers = state.headers.filterNot { it.rowId == rowId })
    }

    fun save() {
        val state = _uiState.value
        if (state.isInteractionLocked || mutationJob?.isActive == true || pendingSave != null) return
        val validated = when (val result = validateCustomProviderDraft(state.toDraft(originalProviderId))) {
            is CustomProviderValidationResult.Valid -> result.draft
            is CustomProviderValidationResult.Invalid -> {
                _uiState.update { it.copy(error = result.failure.toUiText(), notice = null) }
                return
            }
        }
        mutationJob = viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null, notice = null) }
            if (validated.containsNewSecrets) {
                val confirmation = gateway.requiresCleartextSecretConfirmation(serverId)
                if (confirmation.isFailure) {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = confirmation.exceptionOrNull()
                                ?.toErrorUiText(R.string.settings_custom_provider_save_failed),
                        )
                    }
                    return@launch
                }
                if (confirmation.getOrThrow()) {
                    pendingSave = PendingCustomProviderSave(validated)
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            cleartextConfirmationPending = true,
                        )
                    }
                    return@launch
                }
            }
            performSave(validated)
        }
    }

    fun dismissCleartextConfirmation() {
        if (mutationJob?.isActive == true) return
        pendingSave = null
        _uiState.update { it.copy(cleartextConfirmationPending = false) }
    }

    fun confirmCleartextSave() {
        if (mutationJob?.isActive == true) return
        val request = pendingSave ?: return
        pendingSave = null
        mutationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    cleartextConfirmationPending = false,
                    isSaving = true,
                    error = null,
                    notice = null,
                )
            }
            performSave(request.draft)
        }
    }

    fun disable() {
        val providerId = originalProviderId ?: return
        val state = _uiState.value
        if (state.isInteractionLocked || mutationJob?.isActive == true) return
        cancelLoadForMutation()
        mutationJob = viewModelScope.launch {
            _uiState.update { it.copy(isDisabling = true, error = null, notice = null) }
            gateway.disableCustomProvider(serverId, providerId)
                .onSuccess(::completeDisable)
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isDisabling = false,
                            error = throwable.toErrorUiText(R.string.settings_custom_provider_disable_failed),
                        )
                    }
                }
        }
    }

    private fun loadProvider(providerId: String) {
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            gateway.loadCustomProvider(serverId, providerId)
                .onSuccess { configuration ->
                    if (generation != loadGeneration) return@onSuccess
                    if (configuration == null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = UiText.Resource(R.string.settings_custom_provider_not_found),
                            )
                        }
                    } else {
                        originalProviderId = configuration.id
                        _uiState.value = configuration.toUiState()
                    }
                }
                .onFailure { throwable ->
                    if (generation != loadGeneration) return@onFailure
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.toErrorUiText(R.string.settings_custom_provider_load_failed),
                        )
                    }
                }
        }
    }

    private suspend fun performSave(draft: CustomProviderDraft) {
        cancelLoadForMutation()
        gateway.saveCustomProvider(serverId, draft)
            .onSuccess { outcome -> completeSave(draft, outcome) }
            .onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = throwable.toErrorUiText(R.string.settings_custom_provider_save_failed),
                    )
                }
            }
    }

    private fun completeSave(draft: CustomProviderDraft, outcome: CustomProviderMutationOutcome) {
        eventClient.refreshProviderCapabilities(serverId)
        when (outcome.commitState) {
            CustomProviderCommitState.Enabled -> {
                originalProviderId = draft.providerId
                _uiState.value = draft.toPersistedUiState(
                    isDisabled = false,
                    notice = UiText.Resource(
                        if (outcome.instanceRefreshFailure == null) {
                            R.string.settings_custom_provider_saved
                        } else {
                            R.string.settings_custom_provider_saved_refresh_failed
                        },
                    ),
                )
            }
            CustomProviderCommitState.Disabled -> {
                originalProviderId = draft.providerId
                _uiState.value = draft.toPersistedUiState(
                    isDisabled = true,
                    error = outcome.operationFailure
                        ?.toErrorUiText(R.string.settings_custom_provider_save_failed),
                    notice = UiText.Resource(R.string.settings_custom_provider_saved_disabled),
                )
            }
            CustomProviderCommitState.Unknown -> {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        apiKey = "",
                        headers = it.headers.map { row -> row.copy(value = "") },
                        error = outcome.operationFailure
                            ?.toErrorUiText(R.string.settings_custom_provider_save_failed),
                        notice = UiText.Resource(R.string.settings_custom_provider_save_unknown),
                    )
                }
            }
        }
    }

    private fun completeDisable(outcome: CustomProviderMutationOutcome) {
        eventClient.refreshProviderCapabilities(serverId)
        when (outcome.commitState) {
            CustomProviderCommitState.Disabled -> {
                val notice = when {
                    outcome.credentialCleanupFailure != null ->
                        R.string.settings_custom_provider_disabled_cleanup_failed
                    outcome.instanceRefreshFailure != null ->
                        R.string.settings_custom_provider_disabled_refresh_failed
                    else -> R.string.settings_custom_provider_disabled
                }
                _uiState.update {
                    it.copy(
                        isDisabling = false,
                        isDisabled = true,
                        apiKey = "",
                        headers = it.headers.map { row -> row.copy(value = "") },
                        error = outcome.credentialCleanupFailure
                            ?.toErrorUiText(R.string.settings_custom_provider_disable_failed),
                        notice = UiText.Resource(notice),
                    )
                }
            }
            CustomProviderCommitState.Unknown,
            CustomProviderCommitState.Enabled,
            -> {
                _uiState.update {
                    it.copy(
                        isDisabling = false,
                        error = outcome.operationFailure
                            ?.toErrorUiText(R.string.settings_custom_provider_disable_failed),
                        notice = UiText.Resource(R.string.settings_custom_provider_disable_unknown),
                    )
                }
            }
        }
    }

    private fun updateForm(transform: (CustomProviderFormUiState) -> CustomProviderFormUiState) {
        if (_uiState.value.isInteractionLocked) return
        _uiState.update { current ->
            val updated = transform(current)
            if (updated == current) current else updated.copy(error = null, notice = null)
        }
    }

    private fun cancelLoadForMutation() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
    }

    private fun newModelRow(
        modelId: String = "",
        name: String = "",
        isPersisted: Boolean = false,
    ) = CustomProviderModelRowState(
        rowId = nextRowId++,
        modelId = modelId,
        name = name,
        isPersisted = isPersisted,
    )

    private fun newHeaderRow(
        name: String = "",
        isPersisted: Boolean = false,
    ) = CustomProviderHeaderRowState(
        rowId = nextRowId++,
        name = name,
        value = "",
        isPersisted = isPersisted,
    )

    private fun OpenCodeCustomProviderConfiguration.toUiState() = CustomProviderFormUiState(
        isEditing = true,
        isFormReady = true,
        providerId = id,
        displayName = name,
        baseUrl = baseUrl,
        models = models.map { model -> newModelRow(model.id, model.name, isPersisted = true) },
        headers = headers.map { header -> newHeaderRow(header.name, isPersisted = true) },
        isDisabled = isDisabled,
    )

    private fun CustomProviderDraft.toPersistedUiState(
        isDisabled: Boolean,
        error: UiText? = null,
        notice: UiText,
    ) = CustomProviderFormUiState(
        isEditing = true,
        isFormReady = true,
        providerId = providerId,
        displayName = displayName,
        baseUrl = baseUrl,
        models = models.map { model -> newModelRow(model.id, model.name, isPersisted = true) },
        headers = headers.map { header -> newHeaderRow(header.name, isPersisted = true) },
        isDisabled = isDisabled,
        error = error,
        notice = notice,
    )
}

data class CustomProviderFormUiState(
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val isFormReady: Boolean = true,
    val isSaving: Boolean = false,
    val isDisabling: Boolean = false,
    val providerId: String = "",
    val displayName: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val models: List<CustomProviderModelRowState> = emptyList(),
    val headers: List<CustomProviderHeaderRowState> = emptyList(),
    val isDisabled: Boolean = false,
    val cleartextConfirmationPending: Boolean = false,
    val error: UiText? = null,
    val notice: UiText? = null,
) {
    val isInteractionLocked: Boolean
        get() = !isFormReady || isLoading || isSaving || isDisabling || cleartextConfirmationPending

    val modelCount: Int
        get() = models.size

    val headerCount: Int
        get() = headers.size

    val maxModels: Int
        get() = CUSTOM_PROVIDER_MAX_MODELS

    val maxHeaders: Int
        get() = CUSTOM_PROVIDER_MAX_HEADERS

    val canAddModel: Boolean
        get() = modelCount < maxModels

    val canAddHeader: Boolean
        get() = headerCount < maxHeaders

    internal fun toDraft(originalProviderId: String?) = CustomProviderDraft(
        originalProviderId = originalProviderId,
        providerId = providerId,
        displayName = displayName,
        baseUrl = baseUrl,
        apiKey = apiKey.takeIf(String::isNotBlank),
        models = models.map { CustomProviderModelDraft(id = it.modelId, name = it.name) },
        headers = headers.map {
            CustomProviderHeaderDraft(
                name = it.name,
                value = it.value.takeIf(String::isNotBlank),
                retainExisting = it.isPersisted,
            )
        },
    )

    override fun toString(): String =
        "CustomProviderFormUiState(isEditing=$isEditing, isLoading=$isLoading, isFormReady=$isFormReady, " +
            "isSaving=$isSaving, " +
            "isDisabling=$isDisabling, providerId=<redacted>, displayName=<redacted>, baseUrl=<redacted>, " +
            "apiKey=${if (apiKey.isEmpty()) "empty" else "<redacted>"}, modelCount=${models.size}, " +
            "headerCount=${headers.size}, isDisabled=$isDisabled, " +
            "cleartextConfirmationPending=$cleartextConfirmationPending, errorPresent=${error != null}, " +
            "noticePresent=${notice != null})"
}

data class CustomProviderModelRowState(
    val rowId: Long,
    val modelId: String,
    val name: String,
    val isPersisted: Boolean,
) {
    override fun toString(): String =
        "CustomProviderModelRowState(rowId=$rowId, modelId=<redacted>, name=<redacted>, isPersisted=$isPersisted)"
}

data class CustomProviderHeaderRowState(
    val rowId: Long,
    val name: String,
    val value: String,
    val isPersisted: Boolean,
) {
    override fun toString(): String =
        "CustomProviderHeaderRowState(rowId=$rowId, name=<redacted>, " +
            "value=${if (value.isEmpty()) "empty" else "<redacted>"}, isPersisted=$isPersisted)"
}

private data class PendingCustomProviderSave(val draft: CustomProviderDraft) {
    override fun toString(): String = "PendingCustomProviderSave(draft=$draft)"
}

private fun CustomProviderValidationFailure.toUiText(): UiText.Resource = UiText.Resource(
    when (this) {
        CustomProviderValidationFailure.ProviderId -> R.string.settings_custom_provider_error_provider_id
        CustomProviderValidationFailure.DisplayName -> R.string.settings_custom_provider_error_display_name
        CustomProviderValidationFailure.BaseUrl -> R.string.settings_custom_provider_error_base_url
        CustomProviderValidationFailure.ApiKey -> R.string.settings_custom_provider_error_api_key
        CustomProviderValidationFailure.ModelsRequired -> R.string.settings_custom_provider_error_models_required
        CustomProviderValidationFailure.ModelsLimit -> R.string.settings_custom_provider_error_models_limit
        CustomProviderValidationFailure.Model -> R.string.settings_custom_provider_error_model
        CustomProviderValidationFailure.DuplicateModel -> R.string.settings_custom_provider_error_duplicate_model
        CustomProviderValidationFailure.HeadersLimit -> R.string.settings_custom_provider_error_headers_limit
        CustomProviderValidationFailure.Header -> R.string.settings_custom_provider_error_header
        CustomProviderValidationFailure.DuplicateHeader -> R.string.settings_custom_provider_error_duplicate_header
    },
)
