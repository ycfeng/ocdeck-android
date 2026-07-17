package io.github.ycfeng.ocdeck.ui.component

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
fun LocalizedPopup(
    alignment: Alignment,
    offset: IntOffset,
    onDismissRequest: (() -> Unit)?,
    properties: PopupProperties,
    content: @Composable () -> Unit,
) {
    val localization = rememberWindowLocalization()
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        localization.Provide(content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalizedModalBottomSheet(
    onDismissRequest: () -> Unit,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val localization = rememberWindowLocalization()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = containerColor,
    ) {
        localization.Provide { content() }
    }
}

@Composable
fun LocalizedDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    content: @Composable () -> Unit,
) {
    val localization = rememberWindowLocalization()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        localization.Provide(content)
    }
}

@Composable
internal fun rememberWindowLocalization(): WindowLocalization {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        WindowLocalization(
            context = context,
            configuration = Configuration(configuration),
        )
    }
}

@Immutable
internal class WindowLocalization(
    private val context: Context,
    private val configuration: Configuration,
) {
    @Composable
    fun Provide(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            content = content,
        )
    }
}
