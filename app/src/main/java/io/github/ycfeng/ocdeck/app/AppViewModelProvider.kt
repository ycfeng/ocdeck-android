package io.github.ycfeng.ocdeck.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.feature.file.ProjectFileBrowserViewModel
import io.github.ycfeng.ocdeck.feature.project.ProjectPickerViewModel
import io.github.ycfeng.ocdeck.feature.project.ProjectShellViewModel
import io.github.ycfeng.ocdeck.feature.server.AddServerViewModel
import io.github.ycfeng.ocdeck.feature.server.ServerListViewModel
import io.github.ycfeng.ocdeck.feature.settings.CustomProviderFormViewModel
import io.github.ycfeng.ocdeck.feature.settings.ModelSettingsViewModel
import io.github.ycfeng.ocdeck.feature.settings.ProviderSettingsViewModel
import io.github.ycfeng.ocdeck.feature.session.SessionDetailViewModel

class AppViewModelProvider(private val container: AppContainer) {
    val serverListFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ServerListViewModel(container.serverRepository, container.openCodeEventClient)
        }
    }

    val addServerFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            AddServerViewModel(container.serverRepository)
        }
    }

    fun editServerFactory(serverId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            AddServerViewModel(container.serverRepository, serverId)
        }
    }

    fun projectPickerFactory(serverId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ProjectPickerViewModel(
                serverId = serverId,
                repository = container.openCodeRepository,
                recentProjectStore = container.recentProjectStore,
                pathNormalizer = container.pathNormalizer,
            )
        }
    }

    fun projectShellFactory(serverId: String, directory: String): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ProjectShellViewModel(
                serverId = serverId,
                directory = directory,
                repository = container.openCodeRepository,
                serverRepository = container.serverRepository,
                store = container.openCodeStore,
                eventClient = container.openCodeEventClient,
                recentProjectStore = container.recentProjectStore,
                pathNormalizer = container.pathNormalizer,
            )
        }
    }

    fun projectFileBrowserFactory(serverId: String, directory: String): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ProjectFileBrowserViewModel(
                serverId = serverId,
                directory = container.pathNormalizer.normalize(directory),
                repository = container.openCodeRepository,
                pathNormalizer = container.projectFilePathNormalizer,
            )
        }
    }

    fun sessionDetailFactory(
        serverId: String,
        directory: String,
        sessionId: String,
        initialAgentId: String? = null,
        initialModelSelection: PromptModelSelection? = null,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            SessionDetailViewModel(
                serverId = serverId,
                directory = directory,
                initialSessionId = sessionId,
                repository = container.openCodeRepository,
                serverRepository = container.serverRepository,
                store = container.openCodeStore,
                eventClient = container.openCodeEventClient,
                recentProjectStore = container.recentProjectStore,
                promptSender = container.promptSender,
                sessionOperationCoordinator = container.sessionOperationCoordinator,
                pathNormalizer = container.pathNormalizer,
                projectFilePathNormalizer = container.projectFilePathNormalizer,
                initialAgentId = initialAgentId,
                initialModelSelection = initialModelSelection,
            )
        }
    }

    fun modelSettingsFactory(serverId: String): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ModelSettingsViewModel(
                serverId = serverId,
                repository = container.openCodeRepository,
            )
        }
    }

    fun providerSettingsFactory(
        serverId: String,
        directory: String?,
        workspace: String?,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            ProviderSettingsViewModel(
                serverId = serverId,
                directory = directory,
                workspace = workspace,
                gateway = container.openCodeProviderRepository,
                eventClient = container.openCodeEventClient,
            )
        }
    }

    fun customProviderFormFactory(
        serverId: String,
        providerId: String?,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            CustomProviderFormViewModel(
                serverId = serverId,
                providerId = providerId,
                gateway = container.openCodeProviderRepository,
                eventClient = container.openCodeEventClient,
            )
        }
    }
}

internal fun CreationExtras.requireAppContainer(): AppContainer {
    error("AppContainer is supplied explicitly by AppViewModelProvider factories.")
}
