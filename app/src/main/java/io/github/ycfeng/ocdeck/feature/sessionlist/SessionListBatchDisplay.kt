package io.github.ycfeng.ocdeck.feature.sessionlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette

internal const val SessionListBatchSize = 20

@Composable
internal fun SessionListLoadMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            shape = RoundedCornerShape(8.dp),
            color = OpenCodePalette.PanelMuted,
            border = BorderStroke(1.dp, OpenCodePalette.Border),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.session_load_more),
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenCodePalette.Text,
                )
            }
        }
    }
}
