package io.github.ycfeng.ocdeck.feature.sessionlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.store.SessionListMoreState
import io.github.ycfeng.ocdeck.core.store.SessionListWindowState
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette

@Composable
internal fun SessionListWindowFooter(
    window: SessionListWindowState,
    hasMoreLoadedRoots: Boolean,
    isProjectLoading: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        hasMoreLoadedRoots -> SessionListActionButton(
            text = stringResource(R.string.session_load_more),
            onClick = onLoadMore,
            modifier = modifier,
        )
        window.moreState == SessionListMoreState.Loading ||
            window.moreState == SessionListMoreState.Unknown && isProjectLoading -> SessionListLoadingStatus(modifier)
        window.moreState == SessionListMoreState.Failed -> SessionListFailedStatus(onRetry, modifier)
        window.moreState == SessionListMoreState.EndReached -> SessionListTextStatus(
            text = stringResource(R.string.session_no_more),
            modifier = modifier,
        )
        else -> SessionListActionButton(
            text = stringResource(R.string.session_load_more),
            onClick = onLoadMore,
            modifier = modifier,
        )
    }
}

@Composable
private fun SessionListActionButton(
    text: String,
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
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenCodePalette.Text,
                )
            }
        }
    }
}

@Composable
private fun SessionListLoadingStatus(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = OpenCodePalette.MutedText,
        )
        Text(
            text = stringResource(R.string.session_loading_more),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = OpenCodePalette.MutedText,
        )
    }
}

@Composable
private fun SessionListFailedStatus(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SessionListTextStatus(stringResource(R.string.session_load_more_failed))
        SessionListActionButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
        )
    }
}

@Composable
private fun SessionListTextStatus(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = OpenCodePalette.MutedText,
        )
    }
}
