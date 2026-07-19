package io.github.ycfeng.ocdeck

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ActionMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ycfeng.ocdeck.app.OpenCodeApp
import io.github.ycfeng.ocdeck.core.notification.OpenCodeNotificationTarget
import io.github.ycfeng.ocdeck.data.settings.AppColorSchemePreference
import io.github.ycfeng.ocdeck.data.settings.AppLanguagePreference
import io.github.ycfeng.ocdeck.data.settings.localized
import io.github.ycfeng.ocdeck.ui.component.ActiveSourceTracker
import io.github.ycfeng.ocdeck.ui.component.LocalPlatformActionModeActive
import io.github.ycfeng.ocdeck.ui.theme.OpenCodeTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val notificationTarget = MutableStateFlow<OpenCodeNotificationTarget?>(null)
    private val actionModeTracker = ActiveSourceTracker<ActionMode>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_OpenCode)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        notificationTarget.value = intent.openCodeNotificationTarget()

        val appContainer = (application as OpenCodeApplication).container
        setContent {
            val colorSchemePreference by appContainer.appSettingsStore.colorSchemePreference
                .collectAsStateWithLifecycle(AppColorSchemePreference.System)
            val languagePreference by appContainer.appSettingsStore.languagePreference
                .collectAsStateWithLifecycle(AppLanguagePreference.System)
            val currentNotificationTarget by notificationTarget.collectAsStateWithLifecycle()
            val isActionModeActive by actionModeTracker.isActive.collectAsStateWithLifecycle()
            val baseContext = LocalContext.current
            val localizedContext = remember(baseContext, languagePreference) {
                baseContext.localized(languagePreference)
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalPlatformActionModeActive provides isActionModeActive,
            ) {
                OpenCodeTheme(colorSchemePreference = colorSchemePreference) {
                    OpenCodeApp(
                        appContainer = appContainer,
                        notificationTarget = currentNotificationTarget,
                        onNotificationTargetConsumed = { notificationTarget.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationTarget.value = intent.openCodeNotificationTarget()
    }

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        actionModeTracker.begin(mode)
    }

    override fun onActionModeFinished(mode: ActionMode) {
        actionModeTracker.end(mode)
        super.onActionModeFinished(mode)
    }

    override fun onStart() {
        super.onStart()
        val container = (application as OpenCodeApplication).container
        container.sessionVisibilityRegistry.onAppForeground()
        container.appConnectionCoordinator.onForeground()
    }

    override fun onStop() {
        (application as OpenCodeApplication).container.sessionVisibilityRegistry.onAppBackground()
        super.onStop()
    }
}

private fun Intent.openCodeNotificationTarget(): OpenCodeNotificationTarget? {
    val target = OpenCodeNotificationTarget(
        serverId = getStringExtra(OpenCodeNotificationTarget.ExtraServerId).orEmpty(),
        directory = getStringExtra(OpenCodeNotificationTarget.ExtraDirectory).orEmpty(),
        sessionId = getStringExtra(OpenCodeNotificationTarget.ExtraSessionId),
    )
    return target.takeIf { it.isValid() }
}
