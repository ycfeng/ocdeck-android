package io.github.ycfeng.ocdeck.feature.file

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileContent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileEntry
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileType
import io.github.ycfeng.ocdeck.ui.component.OpenCodeCodeViewer
import io.github.ycfeng.ocdeck.ui.component.OpenCodeHeaderIconButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodePlainTextField
import io.github.ycfeng.ocdeck.ui.component.OpenCodePrimaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeRefreshIconButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeTopBar
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import io.github.ycfeng.ocdeck.ui.text.asString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ProjectFilePanelMode {
    Browse,
    Pick,
}

@Composable
fun ProjectFilePanel(
    state: ProjectFileBrowserUiState,
    onSearchQueryChanged: (String) -> Unit,
    onToggleDirectory: (OpenCodeFileEntry) -> Unit,
    onRetryDirectory: (String) -> Unit,
    onOpenFile: (OpenCodeFileEntry) -> Unit,
    onShowTree: () -> Unit,
    onRefreshTree: () -> Unit,
    onRefreshFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    mode: ProjectFilePanelMode = ProjectFilePanelMode.Browse,
    selectedPaths: Set<String> = emptySet(),
    selectionLimit: Int = 0,
    onToggleFileSelection: (OpenCodeFileEntry) -> Unit = {},
    onConfirmSelection: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OpenCodePalette.Panel)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        when (state.page) {
            ProjectFileBrowserPage.Tree -> ProjectFileTreePage(
                state = state,
                onSearchQueryChanged = onSearchQueryChanged,
                onToggleDirectory = onToggleDirectory,
                onRetryDirectory = onRetryDirectory,
                onOpenFile = onOpenFile,
                onRefresh = onRefreshTree,
                onClose = onClose,
                mode = mode,
                selectedPaths = selectedPaths,
                selectionLimit = selectionLimit,
                onToggleFileSelection = onToggleFileSelection,
                onConfirmSelection = onConfirmSelection,
            )

            ProjectFileBrowserPage.Content -> ProjectFileContentPage(
                state = state,
                onBack = onShowTree,
                onRefresh = onRefreshFile,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun ProjectFileTreePage(
    state: ProjectFileBrowserUiState,
    onSearchQueryChanged: (String) -> Unit,
    onToggleDirectory: (OpenCodeFileEntry) -> Unit,
    onRetryDirectory: (String) -> Unit,
    onOpenFile: (OpenCodeFileEntry) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    mode: ProjectFilePanelMode,
    selectedPaths: Set<String>,
    selectionLimit: Int,
    onToggleFileSelection: (OpenCodeFileEntry) -> Unit,
    onConfirmSelection: () -> Unit,
) {
    OpenCodeTopBar(
        title = stringResource(
            if (mode == ProjectFilePanelMode.Pick) {
                R.string.project_file_picker_title
            } else {
                R.string.file_browser_title
            },
        ),
        actions = {
            OpenCodeRefreshIconButton(
                isRefreshing = if (state.searchQuery.isBlank()) {
                    state.directories[""]?.isLoading == true
                } else {
                    state.isSearching
                },
                onClick = onRefresh,
            )
            OpenCodeHeaderIconButton(
                iconRes = R.drawable.ic_opencode_close,
                contentDescription = stringResource(R.string.a11y_close),
                onClick = onClose,
            )
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_opencode_search),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = OpenCodePalette.IconMuted,
        )
        OpenCodePlainTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChanged,
            placeholder = stringResource(R.string.file_browser_search_placeholder),
            modifier = Modifier.weight(1f),
            minHeight = 48.dp,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            singleLine = true,
        )
    }
    HorizontalDivider(color = OpenCodePalette.Border)

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (state.searchQuery.isBlank()) {
                val rows = remember(state.directories, state.expandedDirectories) {
                    flattenProjectFileTree(state.directories, state.expandedDirectories)
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = rows,
                        key = { row -> row.key() },
                    ) { row ->
                        when (row) {
                            is ProjectFileTreeRow.Entry -> {
                                val file = row.file
                                val selected = file.path in selectedPaths
                                ProjectFileEntryRow(
                                    entry = file,
                                    depth = row.depth,
                                    expanded = file.path in state.expandedDirectories,
                                    showPath = false,
                                    selected = selected.takeIf {
                                        mode == ProjectFilePanelMode.Pick && file.type == OpenCodeFileType.File
                                    },
                                    selectionEnabled = selected || selectedPaths.size < selectionLimit,
                                    onClick = {
                                        if (file.type == OpenCodeFileType.Directory) {
                                            onToggleDirectory(file)
                                        } else if (mode == ProjectFilePanelMode.Pick) {
                                            onToggleFileSelection(file)
                                        } else {
                                            onOpenFile(file)
                                        }
                                    },
                                    onPreview = if (
                                        mode == ProjectFilePanelMode.Pick && file.type == OpenCodeFileType.File
                                    ) {
                                        { onOpenFile(file) }
                                    } else {
                                        null
                                    },
                                )
                            }

                            is ProjectFileTreeRow.Loading -> ProjectFileTreeStatusRow(
                                depth = row.depth,
                                text = stringResource(R.string.file_browser_loading),
                                loading = true,
                            )

                            is ProjectFileTreeRow.Error -> ProjectFileTreeStatusRow(
                                depth = row.depth,
                                text = row.message.asString(),
                                actionLabel = stringResource(R.string.action_retry),
                                onClick = { onRetryDirectory(row.parentPath) },
                            )

                            is ProjectFileTreeRow.Empty -> ProjectFileTreeStatusRow(
                                depth = row.depth,
                                text = stringResource(R.string.file_browser_empty_directory),
                            )
                        }
                    }
                }
            } else {
                ProjectFileSearchResults(
                    state = state,
                    mode = mode,
                    selectedPaths = selectedPaths,
                    selectionLimit = selectionLimit,
                    onOpenFile = onOpenFile,
                    onToggleFileSelection = onToggleFileSelection,
                )
            }
        }
        if (mode == ProjectFilePanelMode.Pick) {
            ProjectFilePickerFooter(
                selectedCount = selectedPaths.size,
                selectionLimit = selectionLimit,
                onConfirmSelection = onConfirmSelection,
            )
        }
    }
}

@Composable
private fun ProjectFileSearchResults(
    state: ProjectFileBrowserUiState,
    mode: ProjectFilePanelMode,
    selectedPaths: Set<String>,
    selectionLimit: Int,
    onOpenFile: (OpenCodeFileEntry) -> Unit,
    onToggleFileSelection: (OpenCodeFileEntry) -> Unit,
) {
    when {
        state.isSearching -> ProjectFileCenteredStatus(
            text = stringResource(R.string.file_browser_searching),
            loading = true,
        )

        state.searchError != null -> ProjectFileCenteredStatus(
            text = state.searchError.asString(),
        )

        state.searchResults.isEmpty() -> ProjectFileCenteredStatus(
            text = stringResource(R.string.file_browser_no_search_results),
        )

        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                items = state.searchResults,
                key = OpenCodeFileEntry::path,
            ) { file ->
                val selected = file.path in selectedPaths
                ProjectFileEntryRow(
                    entry = file,
                    depth = 0,
                    expanded = false,
                    showPath = true,
                    selected = selected.takeIf { mode == ProjectFilePanelMode.Pick },
                    selectionEnabled = selected || selectedPaths.size < selectionLimit,
                    onClick = {
                        if (mode == ProjectFilePanelMode.Pick) {
                            onToggleFileSelection(file)
                        } else {
                            onOpenFile(file)
                        }
                    },
                    onPreview = if (mode == ProjectFilePanelMode.Pick) {
                        { onOpenFile(file) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun ProjectFileEntryRow(
    entry: OpenCodeFileEntry,
    depth: Int,
    expanded: Boolean,
    showPath: Boolean,
    selected: Boolean? = null,
    selectionEnabled: Boolean = true,
    onClick: () -> Unit,
    onPreview: (() -> Unit)? = null,
) {
    val directory = entry.type == OpenCodeFileType.Directory
    val actionDescription = if (directory) {
        stringResource(
            if (expanded) R.string.file_browser_collapse_directory else R.string.file_browser_expand_directory,
            entry.name,
        )
    } else {
        stringResource(
            if (selected != null) R.string.project_file_picker_toggle_file else R.string.file_browser_open_file,
            entry.name,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = actionDescription }
                .then(
                    if (selected != null) {
                        Modifier.toggleable(
                            value = selected,
                            enabled = selectionEnabled,
                            role = Role.Checkbox,
                            onValueChange = { onClick() },
                        )
                    } else {
                        Modifier
                            .semantics { role = Role.Button }
                            .clickable(onClick = onClick)
                    },
                )
                .padding(
                    start = 8.dp + (depth.coerceAtMost(12) * 16).dp,
                    end = if (onPreview == null) 12.dp else 4.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (directory) {
                Icon(
                    painter = painterResource(
                        if (expanded) R.drawable.ic_opencode_chevron_down else R.drawable.ic_opencode_chevron_right,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = OpenCodePalette.IconMuted,
                )
            } else if (selected == true) {
                Icon(
                    painter = painterResource(R.drawable.ic_opencode_check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = OpenCodePalette.Accent,
                )
            } else {
                Spacer(Modifier.width(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(
                    if (directory) R.drawable.ic_opencode_folder else R.drawable.ic_opencode_file,
                ),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (selected == true) OpenCodePalette.Accent else OpenCodePalette.IconMuted,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (entry.ignored) OpenCodePalette.MutedText else OpenCodePalette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showPath) {
                    Text(
                        text = entry.path.substringBeforeLast('/', missingDelimiterValue = ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (onPreview != null) {
            OpenCodeHeaderIconButton(
                iconRes = R.drawable.ic_opencode_file,
                contentDescription = stringResource(R.string.project_file_picker_preview_file, entry.name),
                onClick = onPreview,
            )
        }
    }
}

@Composable
private fun ProjectFilePickerFooter(
    selectedCount: Int,
    selectionLimit: Int,
    onConfirmSelection: () -> Unit,
) {
    HorizontalDivider(color = OpenCodePalette.Border)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                R.string.project_file_picker_selected_count,
                selectedCount,
                selectionLimit,
            ),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = OpenCodePalette.MutedText,
        )
        OpenCodePrimaryButton(
            text = stringResource(R.string.project_file_picker_confirm, selectedCount),
            onClick = onConfirmSelection,
        )
    }
}

@Composable
private fun ProjectFileTreeStatusRow(
    depth: Int,
    text: String,
    loading: Boolean = false,
    actionLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 30.dp + (depth.coerceAtMost(12) * 16).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 1.5.dp,
                color = OpenCodePalette.Accent,
            )
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = OpenCodePalette.MutedText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = OpenCodePalette.Accent,
            )
        }
    }
}

@Composable
private fun ProjectFileContentPage(
    state: ProjectFileBrowserUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val path = state.selectedPath.orEmpty()
    OpenCodeTopBar(
        navigationLabel = stringResource(R.string.a11y_back),
        navigationIconRes = R.drawable.ic_opencode_arrow_left,
        onNavigationClick = onBack,
        title = path.substringAfterLast('/').ifEmpty { stringResource(R.string.file_browser_title) },
        actions = {
            OpenCodeRefreshIconButton(
                isRefreshing = state.isContentLoading,
                onClick = onRefresh,
                enabled = path.isNotEmpty(),
            )
            OpenCodeHeaderIconButton(
                iconRes = R.drawable.ic_opencode_close,
                contentDescription = stringResource(R.string.a11y_close),
                onClick = onClose,
            )
        },
    )
    Text(
        text = path,
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenCodePalette.PanelMuted)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = OpenCodePalette.MutedText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    HorizontalDivider(color = OpenCodePalette.Border)

    when {
        state.isContentLoading -> ProjectFileCenteredStatus(
            text = stringResource(R.string.file_browser_loading_content),
            loading = true,
        )

        state.contentError != null -> ProjectFileCenteredStatus(
            text = state.contentError.asString(),
            actionLabel = stringResource(R.string.action_retry),
            onClick = onRefresh,
        )

        state.content is OpenCodeFileContent.Text -> {
            val content = state.content
            if (!isProjectFileTextPreviewable(content.text)) {
                ProjectFileCenteredStatus(text = stringResource(R.string.file_browser_text_too_large))
            } else {
                OpenCodeCodeViewer(
                    code = content.text,
                    filePath = content.path,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        state.content is OpenCodeFileContent.Binary -> ProjectBinaryFileContent(state.content)
        state.content is OpenCodeFileContent.TooLarge -> ProjectFileCenteredStatus(
            text = stringResource(R.string.file_browser_file_too_large),
        )
        else -> ProjectFileCenteredStatus(text = stringResource(R.string.file_browser_no_content))
    }
}

@Composable
private fun ProjectBinaryFileContent(content: OpenCodeFileContent.Binary) {
    if (!content.mimeType.orEmpty().startsWith("image/")) {
        ProjectFileCenteredStatus(text = stringResource(R.string.file_browser_binary_unavailable))
        return
    }
    if (content.base64.length > MaxPreviewImageBase64Characters) {
        ProjectFileCenteredStatus(text = stringResource(R.string.file_browser_image_too_large))
        return
    }

    val decodedImage by produceState<ImageDecodeState>(
        initialValue = ImageDecodeState.Loading,
        key1 = content.base64,
    ) {
        value = withContext(Dispatchers.Default) {
            try {
                decodeProjectFileImage(content.base64)
                    ?.let(ImageDecodeState::Success)
                    ?: ImageDecodeState.Error
            } catch (_: Exception) {
                ImageDecodeState.Error
            }
        }
    }
    when (val image = decodedImage) {
        ImageDecodeState.Loading -> ProjectFileCenteredStatus(
            text = stringResource(R.string.file_browser_loading_image),
            loading = true,
        )

        ImageDecodeState.Error -> ProjectFileCenteredStatus(
            text = stringResource(R.string.file_browser_image_decode_failed),
        )

        is ImageDecodeState.Success -> Image(
            bitmap = image.bitmap,
            contentDescription = stringResource(R.string.file_browser_image_preview),
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun ProjectFileCenteredStatus(
    text: String,
    loading: Boolean = false,
    actionLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = OpenCodePalette.Accent,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = OpenCodePalette.MutedText,
            )
            if (actionLabel != null && onClick != null) {
                Text(
                    text = actionLabel,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { role = Role.Button }
                        .clickable(onClick = onClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = OpenCodePalette.Accent,
                )
            }
        }
    }
}

private fun ProjectFileTreeRow.key(): String = when (this) {
    is ProjectFileTreeRow.Entry -> "entry:${file.path}"
    is ProjectFileTreeRow.Loading -> "loading:$parentPath"
    is ProjectFileTreeRow.Error -> "error:$parentPath"
    is ProjectFileTreeRow.Empty -> "empty:$parentPath"
}

private sealed interface ImageDecodeState {
    data object Loading : ImageDecodeState
    data object Error : ImageDecodeState
    data class Success(val bitmap: ImageBitmap) : ImageDecodeState
}

internal fun isProjectFileTextPreviewable(text: String): Boolean {
    if (text.length > MaxPreviewTextCharacters) return false
    var lineCount = 1
    var lineLength = 0
    text.forEach { character ->
        if (character == '\n') {
            lineCount += 1
            lineLength = 0
            if (lineCount > MaxPreviewTextLines) return false
        } else {
            lineLength += 1
            if (lineLength > MaxPreviewLineCharacters) return false
        }
    }
    return true
}

private fun decodeProjectFileImage(base64: String): ImageBitmap? {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    val sourcePixels = width.toLong() * height.toLong()
    if (
        width > MaxSourceImageDimension ||
        height > MaxSourceImageDimension ||
        sourcePixels > MaxSourceImagePixels
    ) {
        return null
    }

    var sampleSize = 1
    while (sampleSize < MaxImageSampleSize) {
        val sampledWidth = (width.toLong() + sampleSize - 1) / sampleSize
        val sampledHeight = (height.toLong() + sampleSize - 1) / sampleSize
        if (
            sampledWidth <= MaxDecodedImageDimension &&
            sampledHeight <= MaxDecodedImageDimension &&
            sampledWidth * sampledHeight <= MaxDecodedImagePixels
        ) {
            break
        }
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

private const val MaxPreviewTextCharacters = 500_000
private const val MaxPreviewTextLines = 20_000
private const val MaxPreviewLineCharacters = 20_000
private const val MaxPreviewImageBase64Characters = 12_000_000
private const val MaxSourceImageDimension = 65_536
private const val MaxSourceImagePixels = 1_000_000_000L
private const val MaxDecodedImageDimension = 4_096L
private const val MaxDecodedImagePixels = 4_000_000L
private const val MaxImageSampleSize = 1 shl 30
