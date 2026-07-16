package io.github.ycfeng.ocdeck.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.network.OpenCodeEventClient
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerRepository
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.toErrorUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ServerListViewModel(
    private val serverRepository: ServerRepository,
    private val eventClient: OpenCodeEventClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ServerListUiState())
    val uiState: StateFlow<ServerListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { serverRepository.migrateLegacyDefaultServer() }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(error = throwable.toErrorUiText(R.string.servers_load_failed))
                    }
                }
            serverRepository.observeServers().collect { servers ->
                _uiState.update { it.copy(servers = servers, isLoaded = true) }
            }
        }
    }

    fun checkHealth(server: ServerConfig) {
        viewModelScope.launch {
            _uiState.update { it.copy(checkingServerId = server.id, error = null) }
            serverRepository.checkHealth(server)
                .onSuccess { health ->
                    val healthText = health.version
                        ?.let { version -> UiText.Raw("v$version") }
                        ?: UiText.Resource(R.string.server_version_unknown)
                    _uiState.update {
                        it.copy(
                            checkingServerId = null,
                            health = it.health + (server.id to healthText),
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            checkingServerId = null,
                            health = it.health + (server.id to UiText.Resource(R.string.server_status_failed)),
                            error = throwable.toErrorUiText(R.string.server_status_failed),
                        )
                    }
                }
        }
    }

    fun deleteServer(server: ServerConfig) {
        viewModelScope.launch {
            _uiState.update { it.copy(deletingServerId = server.id, error = null) }
            runCatching {
                eventClient.closeServer(server.id)
                serverRepository.deleteServer(server)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        deletingServerId = null,
                        checkingServerId = it.checkingServerId.takeUnless { checkingId -> checkingId == server.id },
                        health = it.health - server.id,
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        deletingServerId = null,
                        error = throwable.toErrorUiText(R.string.servers_delete_failed),
                    )
                }
            }
        }
    }

    fun saveServerOrder(orderedIds: List<String>, onFailure: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            runCatching { serverRepository.saveServerOrder(orderedIds) }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(error = throwable.toErrorUiText(R.string.servers_reorder_failed))
                    }
                    onFailure()
                }
        }
    }
}

data class ServerListUiState(
    val servers: List<ServerConfig> = emptyList(),
    val isLoaded: Boolean = false,
    val health: Map<String, UiText> = emptyMap(),
    val checkingServerId: String? = null,
    val deletingServerId: String? = null,
    val error: UiText? = null,
) {
    override fun toString(): String =
        "ServerListUiState(serverCount=${servers.size}, isLoaded=$isLoaded, healthCount=${health.size}, " +
            "checkingServerId=${if (checkingServerId == null) "null" else "<redacted>"}, " +
            "deletingServerId=${if (deletingServerId == null) "null" else "<redacted>"}, " +
            "errorPresent=${error != null})"
}
