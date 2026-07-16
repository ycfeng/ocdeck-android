package io.github.ycfeng.ocdeck.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.data.opencode.OpenCodeRepository
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModelSettingsGroup
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.toErrorUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ModelSettingsViewModel(
    private val serverId: String,
    private val repository: OpenCodeRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ModelSettingsUiState(isLoading = true))
    val uiState: StateFlow<ModelSettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repository.loadModelSettings(serverId)
                .onSuccess { groups ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            groups = groups,
                            error = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.toErrorUiText(R.string.settings_models_load_failed),
                        )
                    }
                }
        }
    }

    fun setModelEnabled(model: OpenCodeModel, enabled: Boolean) {
        val key = ModelSettingsModelKey(model.providerId, model.modelId)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    groups = it.groups.withUpdatedModelEnabled(model.providerId, model.modelId, enabled),
                    savingModels = it.savingModels + key,
                    error = null,
                )
            }
            repository.setModelEnabled(serverId, model.providerId, model.providerConfigKey, model.modelId, enabled)
                .onSuccess { groups ->
                    _uiState.update {
                        it.copy(
                            groups = groups,
                            savingModels = it.savingModels - key,
                            error = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            savingModels = it.savingModels - key,
                            error = throwable.toErrorUiText(R.string.settings_models_save_failed),
                        )
                    }
                    refresh()
                }
        }
    }
}

data class ModelSettingsUiState(
    val isLoading: Boolean = false,
    val groups: List<OpenCodeModelSettingsGroup> = emptyList(),
    val savingModels: Set<ModelSettingsModelKey> = emptySet(),
    val error: UiText? = null,
) {
    override fun toString(): String =
        "ModelSettingsUiState(isLoading=$isLoading, groupCount=${groups.size}, " +
            "savingModelCount=${savingModels.size}, errorPresent=${error != null})"
}

data class ModelSettingsModelKey(
    val providerId: String,
    val modelId: String,
) {
    override fun toString(): String =
        "ModelSettingsModelKey(providerId=<redacted>, modelId=<redacted>)"
}

private fun List<OpenCodeModelSettingsGroup>.withUpdatedModelEnabled(
    providerId: String,
    modelId: String,
    enabled: Boolean,
): List<OpenCodeModelSettingsGroup> = map { group ->
    if (group.providerId != providerId) return@map group
    group.copy(
        models = group.models.map { model ->
            if (model.modelId == modelId) model.copy(isEnabled = enabled) else model
        },
    )
}
