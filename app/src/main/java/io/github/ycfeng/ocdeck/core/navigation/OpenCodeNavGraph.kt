package io.github.ycfeng.ocdeck.core.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.app.AppContainer
import io.github.ycfeng.ocdeck.data.server.safeServerBaseUrlForDisplay
import io.github.ycfeng.ocdeck.core.notification.OpenCodeNotificationTarget
import io.github.ycfeng.ocdeck.core.sound.OpenCodeSoundCatalog
import io.github.ycfeng.ocdeck.data.settings.AppColorSchemePreference
import io.github.ycfeng.ocdeck.data.settings.AppLanguagePreference
import io.github.ycfeng.ocdeck.data.settings.AppNotificationSettings
import io.github.ycfeng.ocdeck.data.settings.AppSoundSettings
import io.github.ycfeng.ocdeck.domain.prompt.OpenCodePromptSender
import io.github.ycfeng.ocdeck.feature.placeholder.PlaceholderScreen
import io.github.ycfeng.ocdeck.feature.project.ProjectPickerScreen
import io.github.ycfeng.ocdeck.feature.project.ProjectPickerViewModel
import io.github.ycfeng.ocdeck.feature.project.ProjectShellScreen
import io.github.ycfeng.ocdeck.feature.project.ProjectShellViewModel
import io.github.ycfeng.ocdeck.feature.server.AddServerScreen
import io.github.ycfeng.ocdeck.feature.server.AddServerViewModel
import io.github.ycfeng.ocdeck.feature.server.ServerListScreen
import io.github.ycfeng.ocdeck.feature.server.ServerListViewModel
import io.github.ycfeng.ocdeck.feature.settings.AboutScreen
import io.github.ycfeng.ocdeck.feature.settings.BackgroundRunSettingsScreen
import io.github.ycfeng.ocdeck.feature.settings.CustomProviderFormViewModel
import io.github.ycfeng.ocdeck.feature.settings.CustomProviderFormScreen
import io.github.ycfeng.ocdeck.feature.settings.GeneralSettingsScreen
import io.github.ycfeng.ocdeck.feature.settings.ModelSettingsScreen
import io.github.ycfeng.ocdeck.feature.settings.ModelSettingsViewModel
import io.github.ycfeng.ocdeck.feature.settings.ProviderSettingsScreen
import io.github.ycfeng.ocdeck.feature.settings.ProviderSettingsViewModel
import io.github.ycfeng.ocdeck.feature.settings.SettingsScreen
import io.github.ycfeng.ocdeck.feature.session.SessionDetailScreen
import io.github.ycfeng.ocdeck.feature.session.SessionDetailViewModel
import kotlinx.coroutines.launch

@Composable
fun OpenCodeNavGraph(
    navController: NavHostController,
    appContainer: AppContainer,
    notificationTarget: OpenCodeNotificationTarget? = null,
    onNotificationTargetConsumed: () -> Unit = {},
) {
    fun openExistingServerList() {
        val restoredExistingList = navController.popBackStack(
            route = OpenCodeRoutes.serverList,
            inclusive = false,
        )
        if (!restoredExistingList) {
            navController.navigate(OpenCodeRoutes.serverList) {
                launchSingleTop = true
            }
        }
    }

    fun openProjectHomeFromDrawer(serverId: String, directory: String) {
        val normalizedDirectory = appContainer.pathNormalizer.normalize(directory)
        appContainer.recentProjectRecorder.recordAdd(serverId, normalizedDirectory)
        val activeProject = navController.currentBackStackEntry?.activeProjectDrawerRoute()
        val navigation = resolveProjectDrawerNavigation(
            activeProject = activeProject,
            targetServerId = serverId,
            targetDirectory = normalizedDirectory,
            pathNormalizer = appContainer.pathNormalizer,
        )
        if (navigation == ProjectDrawerNavigation.CloseDrawerOnly) {
            return
        }

        val targetRoute = OpenCodeRoutes.projectShell(serverId, normalizedDirectory)
        val restoredExistingProject = navController.popBackStack(
            route = targetRoute,
            inclusive = false,
        )
        if (!restoredExistingProject) {
            val sessionProject = activeProject
            if (
                navigation == ProjectDrawerNavigation.OpenProjectHomeFromSession &&
                sessionProject != null
            ) {
                // Project switches leave the current project's home in history, not its detail pages.
                while (true) {
                    val route = navController.currentBackStackEntry?.activeProjectDrawerRoute()
                    val isCurrentProjectSession = route?.let {
                        it.sessionId != null &&
                            it.serverId == sessionProject.serverId &&
                            appContainer.pathNormalizer.areSame(it.directory, sessionProject.directory)
                    } == true
                    if (!isCurrentProjectSession) break
                    if (!navController.popBackStack()) break
                }
                val revealedProject = navController.currentBackStackEntry?.activeProjectDrawerRoute()
                if (
                    resolveProjectDrawerNavigation(
                        activeProject = revealedProject,
                        targetServerId = serverId,
                        targetDirectory = normalizedDirectory,
                        pathNormalizer = appContainer.pathNormalizer,
                    ) == ProjectDrawerNavigation.CloseDrawerOnly
                ) {
                    return
                }
            }
            navController.navigate(targetRoute)
        }
    }

    val currentBackStackEntry = navController.currentBackStackEntryAsState().value

    LaunchedEffect(notificationTarget) {
        val target = notificationTarget?.takeIf { it.isValid() } ?: return@LaunchedEffect
        val normalizedDirectory = appContainer.pathNormalizer.normalize(target.directory)
        appContainer.recentProjectRecorder.recordAdd(target.serverId, normalizedDirectory)
        val route = target.sessionId
            ?.let { sessionId -> OpenCodeRoutes.sessionDetail(target.serverId, normalizedDirectory, sessionId) }
            ?: OpenCodeRoutes.projectShell(target.serverId, normalizedDirectory)
        navController.navigate(route) {
            launchSingleTop = true
        }
        onNotificationTargetConsumed()
    }

    ProjectDrawerHost(
        activeProject = currentBackStackEntry?.activeProjectDrawerRoute(),
        appContainer = appContainer,
        onOpenProject = ::openProjectHomeFromDrawer,
        onOpenProjectPicker = { serverId -> navController.navigate(OpenCodeRoutes.projectPicker(serverId)) },
        onOpenSettings = { serverId -> navController.navigate(OpenCodeRoutes.settings(serverId)) },
        onOpenSession = { serverId, directory, sessionId ->
            val normalizedDirectory = appContainer.pathNormalizer.normalize(directory)
            appContainer.recentProjectRecorder.recordAdd(serverId, normalizedDirectory)
            navController.navigate(OpenCodeRoutes.sessionDetail(serverId, normalizedDirectory, sessionId)) {
                launchSingleTop = true
            }
        },
        onCloseProject = { serverId, directory ->
            navController.navigate(OpenCodeRoutes.projectPicker(serverId)) {
                popUpTo(OpenCodeRoutes.projectShell(serverId, directory)) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        },
    ) { onOpenProjectDrawer, onOpenProjectFiles, onPickProjectFiles ->
        NavHost(
            navController = navController,
            startDestination = OpenCodeRoutes.serverList,
            enterTransition = {
                if (isSessionDetailInternalNavigation()) {
                    EnterTransition.None
                } else {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(NavigationAnimationDurationMillis),
                    )
                }
            },
            exitTransition = {
                if (isSessionDetailInternalNavigation()) {
                    ExitTransition.None
                } else {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(NavigationAnimationDurationMillis),
                    )
                }
            },
            popEnterTransition = {
                if (isSessionDetailInternalNavigation()) {
                    EnterTransition.None
                } else {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(NavigationAnimationDurationMillis),
                    )
                }
            },
            popExitTransition = {
                if (isSessionDetailInternalNavigation()) {
                    ExitTransition.None
                } else {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(NavigationAnimationDurationMillis),
                    )
                }
            },
        ) {
        composable(OpenCodeRoutes.serverList) {
            val viewModel: ServerListViewModel = viewModel(factory = appContainer.viewModelProvider.serverListFactory)
            ServerListScreen(
                viewModel = viewModel,
                onAddServer = { navController.navigate(OpenCodeRoutes.addServer) },
                onEditServer = { serverId -> navController.navigate(OpenCodeRoutes.editServer(serverId)) },
                onOpenServer = { serverId -> navController.navigate(OpenCodeRoutes.projectPicker(serverId)) },
                onOpenSettings = { navController.navigate(OpenCodeRoutes.noServerSettings) },
            )
        }

        composable(OpenCodeRoutes.noServerSettings) {
            SettingsScreen(
                currentServerUrl = null,
                onBack = { navController.popBackStack() },
                onOpenGeneralSettings = { navController.navigate(OpenCodeRoutes.noServerGeneralSettings) },
                onOpenServers = ::openExistingServerList,
                onOpenBackgroundRunSettings = { navController.navigate(OpenCodeRoutes.noServerBackgroundRunSettings) },
                onOpenAbout = { navController.navigate(AboutRoute) },
                onOpenProviders = null,
                onOpenModels = null,
            )
        }

        composable(OpenCodeRoutes.noServerGeneralSettings) {
            GeneralSettingsRoute(
                appContainer = appContainer,
                currentServerUrl = null,
                onBack = { navController.popBackStack() },
            )
        }

        composable(OpenCodeRoutes.noServerBackgroundRunSettings) {
            BackgroundRunSettingsScreen(
                currentServerUrl = null,
                onBack = { navController.popBackStack() },
            )
        }

        composable<AboutRoute> {
            AboutScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(OpenCodeRoutes.addServer) {
            val viewModel: AddServerViewModel = viewModel(factory = appContainer.viewModelProvider.addServerFactory)
            AddServerScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSaved = { openExistingServerList() },
            )
        }

        composable(OpenCodeRoutes.editServerPattern) { entry ->
            val serverId = entry.requireStringArg("serverId")
            val viewModel: AddServerViewModel = viewModel(
                key = "edit-server-$serverId",
                factory = appContainer.viewModelProvider.editServerFactory(serverId),
            )
            AddServerScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSaved = { openExistingServerList() },
            )
        }

        composable(OpenCodeRoutes.projectPickerPattern) { entry ->
            val serverId = entry.requireStringArg("serverId")
            val viewModel: ProjectPickerViewModel = viewModel(
                key = "project-picker-$serverId",
                factory = appContainer.viewModelProvider.projectPickerFactory(serverId),
            )
            ProjectPickerScreen(
                viewModel = viewModel,
                onOpenServers = ::openExistingServerList,
                onOpenSettings = { navController.navigate(OpenCodeRoutes.settings(serverId)) },
                onOpenProject = { directory ->
                    navController.navigate(OpenCodeRoutes.projectShell(serverId, directory)) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(OpenCodeRoutes.projectShellPattern) { entry ->
            val serverId = entry.requireStringArg("serverId")
            val directory = entry.requireStringArg("directory")
            val viewModel: ProjectShellViewModel = viewModel(
                key = "project-shell-$serverId-$directory",
                factory = appContainer.viewModelProvider.projectShellFactory(serverId, directory),
            )
            ProjectShellScreen(
                viewModel = viewModel,
                onOpenDrawer = onOpenProjectDrawer,
                onOpenFiles = onOpenProjectFiles,
                onOpenServers = ::openExistingServerList,
                onOpenProviders = { navController.navigate(OpenCodeRoutes.providerSettings(serverId, directory)) },
                onOpenModels = { navController.navigate(OpenCodeRoutes.modelSettings(serverId)) },
                onOpenSession = { sessionId ->
                    appContainer.recentProjectRecorder.recordAdd(serverId, directory)
                    navController.navigate(OpenCodeRoutes.sessionDetail(serverId, directory, sessionId)) {
                        launchSingleTop = true
                    }
                },
                onOpenNewSession = { agentId, modelSelection ->
                    appContainer.recentProjectRecorder.recordAdd(serverId, directory)
                    navController.navigate(
                        OpenCodeRoutes.sessionDetail(
                            serverId = serverId,
                            directory = directory,
                            sessionId = OpenCodePromptSender.NEW_SESSION_ID,
                            initialAgentId = agentId,
                            initialModelSelection = modelSelection,
                        ),
                    ) {
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = OpenCodeRoutes.sessionDetailPattern,
            arguments = listOf(
                navArgument(OpenCodeRoutes.sessionInitialAgentArg) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(OpenCodeRoutes.sessionInitialProviderArg) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(OpenCodeRoutes.sessionInitialModelArg) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(OpenCodeRoutes.sessionInitialVariantArg) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val serverId = entry.requireStringArg("serverId")
            val directory = entry.requireStringArg("directory")
            val sessionId = entry.requireStringArg("sessionId")
            val initialSelection = resolveSessionComposerRouteSelection(
                sessionId = sessionId,
                initialAgentId = entry.arguments?.getString(OpenCodeRoutes.sessionInitialAgentArg),
                initialProviderId = entry.arguments?.getString(OpenCodeRoutes.sessionInitialProviderArg),
                initialModelId = entry.arguments?.getString(OpenCodeRoutes.sessionInitialModelArg),
                initialVariant = entry.arguments?.getString(OpenCodeRoutes.sessionInitialVariantArg),
            )
            val viewModel: SessionDetailViewModel = viewModel(
                key = "session-$serverId-$directory-$sessionId",
                factory = appContainer.viewModelProvider.sessionDetailFactory(
                    serverId = serverId,
                    directory = directory,
                    sessionId = sessionId,
                    initialAgentId = initialSelection.agentId,
                    initialModelSelection = initialSelection.modelSelection,
                ),
            )
            SessionDestinationVisibilityEffect(entry, viewModel)
            SessionDetailScreen(
                viewModel = viewModel,
                onOpenDrawer = onOpenProjectDrawer,
                onOpenFiles = onOpenProjectFiles,
                onPickProjectFiles = onPickProjectFiles,
                onOpenServers = ::openExistingServerList,
                onOpenProviders = { navController.navigate(OpenCodeRoutes.providerSettings(serverId, directory)) },
                onOpenModels = { navController.navigate(OpenCodeRoutes.modelSettings(serverId)) },
                onOpenSession = { nextSessionId ->
                    if (nextSessionId != sessionId) {
                        appContainer.recentProjectRecorder.recordAdd(serverId, directory)
                        navController.navigate(OpenCodeRoutes.sessionDetail(serverId, directory, nextSessionId)) {
                            launchSingleTop = true
                        }
                    }
                },
                onSessionMaterialized = { materializedSessionId ->
                    appContainer.recentProjectRecorder.recordAdd(serverId, directory)
                    navController.navigate(OpenCodeRoutes.sessionDetail(serverId, directory, materializedSessionId)) {
                        popUpTo(OpenCodeRoutes.projectShell(serverId, directory))
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = OpenCodeRoutes.reviewPattern,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) {
            PlaceholderScreen(
                title = stringResource(R.string.placeholder_review_title),
                subtitle = stringResource(R.string.placeholder_review_subtitle),
                onBack = { navController.popBackStack() },
            )
        }

        composable(OpenCodeRoutes.settingsPattern) { entry ->
            val serverId = entry.requireStringArg("serverId")
            val servers = appContainer.serverRepository.observeServers().collectAsStateWithLifecycle(emptyList()).value
            val currentServerUrl = servers.firstOrNull { it.id == serverId }
                ?.baseUrl
                ?.let(::safeServerBaseUrlForDisplay)
            SettingsScreen(
                currentServerUrl = currentServerUrl,
                onBack = { navController.popBackStack() },
                onOpenGeneralSettings = { navController.navigate(OpenCodeRoutes.generalSettings(serverId)) },
                onOpenServers = ::openExistingServerList,
                onOpenBackgroundRunSettings = { navController.navigate(OpenCodeRoutes.backgroundRunSettings(serverId)) },
                onOpenAbout = { navController.navigate(AboutRoute) },
                onOpenProviders = currentServerUrl?.let { { navController.navigate(OpenCodeRoutes.providerSettings(serverId)) } },
                onOpenModels = currentServerUrl?.let { { navController.navigate(OpenCodeRoutes.modelSettings(serverId)) } },
            )
        }

        composable(OpenCodeRoutes.generalSettingsPattern) { entry ->
            val serverId = entry.requireStringArg("serverId")
            val servers = appContainer.serverRepository.observeServers().collectAsStateWithLifecycle(emptyList()).value
            val currentServerUrl = servers.firstOrNull { it.id == serverId }
                ?.baseUrl
                ?.let(::safeServerBaseUrlForDisplay)
            GeneralSettingsRoute(
                appContainer = appContainer,
                currentServerUrl = currentServerUrl,
                onBack = { navController.popBackStack() },
            )
        }

        composable(OpenCodeRoutes.backgroundRunSettingsPattern) { entry ->
            val serverId = entry.requireStringArg("serverId")
            val servers = appContainer.serverRepository.observeServers().collectAsStateWithLifecycle(emptyList()).value
            val currentServerUrl = servers.firstOrNull { it.id == serverId }
                ?.baseUrl
                ?.let(::safeServerBaseUrlForDisplay)
            BackgroundRunSettingsScreen(
                currentServerUrl = currentServerUrl,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = OpenCodeRoutes.providerSettingsPattern,
            arguments = providerScopeArguments(),
        ) { entry ->
            val serverId = entry.requireStringArg("serverId")
            val directory = entry.arguments?.getString(OpenCodeRoutes.providerDirectoryArg)
            val workspace = entry.arguments?.getString(OpenCodeRoutes.providerWorkspaceArg)
            val servers = appContainer.serverRepository.observeServers().collectAsStateWithLifecycle(emptyList()).value
            val currentServerUrl = servers.firstOrNull { it.id == serverId }
                ?.baseUrl
                ?.let(::safeServerBaseUrlForDisplay)
                ?: serverId
            val viewModel: ProviderSettingsViewModel = viewModel(
                key = "provider-settings-$serverId-${directory.orEmpty()}-${workspace.orEmpty()}",
                factory = appContainer.viewModelProvider.providerSettingsFactory(serverId, directory, workspace),
            )
            ProviderSettingsScreen(
                currentServerUrl = currentServerUrl,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenCustomProvider = { providerId ->
                    navController.navigate(
                        OpenCodeRoutes.customProviderForm(serverId, directory, workspace, providerId),
                    )
                },
            )
        }

        composable(
            route = OpenCodeRoutes.customProviderFormPattern,
            arguments = customProviderArguments(),
        ) { entry ->
            val serverId = entry.requireStringArg("serverId")
            val providerId = entry.arguments?.getString(OpenCodeRoutes.customProviderIdArg)
            val servers = appContainer.serverRepository.observeServers().collectAsStateWithLifecycle(emptyList()).value
            val currentServerUrl = servers.firstOrNull { it.id == serverId }
                ?.baseUrl
                ?.let(::safeServerBaseUrlForDisplay)
                ?: serverId
            val viewModel: CustomProviderFormViewModel = viewModel(
                key = "custom-provider-$serverId-${providerId.orEmpty()}",
                factory = appContainer.viewModelProvider.customProviderFormFactory(serverId, providerId),
            )
            CustomProviderFormScreen(
                currentServerUrl = currentServerUrl,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(OpenCodeRoutes.modelSettingsPattern) { entry ->
            val serverId = entry.requireStringArg("serverId")
            val servers = appContainer.serverRepository.observeServers().collectAsStateWithLifecycle(emptyList()).value
            val currentServerUrl = servers.firstOrNull { it.id == serverId }
                ?.baseUrl
                ?.let(::safeServerBaseUrlForDisplay)
                ?: serverId
            val viewModel: ModelSettingsViewModel = viewModel(
                key = "model-settings-$serverId",
                factory = appContainer.viewModelProvider.modelSettingsFactory(serverId),
            )
            ModelSettingsScreen(
                currentServerUrl = currentServerUrl,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        }
    }
}

private fun providerScopeArguments() = listOf(
    navArgument(OpenCodeRoutes.providerDirectoryArg) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
    navArgument(OpenCodeRoutes.providerWorkspaceArg) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

private fun customProviderArguments() = providerScopeArguments() + navArgument(OpenCodeRoutes.customProviderIdArg) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

@Composable
private fun GeneralSettingsRoute(
    appContainer: AppContainer,
    currentServerUrl: String?,
    onBack: () -> Unit,
) {
    val colorSchemePreference = appContainer.appSettingsStore.colorSchemePreference
        .collectAsStateWithLifecycle(AppColorSchemePreference.System)
        .value
    val languagePreference = appContainer.appSettingsStore.languagePreference
        .collectAsStateWithLifecycle(AppLanguagePreference.System)
        .value
    val notificationSettings = appContainer.appSettingsStore.notificationSettings
        .collectAsStateWithLifecycle(AppNotificationSettings())
        .value
    val soundSettings = appContainer.appSettingsStore.soundSettings
        .collectAsStateWithLifecycle(AppSoundSettings())
        .value
    val scope = rememberCoroutineScope()
    GeneralSettingsScreen(
        currentServerUrl = currentServerUrl,
        colorSchemePreference = colorSchemePreference,
        languagePreference = languagePreference,
        notificationSettings = notificationSettings,
        soundSettings = soundSettings,
        onColorSchemePreferenceChange = { preference ->
            scope.launch {
                appContainer.appSettingsStore.setColorSchemePreference(preference)
            }
        },
        onLanguagePreferenceChange = { preference ->
            scope.launch {
                appContainer.appSettingsStore.setLanguagePreference(preference)
            }
        },
        onAgentNotificationsEnabledChange = { enabled ->
            scope.launch {
                appContainer.appSettingsStore.setAgentNotificationsEnabled(enabled)
            }
        },
        onPermissionNotificationsEnabledChange = { enabled ->
            scope.launch {
                appContainer.appSettingsStore.setPermissionNotificationsEnabled(enabled)
            }
        },
        onErrorNotificationsEnabledChange = { enabled ->
            scope.launch {
                appContainer.appSettingsStore.setErrorNotificationsEnabled(enabled)
            }
        },
        onAgentSoundSelected = { soundId ->
            scope.launch {
                appContainer.appSettingsStore.setAgentSound(soundId)
                if (OpenCodeSoundCatalog.isNone(soundId)) {
                    appContainer.soundPlayer.stop()
                } else {
                    appContainer.soundPlayer.preview(soundId)
                }
            }
        },
        onPermissionSoundSelected = { soundId ->
            scope.launch {
                appContainer.appSettingsStore.setPermissionSound(soundId)
                if (OpenCodeSoundCatalog.isNone(soundId)) {
                    appContainer.soundPlayer.stop()
                } else {
                    appContainer.soundPlayer.preview(soundId)
                }
            }
        },
        onErrorSoundSelected = { soundId ->
            scope.launch {
                appContainer.appSettingsStore.setErrorSound(soundId)
                if (OpenCodeSoundCatalog.isNone(soundId)) {
                    appContainer.soundPlayer.stop()
                } else {
                    appContainer.soundPlayer.preview(soundId)
                }
            }
        },
        onBack = onBack,
    )
}

private fun androidx.navigation.NavBackStackEntry.requireStringArg(name: String): String =
    requireNotNull(arguments?.getString(name)) { "Missing navigation argument: $name" }

private fun NavBackStackEntry.activeProjectDrawerRoute(): ActiveProjectDrawerRoute? = when (destination.route) {
    OpenCodeRoutes.projectShellPattern -> ActiveProjectDrawerRoute(
        serverId = requireStringArg("serverId"),
        directory = requireStringArg("directory"),
        sessionId = null,
    )
    OpenCodeRoutes.sessionDetailPattern -> ActiveProjectDrawerRoute(
        serverId = requireStringArg("serverId"),
        directory = requireStringArg("directory"),
        sessionId = requireStringArg("sessionId"),
    )
    else -> null
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isSessionDetailInternalNavigation(): Boolean =
    initialState.destination.route == OpenCodeRoutes.sessionDetailPattern &&
        targetState.destination.route == OpenCodeRoutes.sessionDetailPattern

@Composable
private fun SessionDestinationVisibilityEffect(
    entry: NavBackStackEntry,
    viewModel: SessionDetailViewModel,
) {
    DisposableEffect(entry, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onDestinationVisibilityChanged(true)
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> viewModel.onDestinationVisibilityChanged(false)
                else -> Unit
            }
        }
        entry.lifecycle.addObserver(observer)
        viewModel.onDestinationVisibilityChanged(entry.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            entry.lifecycle.removeObserver(observer)
            viewModel.onDestinationVisibilityChanged(false)
        }
    }
}

private const val NavigationAnimationDurationMillis = 300
