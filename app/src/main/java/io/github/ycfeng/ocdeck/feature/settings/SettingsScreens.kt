package io.github.ycfeng.ocdeck.feature.settings

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ycfeng.ocdeck.BuildConfig
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.sound.OpenCodeSoundCatalog
import io.github.ycfeng.ocdeck.core.sound.OpenCodeSoundOption
import io.github.ycfeng.ocdeck.core.util.toSafeExternalHttpUrlOrNull
import io.github.ycfeng.ocdeck.data.settings.AppColorSchemePreference
import io.github.ycfeng.ocdeck.data.settings.AppLanguagePreference
import io.github.ycfeng.ocdeck.data.settings.AppNotificationSettings
import io.github.ycfeng.ocdeck.data.settings.AppSoundSettings
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProviderSource
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProviderSummary
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthMethod
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthMethodType
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthPrompt
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthSelectOption
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthSelectPrompt
import io.github.ycfeng.ocdeck.domain.model.ProviderAuthTextPrompt
import io.github.ycfeng.ocdeck.domain.model.ProviderOAuthMode
import io.github.ycfeng.ocdeck.ui.component.LocalizedModalBottomSheet
import io.github.ycfeng.ocdeck.ui.component.OpenCodeCard
import io.github.ycfeng.ocdeck.ui.component.OpenCodeConfirmDialog
import io.github.ycfeng.ocdeck.ui.component.OpenCodePrimaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSecondaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSectionLabel
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSwitch
import io.github.ycfeng.ocdeck.ui.component.openCodeOutlinedTextFieldColors
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import io.github.ycfeng.ocdeck.ui.text.asString

@Composable
fun SettingsScreen(
    currentServerUrl: String?,
    onBack: () -> Unit,
    onOpenGeneralSettings: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenBackgroundRunSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenProviders: (() -> Unit)?,
    onOpenModels: (() -> Unit)?,
) {
    val hasOpenServer = currentServerUrl != null

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsHeader(
                    title = stringResource(R.string.settings_title),
                    subtitle = currentServerUrl?.let { stringResource(R.string.settings_current_server, it) },
                    onBack = onBack,
                )
            }
            item {
                SettingsActionCard(
                    title = stringResource(R.string.settings_general_title),
                    subtitle = stringResource(R.string.settings_general_subtitle),
                    action = stringResource(R.string.action_open),
                    onClick = onOpenGeneralSettings,
                )
            }
            item {
                SettingsActionCard(
                    title = stringResource(R.string.settings_background_title),
                    subtitle = stringResource(R.string.settings_background_subtitle),
                    action = stringResource(R.string.action_check),
                    onClick = onOpenBackgroundRunSettings,
                )
            }
            item {
                SettingsCard(
                    title = stringResource(R.string.settings_shortcuts_title),
                    subtitle = stringResource(R.string.settings_shortcuts_subtitle),
                    meta = "Ctrl+, / Ctrl+O / Ctrl+L",
                )
            }
            item {
                SettingsActionCard(
                    title = stringResource(R.string.settings_servers_title),
                    subtitle = stringResource(R.string.settings_servers_subtitle),
                    action = stringResource(R.string.action_open),
                    onClick = onOpenServers,
                )
            }
            if (hasOpenServer && onOpenProviders != null) item {
                SettingsActionCard(
                    title = stringResource(R.string.settings_providers_title),
                    subtitle = stringResource(R.string.settings_providers_subtitle),
                    action = stringResource(R.string.action_manage),
                    onClick = onOpenProviders,
                )
            }
            if (hasOpenServer && onOpenModels != null) item {
                SettingsActionCard(
                    title = stringResource(R.string.settings_models_title),
                    subtitle = stringResource(R.string.settings_models_subtitle),
                    action = stringResource(R.string.action_manage),
                    onClick = onOpenModels,
                )
            }
            item {
                SettingsActionCard(
                    title = stringResource(R.string.settings_about_title),
                    subtitle = stringResource(R.string.settings_about_subtitle, BuildConfig.VERSION_NAME),
                    action = stringResource(R.string.action_open),
                    onClick = onOpenAbout,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    currentServerUrl: String?,
    colorSchemePreference: AppColorSchemePreference,
    languagePreference: AppLanguagePreference,
    notificationSettings: AppNotificationSettings,
    soundSettings: AppSoundSettings,
    onColorSchemePreferenceChange: (AppColorSchemePreference) -> Unit,
    onLanguagePreferenceChange: (AppLanguagePreference) -> Unit,
    onAgentNotificationsEnabledChange: (Boolean) -> Unit,
    onPermissionNotificationsEnabledChange: (Boolean) -> Unit,
    onErrorNotificationsEnabledChange: (Boolean) -> Unit,
    onAgentSoundSelected: (String) -> Unit,
    onPermissionSoundSelected: (String) -> Unit,
    onErrorSoundSelected: (String) -> Unit,
    onBack: () -> Unit,
) {
    var showColorSchemeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var soundSheetTarget by remember { mutableStateOf<SoundSettingTarget?>(null) }

    Scaffold(containerColor = OpenCodePalette.Canvas) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsHeader(
                    title = stringResource(R.string.settings_general_title),
                    subtitle = currentServerUrl?.let {
                        stringResource(R.string.settings_general_header_subtitle_with_server, it)
                    } ?: stringResource(R.string.settings_general_header_subtitle),
                    onBack = onBack,
                )
            }
            item { OpenCodeSectionLabel(stringResource(R.string.settings_general_section_display)) }
            item {
                SettingsActionCard(
                    title = stringResource(R.string.settings_general_language_title),
                    subtitle = stringResource(
                        R.string.settings_general_language_subtitle,
                        languagePreference.label(),
                    ),
                    action = languagePreference.label(),
                    onClick = { showLanguageSheet = true },
                )
            }
            item {
                SettingsActionCard(
                    title = stringResource(R.string.settings_general_color_scheme_title),
                    subtitle = stringResource(
                        R.string.settings_general_color_scheme_subtitle,
                        colorSchemePreference.label(),
                    ),
                    action = colorSchemePreference.label(),
                    onClick = { showColorSchemeSheet = true },
                )
            }
            item { OpenCodeSectionLabel(stringResource(R.string.settings_general_section_notifications)) }
            item {
                NotificationSettingCard(
                    title = stringResource(R.string.settings_notifications_agent_title),
                    subtitle = stringResource(R.string.settings_notifications_agent_description),
                    checked = notificationSettings.agentEnabled,
                    onCheckedChange = onAgentNotificationsEnabledChange,
                )
            }
            item {
                NotificationSettingCard(
                    title = stringResource(R.string.settings_notifications_permissions_title),
                    subtitle = stringResource(R.string.settings_notifications_permissions_description),
                    checked = notificationSettings.permissionsEnabled,
                    onCheckedChange = onPermissionNotificationsEnabledChange,
                )
            }
            item {
                NotificationSettingCard(
                    title = stringResource(R.string.settings_notifications_errors_title),
                    subtitle = stringResource(R.string.settings_notifications_errors_description),
                    checked = notificationSettings.errorsEnabled,
                    onCheckedChange = onErrorNotificationsEnabledChange,
                )
            }
            item { OpenCodeSectionLabel(stringResource(R.string.settings_general_section_sounds)) }
            item {
                val option = soundSettings.optionFor(SoundSettingTarget.Agent)
                SettingsActionCard(
                    title = stringResource(R.string.settings_sounds_agent_title),
                    subtitle = stringResource(R.string.settings_sounds_agent_description),
                    action = option.label(),
                    onClick = { soundSheetTarget = SoundSettingTarget.Agent },
                )
            }
            item {
                val option = soundSettings.optionFor(SoundSettingTarget.Permissions)
                SettingsActionCard(
                    title = stringResource(R.string.settings_sounds_permissions_title),
                    subtitle = stringResource(R.string.settings_sounds_permissions_description),
                    action = option.label(),
                    onClick = { soundSheetTarget = SoundSettingTarget.Permissions },
                )
            }
            item {
                val option = soundSettings.optionFor(SoundSettingTarget.Errors)
                SettingsActionCard(
                    title = stringResource(R.string.settings_sounds_errors_title),
                    subtitle = stringResource(R.string.settings_sounds_errors_description),
                    action = option.label(),
                    onClick = { soundSheetTarget = SoundSettingTarget.Errors },
                )
            }
        }
    }

    if (showColorSchemeSheet) {
        LocalizedModalBottomSheet(
            onDismissRequest = { showColorSchemeSheet = false },
            containerColor = OpenCodePalette.Panel,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_general_color_scheme_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = OpenCodePalette.Text,
                )
                Text(
                    text = stringResource(R.string.settings_general_color_scheme_sheet_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenCodePalette.MutedText,
                )
                HorizontalDivider(color = OpenCodePalette.Border)
                ColorSchemeOptions.forEach { option ->
                    ColorSchemeOptionRow(
                        preference = option,
                        selected = option == colorSchemePreference,
                        onClick = {
                            onColorSchemePreferenceChange(option)
                            showColorSchemeSheet = false
                        },
                    )
                }
            }
        }
    }

    if (showLanguageSheet) {
        LocalizedModalBottomSheet(
            onDismissRequest = { showLanguageSheet = false },
            containerColor = OpenCodePalette.Panel,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_general_language_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = OpenCodePalette.Text,
                )
                Text(
                    text = stringResource(R.string.settings_general_language_sheet_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenCodePalette.MutedText,
                )
                HorizontalDivider(color = OpenCodePalette.Border)
                LanguageOptions.forEach { option ->
                    LanguageOptionRow(
                        preference = option,
                        selected = option == languagePreference,
                        onClick = {
                            onLanguagePreferenceChange(option)
                            showLanguageSheet = false
                        },
                    )
                }
            }
        }
    }

    soundSheetTarget?.let { target ->
        LocalizedModalBottomSheet(
            onDismissRequest = { soundSheetTarget = null },
            containerColor = OpenCodePalette.Panel,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(target.titleResId),
                            style = MaterialTheme.typography.titleLarge,
                            color = OpenCodePalette.Text,
                        )
                        Text(
                            text = stringResource(R.string.settings_sounds_sheet_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenCodePalette.MutedText,
                        )
                    }
                    OpenCodeSecondaryButton(
                        text = stringResource(R.string.settings_sounds_restore_default),
                        onClick = {
                            when (target) {
                                SoundSettingTarget.Agent -> onAgentSoundSelected(target.defaultSoundId)
                                SoundSettingTarget.Permissions -> onPermissionSoundSelected(target.defaultSoundId)
                                SoundSettingTarget.Errors -> onErrorSoundSelected(target.defaultSoundId)
                            }
                        },
                    )
                }
                HorizontalDivider(color = OpenCodePalette.Border)
                val selectedOption = soundSettings.optionFor(target)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(OpenCodeSoundCatalog.selectableOptions, key = { it.id }) { option ->
                        SettingsOptionRow(
                            title = option.label(),
                            selected = option.id == selectedOption.id,
                            onClick = {
                                when (target) {
                                    SoundSettingTarget.Agent -> onAgentSoundSelected(option.id)
                                    SoundSettingTarget.Permissions -> onPermissionSoundSelected(option.id)
                                    SoundSettingTarget.Errors -> onErrorSoundSelected(option.id)
                                }
                                if (OpenCodeSoundCatalog.isNone(option.id)) {
                                    soundSheetTarget = null
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BackgroundRunSettingsScreen(
    currentServerUrl: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsAllowed by remember { mutableStateOf(isNotificationPermissionGranted(context)) }
    var ignoringBatteryOptimizations by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val notificationAllowedMessage = stringResource(R.string.settings_background_notification_allowed_toast)
    val notificationDeniedMessage = stringResource(R.string.settings_background_notification_denied_toast)
    val notificationNoRuntimePermissionMessage = stringResource(R.string.settings_background_notification_no_runtime_permission_toast)

    fun refreshStatus() {
        notificationsAllowed = isNotificationPermissionGranted(context)
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = granted || isNotificationPermissionGranted(context)
        Toast.makeText(
            context,
            if (notificationsAllowed) notificationAllowedMessage else notificationDeniedMessage,
            Toast.LENGTH_SHORT,
        ).show()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(containerColor = OpenCodePalette.Canvas) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsHeader(
                    title = stringResource(R.string.settings_background_title),
                    subtitle = currentServerUrl?.let {
                        stringResource(R.string.settings_background_header_subtitle_with_server, it)
                    } ?: stringResource(R.string.settings_background_header_subtitle),
                    onBack = onBack,
                )
            }
            item {
                OpenCodeCard(modifier = Modifier.fillMaxWidth(), color = OpenCodePalette.AccentSoft) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.settings_background_card_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_background_card_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenCodePalette.MutedText,
                        )
                    }
                }
            }
            item { OpenCodeSectionLabel(stringResource(R.string.settings_background_section_recommended)) }
            item {
                BackgroundRequirementCard(
                    title = stringResource(R.string.settings_background_notifications_title),
                    status = if (notificationsAllowed) {
                        stringResource(R.string.status_allowed)
                    } else {
                        stringResource(R.string.status_pending)
                    },
                    state = if (notificationsAllowed) RequirementState.Complete else RequirementState.Incomplete,
                    description = stringResource(R.string.settings_background_notifications_description),
                    warning = if (notificationsAllowed) null else stringResource(R.string.settings_background_notifications_warning),
                    actionText = when {
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> stringResource(R.string.settings_background_notifications_auto_allowed)
                        notificationsAllowed -> stringResource(R.string.status_completed)
                        else -> stringResource(R.string.settings_background_notifications_grant)
                    },
                    actionEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed,
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            Toast.makeText(context, notificationNoRuntimePermissionMessage, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            item {
                BackgroundRequirementCard(
                    title = stringResource(R.string.settings_background_battery_title),
                    status = if (ignoringBatteryOptimizations) {
                        stringResource(R.string.status_ignored)
                    } else {
                        stringResource(R.string.status_pending)
                    },
                    state = if (ignoringBatteryOptimizations) RequirementState.Complete else RequirementState.Incomplete,
                    description = stringResource(R.string.settings_background_battery_description),
                    warning = if (ignoringBatteryOptimizations) null else stringResource(R.string.settings_background_battery_warning),
                    actionText = if (ignoringBatteryOptimizations) {
                        stringResource(R.string.status_completed)
                    } else {
                        stringResource(R.string.settings_background_battery_request)
                    },
                    actionEnabled = !ignoringBatteryOptimizations,
                    onAction = { requestIgnoreBatteryOptimizations(context) },
                )
            }
            item {
                BackgroundRequirementCard(
                    title = stringResource(R.string.settings_background_recent_tasks_title),
                    status = stringResource(R.string.status_manual_confirm),
                    state = RequirementState.Manual,
                    description = stringResource(R.string.settings_background_recent_tasks_description),
                    warning = stringResource(R.string.settings_background_recent_tasks_warning),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpenCodeSecondaryButton(
                        text = stringResource(R.string.action_recheck),
                        onClick = { refreshStatus() },
                        modifier = Modifier.weight(1f),
                    )
                    OpenCodePrimaryButton(
                        text = stringResource(R.string.action_done),
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    currentServerUrl: String,
    viewModel: ProviderSettingsViewModel,
    onBack: () -> Unit,
    onOpenCustomProvider: (String?) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var disconnectProvider by remember { mutableStateOf<OpenCodeProviderSummary?>(null) }
    val connectedProviders = state.visibleProviders.filter(OpenCodeProviderSummary::isConnected)
    val availableProviders = state.visibleProviders.filterNot(OpenCodeProviderSummary::isConnected)
    val mutationInProgress = state.mutatingProviderId != null || state.authentication?.isBusy == true

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    state.authentication?.let { authentication ->
        ProviderAuthenticationSheet(
            authentication = authentication,
            onDismiss = viewModel::dismissAuthentication,
            onSelectMethod = viewModel::selectAuthenticationMethod,
            onSubmitApi = viewModel::submitApiAuthentication,
            onAuthorizeOAuth = viewModel::authorizeOAuth,
            onCompleteOAuth = viewModel::completeOAuth,
            onCancelOAuth = viewModel::cancelOAuth,
            onBrowserOpenFailure = viewModel::reportBrowserOpenFailure,
        )
    }

    disconnectProvider?.let { provider ->
        OpenCodeConfirmDialog(
            title = stringResource(R.string.settings_providers_disconnect_title),
            message = stringResource(R.string.settings_providers_disconnect_message, provider.name),
            confirmText = stringResource(R.string.action_disconnect),
            onDismiss = { disconnectProvider = null },
            onConfirm = {
                disconnectProvider = null
                viewModel.disconnect(provider.id)
            },
        )
    }

    if (state.cleartextConfirmationProviderId != null) {
        OpenCodeConfirmDialog(
            title = stringResource(R.string.settings_providers_cleartext_title),
            message = stringResource(R.string.settings_providers_cleartext_message),
            confirmText = stringResource(R.string.settings_providers_cleartext_confirm),
            isConfirming = mutationInProgress,
            onDismiss = viewModel::dismissCleartextConfirmation,
            onConfirm = viewModel::confirmCleartextConnection,
        )
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsHeader(
                    title = stringResource(R.string.settings_providers_title),
                    subtitle = stringResource(R.string.settings_providers_header_subtitle, currentServerUrl),
                    onBack = onBack,
                )
            }
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    label = { Text(stringResource(R.string.settings_providers_search)) },
                    singleLine = true,
                    colors = openCodeOutlinedTextFieldColors(),
                )
            }
            item {
                OpenCodePrimaryButton(
                    text = if (state.isLoading) {
                        stringResource(R.string.action_refreshing)
                    } else {
                        stringResource(R.string.action_refresh)
                    },
                    enabled = !state.isLoading && !mutationInProgress,
                    onClick = viewModel::refresh,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.notice?.let { notice ->
                item {
                    Text(
                        text = notice.asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.Accent,
                    )
                }
            }
            state.error?.let { error ->
                item {
                    Text(
                        text = error.asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (state.isLoading && state.providers.isEmpty()) {
                item { Text(stringResource(R.string.settings_providers_loading)) }
            }
            if (!state.isLoading && state.providers.isEmpty()) {
                item { Text(stringResource(R.string.settings_providers_empty)) }
            } else if (!state.isLoading && state.visibleProviders.isEmpty()) {
                item { Text(stringResource(R.string.settings_providers_no_matches)) }
            }
            if (connectedProviders.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.settings_providers_connected_section)) }
                items(connectedProviders, key = { "connected/${it.id}" }) { provider ->
                    ProviderCard(
                        provider = provider,
                        isMutating = state.mutatingProviderId == provider.id,
                        actionsEnabled = !mutationInProgress && state.authentication == null,
                        onConnect = { viewModel.beginAuthentication(provider.id) },
                        onDisconnect = { disconnectProvider = provider },
                        onEditCustom = { onOpenCustomProvider(provider.id) },
                    )
                }
            }
            if (availableProviders.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.settings_providers_available_section)) }
                items(availableProviders, key = { "available/${it.id}" }) { provider ->
                    ProviderCard(
                        provider = provider,
                        isMutating = state.mutatingProviderId == provider.id,
                        actionsEnabled = !mutationInProgress && state.authentication == null,
                        onConnect = { viewModel.beginAuthentication(provider.id) },
                        onDisconnect = { disconnectProvider = provider },
                        onEditCustom = { onOpenCustomProvider(provider.id) },
                    )
                }
            }
            item {
                SettingsActionCard(
                    title = stringResource(R.string.settings_custom_provider_title),
                    subtitle = stringResource(R.string.settings_custom_provider_subtitle),
                    action = stringResource(R.string.action_create),
                    onClick = { onOpenCustomProvider(null) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderAuthenticationSheet(
    authentication: ProviderAuthenticationUiState,
    onDismiss: () -> Unit,
    onSelectMethod: (Int) -> Unit,
    onSubmitApi: (String, Map<String, String>) -> Unit,
    onAuthorizeOAuth: (Map<String, String>) -> Unit,
    onCompleteOAuth: (String?) -> Unit,
    onCancelOAuth: () -> Unit,
    onBrowserOpenFailure: () -> Unit,
) {
    val context = LocalContext.current
    val selectedMethod = authentication.selectedMethod
    var apiKey by remember(authentication.providerId, selectedMethod?.wireIndex) { mutableStateOf("") }
    var inputs by remember(authentication.providerId, selectedMethod?.wireIndex) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var oauthCode by remember(authentication.providerId, selectedMethod?.wireIndex) { mutableStateOf("") }
    val visiblePrompts = selectedMethod?.visiblePrompts(inputs).orEmpty()

    LocalizedModalBottomSheet(
        onDismissRequest = if (authentication.isCompletingOAuth) onCancelOAuth else onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_providers_auth_sheet_title, authentication.providerName),
                style = MaterialTheme.typography.titleLarge,
            )

            if (authentication.isLoadingMethods) {
                Text(
                    text = stringResource(R.string.settings_providers_auth_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenCodePalette.MutedText,
                )
                OpenCodeSecondaryButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
                return@Column
            }

            if (authentication.methods.size > 1) {
                Text(
                    text = stringResource(R.string.settings_providers_auth_choose_method),
                    style = MaterialTheme.typography.titleMedium,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    authentication.methods.forEach { method ->
                        ProviderAuthMethodRow(
                            method = method,
                            selected = method.wireIndex == authentication.selectedMethodWireIndex,
                            enabled = !authentication.isBusy,
                            onClick = { onSelectMethod(method.wireIndex) },
                        )
                    }
                }
            }

            if (selectedMethod == null) {
                OpenCodeSecondaryButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
                return@Column
            }

            selectedMethod.label?.takeIf(String::isNotBlank)?.let { label ->
                Text(label, style = MaterialTheme.typography.titleMedium, color = OpenCodePalette.Text)
            }

            visiblePrompts.forEach { prompt ->
                ProviderAuthPromptField(
                    prompt = prompt,
                    value = inputs[prompt.key].orEmpty(),
                    enabled = !authentication.isBusy,
                    onValueChange = { value -> inputs = inputs + (prompt.key to value) },
                )
            }

            when (selectedMethod.type) {
                ProviderAuthMethodType.Api -> {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.settings_providers_api_key_label)) },
                        enabled = !authentication.isBusy,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = openCodeOutlinedTextFieldColors(),
                    )
                    Text(
                        text = stringResource(R.string.settings_providers_api_key_security_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MutedText,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OpenCodeSecondaryButton(
                            text = stringResource(R.string.action_cancel),
                            enabled = !authentication.isBusy,
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                        OpenCodePrimaryButton(
                            text = stringResource(R.string.action_connect),
                            enabled = apiKey.isNotBlank() && !authentication.isBusy,
                            onClick = { onSubmitApi(apiKey, inputs) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                ProviderAuthMethodType.OAuth -> {
                    val authorization = authentication.authorization
                    if (authorization == null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OpenCodeSecondaryButton(
                                text = stringResource(R.string.action_cancel),
                                enabled = !authentication.isAuthorizing,
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f),
                            )
                            OpenCodePrimaryButton(
                                text = if (authentication.isAuthorizing) {
                                    stringResource(R.string.settings_providers_oauth_authorizing)
                                } else {
                                    stringResource(R.string.settings_providers_oauth_authorize)
                                },
                                enabled = !authentication.isAuthorizing,
                                onClick = { onAuthorizeOAuth(inputs) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        if (authorization.instructions.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.settings_providers_oauth_instructions),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = authorization.instructions,
                                style = MaterialTheme.typography.bodyMedium,
                                color = OpenCodePalette.MutedText,
                            )
                        }
                        if (authorization.usesLoopbackUrl) {
                            Text(
                                text = stringResource(R.string.settings_providers_oauth_loopback_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        when (authorization.mode) {
                            ProviderOAuthMode.Auto -> {
                                if (authentication.isCompletingOAuth) {
                                    Text(
                                        text = stringResource(R.string.settings_providers_oauth_waiting),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OpenCodePalette.MutedText,
                                    )
                                    OpenCodeSecondaryButton(
                                        text = stringResource(R.string.action_cancel),
                                        onClick = onCancelOAuth,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    OpenCodePrimaryButton(
                                        text = stringResource(R.string.settings_providers_oauth_open_and_continue),
                                        onClick = {
                                            if (openProviderOAuthUrl(context, authorization.url)) {
                                                onCompleteOAuth(null)
                                            } else {
                                                onBrowserOpenFailure()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OpenCodeSecondaryButton(
                                        text = stringResource(R.string.action_cancel),
                                        onClick = onDismiss,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            ProviderOAuthMode.Code -> {
                                OpenCodeSecondaryButton(
                                    text = stringResource(R.string.settings_providers_oauth_open_browser),
                                    enabled = !authentication.isCompletingOAuth,
                                    onClick = {
                                        if (!openProviderOAuthUrl(context, authorization.url)) {
                                            onBrowserOpenFailure()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth(),
                                    value = oauthCode,
                                    onValueChange = { oauthCode = it },
                                    label = { Text(stringResource(R.string.settings_providers_oauth_code_label)) },
                                    enabled = !authentication.isCompletingOAuth,
                                    singleLine = true,
                                    colors = openCodeOutlinedTextFieldColors(),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OpenCodeSecondaryButton(
                                        text = stringResource(R.string.action_cancel),
                                        onClick = if (authentication.isCompletingOAuth) onCancelOAuth else onDismiss,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OpenCodePrimaryButton(
                                        text = if (authentication.isCompletingOAuth) {
                                            stringResource(R.string.action_processing)
                                        } else {
                                            stringResource(R.string.settings_providers_oauth_complete)
                                        },
                                        enabled = oauthCode.isNotBlank() && !authentication.isCompletingOAuth,
                                        onClick = { onCompleteOAuth(oauthCode) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderAuthMethodRow(
    method: ProviderAuthMethod,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val fallbackLabel = when (method.type) {
        ProviderAuthMethodType.Api -> stringResource(R.string.settings_providers_auth_method_api)
        ProviderAuthMethodType.OAuth -> stringResource(R.string.settings_providers_auth_method_oauth)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) OpenCodePalette.PanelMuted else OpenCodePalette.Panel,
        border = BorderStroke(1.dp, if (selected) OpenCodePalette.SelectionBorder else OpenCodePalette.Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = method.label?.takeIf(String::isNotBlank) ?: fallbackLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenCodePalette.Text,
            )
            if (selected) {
                Text(
                    text = stringResource(R.string.settings_option_selected),
                    style = MaterialTheme.typography.labelSmall,
                    color = OpenCodePalette.Accent,
                )
            }
        }
    }
}

@Composable
private fun ProviderAuthPromptField(
    prompt: ProviderAuthPrompt,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    when (prompt) {
        is ProviderAuthTextPrompt -> OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            label = { Text(prompt.message) },
            placeholder = prompt.placeholder?.let { placeholder -> ({ Text(placeholder) }) },
            enabled = enabled,
            singleLine = true,
            colors = openCodeOutlinedTextFieldColors(),
        )

        is ProviderAuthSelectPrompt -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(prompt.message, style = MaterialTheme.typography.labelLarge, color = OpenCodePalette.Text)
            prompt.options.forEach { option ->
                ProviderAuthSelectOptionRow(
                    option = option,
                    selected = option.value == value,
                    enabled = enabled,
                    onClick = { onValueChange(option.value) },
                )
            }
        }
    }
}

@Composable
private fun ProviderAuthSelectOptionRow(
    option: ProviderAuthSelectOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) OpenCodePalette.PanelMuted else OpenCodePalette.Panel,
        border = BorderStroke(1.dp, if (selected) OpenCodePalette.SelectionBorder else OpenCodePalette.Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(option.label, style = MaterialTheme.typography.bodyMedium, color = OpenCodePalette.Text)
                option.hint?.takeIf(String::isNotBlank)?.let { hint ->
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = OpenCodePalette.MutedText)
                }
            }
            if (selected) {
                Text(
                    text = stringResource(R.string.settings_option_selected),
                    style = MaterialTheme.typography.labelSmall,
                    color = OpenCodePalette.Accent,
                )
            }
        }
    }
}

@Composable
fun CustomProviderFormScreen(
    currentServerUrl: String,
    viewModel: CustomProviderFormViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDisableConfirmation by remember { mutableStateOf(false) }

    if (state.cleartextConfirmationPending) {
        OpenCodeConfirmDialog(
            title = stringResource(R.string.settings_custom_provider_cleartext_title),
            message = stringResource(R.string.settings_custom_provider_cleartext_message),
            confirmText = stringResource(R.string.settings_providers_cleartext_confirm),
            onDismiss = viewModel::dismissCleartextConfirmation,
            onConfirm = viewModel::confirmCleartextSave,
        )
    }

    if (showDisableConfirmation) {
        OpenCodeConfirmDialog(
            title = stringResource(R.string.settings_custom_provider_disable_title),
            message = stringResource(R.string.settings_custom_provider_disable_message),
            confirmText = stringResource(R.string.settings_custom_provider_disable_confirm),
            isConfirming = state.isDisabling,
            onDismiss = { showDisableConfirmation = false },
            onConfirm = {
                showDisableConfirmation = false
                viewModel.disable()
            },
        )
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsHeader(
                    title = stringResource(
                        if (state.isEditing) {
                            R.string.settings_custom_provider_edit_title
                        } else {
                            R.string.settings_custom_provider_create_title
                        },
                    ),
                    subtitle = stringResource(R.string.settings_custom_provider_form_subtitle, currentServerUrl),
                    onBack = onBack,
                )
            }
            state.notice?.let { notice ->
                item {
                    Text(
                        text = notice.asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.Accent,
                    )
                }
            }
            state.error?.let { error ->
                item {
                    Text(
                        text = error.asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!state.isFormReady) {
                if (state.isLoading) {
                    item { Text(stringResource(R.string.settings_custom_provider_loading)) }
                }
                item {
                    OpenCodeSecondaryButton(
                        text = stringResource(R.string.action_retry),
                        enabled = state.error != null,
                        onClick = viewModel::retryLoad,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                if (state.isEditing) {
                    item {
                        Text(
                            text = stringResource(
                                if (state.isDisabled) {
                                    R.string.settings_custom_provider_status_disabled
                                } else {
                                    R.string.settings_custom_provider_status_enabled
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.isDisabled) MaterialTheme.colorScheme.error else OpenCodePalette.Accent,
                        )
                    }
                }
                item {
                    FormField(
                        label = stringResource(R.string.settings_custom_provider_id),
                        value = state.providerId,
                        onValueChange = viewModel::updateProviderId,
                        placeholder = stringResource(R.string.settings_custom_provider_id_placeholder),
                        enabled = !state.isEditing && !state.isInteractionLocked,
                    )
                }
                item {
                    FormField(
                        label = stringResource(R.string.settings_custom_provider_display_name),
                        value = state.displayName,
                        onValueChange = viewModel::updateDisplayName,
                        placeholder = stringResource(R.string.settings_custom_provider_display_name_placeholder),
                        enabled = !state.isInteractionLocked,
                    )
                }
                item {
                    FormField(
                        label = stringResource(R.string.settings_custom_provider_base_url),
                        value = state.baseUrl,
                        onValueChange = viewModel::updateBaseUrl,
                        placeholder = stringResource(R.string.settings_custom_provider_base_url_placeholder),
                        enabled = !state.isInteractionLocked,
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.apiKey,
                        onValueChange = viewModel::updateApiKey,
                        label = { Text(stringResource(R.string.settings_custom_provider_api_key_optional)) },
                        enabled = !state.isInteractionLocked,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = openCodeOutlinedTextFieldColors(),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.settings_custom_provider_api_key_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MutedText,
                    )
                }
                item { SectionTitle(stringResource(R.string.settings_custom_provider_models_title)) }
                item {
                    Text(
                        text = stringResource(R.string.settings_custom_provider_existing_fields_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MutedText,
                    )
                }
                items(state.models, key = { "model/${it.rowId}" }) { model ->
                    CustomProviderModelRow(
                        model = model,
                        enabled = !state.isInteractionLocked,
                        onModelIdChange = { viewModel.updateModelId(model.rowId, it) },
                        onNameChange = { viewModel.updateModelName(model.rowId, it) },
                        onRemove = { viewModel.removeModel(model.rowId) },
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CustomProviderCapacityText(
                            currentCount = state.modelCount,
                            maxCount = state.maxModels,
                            countText = R.string.settings_custom_provider_model_count,
                            limitReachedText = R.string.settings_custom_provider_model_limit_reached,
                            limitExceededText = R.string.settings_custom_provider_model_limit_exceeded,
                        )
                        OpenCodeSecondaryButton(
                            text = stringResource(R.string.settings_custom_provider_add_model),
                            enabled = !state.isInteractionLocked && state.canAddModel,
                            onClick = viewModel::addModel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item { SectionTitle(stringResource(R.string.settings_custom_provider_headers_optional)) }
                item {
                    Text(
                        text = stringResource(R.string.settings_custom_provider_headers_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MutedText,
                    )
                }
                items(state.headers, key = { "header/${it.rowId}" }) { header ->
                    CustomProviderHeaderRow(
                        header = header,
                        enabled = !state.isInteractionLocked,
                        onNameChange = { viewModel.updateHeaderName(header.rowId, it) },
                        onValueChange = { viewModel.updateHeaderValue(header.rowId, it) },
                        onRemove = { viewModel.removeHeader(header.rowId) },
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CustomProviderCapacityText(
                            currentCount = state.headerCount,
                            maxCount = state.maxHeaders,
                            countText = R.string.settings_custom_provider_header_count,
                            limitReachedText = R.string.settings_custom_provider_header_limit_reached,
                            limitExceededText = R.string.settings_custom_provider_header_limit_exceeded,
                        )
                        OpenCodeSecondaryButton(
                            text = stringResource(R.string.settings_custom_provider_add_header),
                            enabled = !state.isInteractionLocked && state.canAddHeader,
                            onClick = viewModel::addHeader,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.settings_custom_provider_transaction_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MutedText,
                    )
                }
                item {
                    OpenCodePrimaryButton(
                        text = if (state.isSaving) {
                            stringResource(R.string.action_saving)
                        } else {
                            stringResource(R.string.action_save)
                        },
                        enabled = !state.isInteractionLocked,
                        onClick = viewModel::save,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.isEditing) {
                    item {
                        OpenCodeSecondaryButton(
                            text = if (state.isDisabling) {
                                stringResource(R.string.action_processing)
                            } else {
                                stringResource(R.string.settings_custom_provider_disable_action)
                            },
                            enabled = !state.isInteractionLocked,
                            onClick = { showDisableConfirmation = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomProviderCapacityText(
    currentCount: Int,
    maxCount: Int,
    @StringRes countText: Int,
    @StringRes limitReachedText: Int,
    @StringRes limitExceededText: Int,
) {
    val text = stringResource(
        when {
            currentCount > maxCount -> limitExceededText
            currentCount == maxCount -> limitReachedText
            else -> countText
        },
        currentCount,
        maxCount,
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (currentCount > maxCount) MaterialTheme.colorScheme.error else OpenCodePalette.MutedText,
    )
}

@Composable
private fun CustomProviderModelRow(
    model: CustomProviderModelRowState,
    enabled: Boolean,
    onModelIdChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    OpenCodeCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FormField(
                label = stringResource(R.string.settings_custom_provider_model_id),
                value = model.modelId,
                onValueChange = onModelIdChange,
                placeholder = stringResource(R.string.settings_custom_provider_model_id_placeholder),
                enabled = enabled && !model.isPersisted,
            )
            FormField(
                label = stringResource(R.string.settings_custom_provider_model_name),
                value = model.name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.settings_custom_provider_model_name_placeholder),
                enabled = enabled,
            )
            if (model.isPersisted) {
                Text(
                    text = stringResource(R.string.settings_custom_provider_persisted_model_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenCodePalette.MutedText,
                )
            } else {
                OpenCodeSecondaryButton(
                    text = stringResource(R.string.settings_custom_provider_remove_model),
                    enabled = enabled,
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CustomProviderHeaderRow(
    header: CustomProviderHeaderRowState,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    OpenCodeCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FormField(
                label = stringResource(R.string.settings_custom_provider_header_name),
                value = header.name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.settings_custom_provider_header_name_placeholder),
                enabled = enabled && !header.isPersisted,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = header.value,
                onValueChange = onValueChange,
                label = { Text(stringResource(R.string.settings_custom_provider_header_value)) },
                placeholder = if (header.isPersisted) {
                    { Text(stringResource(R.string.settings_custom_provider_header_value_retain)) }
                } else {
                    null
                },
                enabled = enabled,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = openCodeOutlinedTextFieldColors(),
            )
            if (header.isPersisted) {
                Text(
                    text = stringResource(R.string.settings_custom_provider_persisted_header_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenCodePalette.MutedText,
                )
            } else {
                OpenCodeSecondaryButton(
                    text = stringResource(R.string.settings_custom_provider_remove_header),
                    enabled = enabled,
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun ModelSettingsScreen(
    currentServerUrl: String,
    viewModel: ModelSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsHeader(
                    title = stringResource(R.string.settings_models_title),
                    subtitle = stringResource(R.string.settings_server_prefix, currentServerUrl),
                    onBack = onBack,
                )
            }
            item {
                OpenCodePrimaryButton(
                    text = if (state.isLoading) stringResource(R.string.action_refreshing) else stringResource(R.string.action_refresh),
                    enabled = !state.isLoading,
                    onClick = viewModel::refresh,
                )
            }
            state.error?.let { error ->
                item { Text(error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.isLoading && state.groups.isEmpty()) {
                item { Text(stringResource(R.string.settings_models_loading), style = MaterialTheme.typography.bodyMedium) }
            }
            if (!state.isLoading && state.groups.isEmpty()) {
                item { Text(stringResource(R.string.settings_models_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            state.groups.forEach { group ->
                item(key = group.providerId) { SectionTitle(group.providerName) }
                items(group.models, key = { "${group.providerId}/${it.modelId}" }) { model ->
                    ModelSettingsModelCard(
                        model = model,
                        isSaving = ModelSettingsModelKey(model.providerId, model.modelId) in state.savingModels,
                        onEnabledChanged = { enabled -> viewModel.setModelEnabled(model, enabled) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSettingsModelCard(
    model: OpenCodeModel,
    isSaving: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = model.isEnabled,
                    enabled = !isSaving,
                    role = Role.Switch,
                    onValueChange = onEnabledChanged,
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = when {
                        isSaving -> stringResource(R.string.status_saving)
                        model.isEnabled -> stringResource(R.string.status_enabled)
                        else -> stringResource(R.string.status_hidden)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(model.modelId, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OpenCodeSwitch(
                checked = model.isEnabled,
                enabled = !isSaving,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
internal fun SettingsHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
) {
    val backContentDescription = stringResource(R.string.a11y_back)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = backContentDescription
                        role = Role.Button
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_opencode_arrow_left),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = OpenCodePalette.IconMuted,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    meta: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = OpenCodePalette.MutedText)
        }
    }
}

@Composable
internal fun SettingsActionCard(
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            OpenCodePrimaryButton(
                text = action,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun NotificationSettingCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    OpenCodeCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = OpenCodePalette.Text)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OpenCodePalette.MutedText)
            }
            OpenCodeSwitch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: OpenCodeProviderSummary,
    isMutating: Boolean,
    actionsEnabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEditCustom: () -> Unit,
) {
    val isExternallyManaged = !provider.isCustomConfigured &&
        (provider.source == OpenCodeProviderSource.Env || provider.source == OpenCodeProviderSource.Config)
    val status = if (provider.isConnected) {
        stringResource(R.string.settings_providers_status_loaded)
    } else {
        stringResource(R.string.settings_providers_status_available)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(
                        R.string.settings_providers_status_details,
                        status,
                        provider.source.label(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenCodePalette.Accent,
                )
                Text(
                    text = stringResource(R.string.settings_providers_model_count, provider.modelCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (provider.name != provider.id) {
                    Text(
                        text = stringResource(R.string.settings_providers_provider_id, provider.id),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (provider.isConnected && isExternallyManaged) {
                    Text(
                        text = if (provider.source == OpenCodeProviderSource.Env) {
                            stringResource(R.string.settings_providers_managed_by_environment)
                        } else {
                            stringResource(R.string.settings_providers_managed_by_config)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MutedText,
                    )
                }
            }
            OpenCodePrimaryButton(
                text = when {
                    isMutating -> stringResource(R.string.action_processing)
                    provider.isCustomConfigured -> stringResource(R.string.action_edit)
                    provider.isConnected -> stringResource(R.string.action_disconnect)
                    else -> stringResource(R.string.action_connect)
                },
                enabled = actionsEnabled && !isExternallyManaged,
                onClick = when {
                    provider.isCustomConfigured -> onEditCustom
                    provider.isConnected -> onDisconnect
                    else -> onConnect
                },
            )
        }
    }
}

@Composable
private fun OpenCodeProviderSource.label(): String = when (this) {
    OpenCodeProviderSource.Env -> stringResource(R.string.settings_providers_source_environment)
    OpenCodeProviderSource.Config -> stringResource(R.string.settings_providers_source_config)
    OpenCodeProviderSource.Custom -> stringResource(R.string.settings_providers_source_catalog_plugin)
    OpenCodeProviderSource.Api -> stringResource(R.string.settings_providers_source_api)
    OpenCodeProviderSource.Unknown -> stringResource(R.string.settings_providers_source_unknown)
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = OpenCodePalette.MutedText)
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        singleLine = true,
        colors = openCodeOutlinedTextFieldColors(),
    )
}

@Composable
private fun ColorSchemeOptionRow(
    preference: AppColorSchemePreference,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SettingsOptionRow(
        title = preference.label(),
        subtitle = preference.description(),
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun LanguageOptionRow(
    preference: AppLanguagePreference,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SettingsOptionRow(
        title = preference.label(),
        subtitle = preference.description(),
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun SettingsOptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) OpenCodePalette.PanelMuted else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, OpenCodePalette.SelectionBorder) else null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = OpenCodePalette.Text)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MutedText,
                    )
                }
            }
        }
    }
}

private val ColorSchemeOptions = listOf(
    AppColorSchemePreference.System,
    AppColorSchemePreference.Light,
    AppColorSchemePreference.Dark,
)

private val LanguageOptions = listOf(
    AppLanguagePreference.System,
    AppLanguagePreference.English,
    AppLanguagePreference.SimplifiedChinese,
)

private enum class SoundSettingTarget(
    @StringRes val titleResId: Int,
    val defaultSoundId: String,
) {
    Agent(R.string.settings_sounds_agent_title, OpenCodeSoundCatalog.DefaultAgentSoundId),
    Permissions(R.string.settings_sounds_permissions_title, OpenCodeSoundCatalog.DefaultPermissionsSoundId),
    Errors(R.string.settings_sounds_errors_title, OpenCodeSoundCatalog.DefaultErrorsSoundId),
}

private fun AppSoundSettings.optionFor(target: SoundSettingTarget): OpenCodeSoundOption = when (target) {
    SoundSettingTarget.Agent -> OpenCodeSoundCatalog.selectedOption(
        agentEnabled,
        agent,
        OpenCodeSoundCatalog.DefaultAgentSoundId,
    )
    SoundSettingTarget.Permissions -> OpenCodeSoundCatalog.selectedOption(
        permissionsEnabled,
        permissions,
        OpenCodeSoundCatalog.DefaultPermissionsSoundId,
    )
    SoundSettingTarget.Errors -> OpenCodeSoundCatalog.selectedOption(
        errorsEnabled,
        errors,
        OpenCodeSoundCatalog.DefaultErrorsSoundId,
    )
}

@Composable
private fun OpenCodeSoundOption.label(): String = labelNumber?.let { number ->
    stringResource(labelResId, number)
} ?: stringResource(labelResId)

@Composable
private fun AppColorSchemePreference.label(): String = when (this) {
    AppColorSchemePreference.System -> stringResource(R.string.settings_color_scheme_system)
    AppColorSchemePreference.Light -> stringResource(R.string.settings_color_scheme_light)
    AppColorSchemePreference.Dark -> stringResource(R.string.settings_color_scheme_dark)
}

@Composable
private fun AppColorSchemePreference.description(): String = when (this) {
    AppColorSchemePreference.System -> stringResource(R.string.settings_color_scheme_system_description)
    AppColorSchemePreference.Light -> stringResource(R.string.settings_color_scheme_light_description)
    AppColorSchemePreference.Dark -> stringResource(R.string.settings_color_scheme_dark_description)
}

@Composable
private fun AppLanguagePreference.label(): String = when (this) {
    AppLanguagePreference.System -> stringResource(R.string.settings_language_system)
    AppLanguagePreference.English -> stringResource(R.string.settings_language_english)
    AppLanguagePreference.SimplifiedChinese -> stringResource(R.string.settings_language_simplified_chinese)
}

@Composable
private fun AppLanguagePreference.description(): String = when (this) {
    AppLanguagePreference.System -> stringResource(R.string.settings_language_system_description)
    AppLanguagePreference.English -> stringResource(R.string.settings_language_english_description)
    AppLanguagePreference.SimplifiedChinese -> stringResource(R.string.settings_language_simplified_chinese_description)
}

private enum class RequirementState {
    Complete,
    Incomplete,
    Manual,
}

@Composable
private fun BackgroundRequirementCard(
    title: String,
    status: String,
    state: RequirementState,
    description: String,
    warning: String? = null,
    actionText: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    OpenCodeCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (state) {
                        RequirementState.Complete -> OpenCodePalette.Accent
                        RequirementState.Incomplete -> OpenCodePalette.Danger
                        RequirementState.Manual -> OpenCodePalette.MutedText
                    },
                )
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = OpenCodePalette.MutedText)
            warning?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == RequirementState.Manual) OpenCodePalette.MutedText else OpenCodePalette.Danger,
                )
            }
            if (actionText != null && onAction != null) {
                OpenCodePrimaryButton(
                    text = actionText,
                    enabled = actionEnabled,
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

private fun isNotificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openProviderOAuthUrl(context: Context, rawUrl: String): Boolean {
    val safeUrl = rawUrl.toSafeExternalHttpUrlOrNull() ?: return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl.value)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    if (isIgnoringBatteryOptimizations(context)) {
        Toast.makeText(context, context.getString(R.string.settings_background_battery_ignored_toast), Toast.LENGTH_SHORT).show()
        return
    }

    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    if (intent.resolveActivity(context.packageManager) == null) {
        Toast.makeText(context, context.getString(R.string.settings_background_battery_request_unsupported_toast), Toast.LENGTH_SHORT).show()
        return
    }

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.settings_background_battery_request_unsupported_toast), Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, context.getString(R.string.settings_background_battery_request_failed_toast), Toast.LENGTH_SHORT).show()
    }
}
