package io.github.ycfeng.ocdeck.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import io.github.ycfeng.ocdeck.core.notification.OpenCodeNotificationTarget
import io.github.ycfeng.ocdeck.core.navigation.OpenCodeNavGraph

@Composable
fun OpenCodeApp(
    appContainer: AppContainer,
    notificationTarget: OpenCodeNotificationTarget?,
    onNotificationTargetConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    OpenCodeNavGraph(
        navController = navController,
        appContainer = appContainer,
        notificationTarget = notificationTarget,
        onNotificationTargetConsumed = onNotificationTargetConsumed,
    )
}
