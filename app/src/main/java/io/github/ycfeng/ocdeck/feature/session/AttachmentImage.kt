package io.github.ycfeng.ocdeck.feature.session

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.ui.component.OpenCodeDialog
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AttachmentImageThumbnail(
    dataUrl: String,
    filename: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageState by rememberAttachmentImageState(
        dataUrl = dataUrl,
        target = AttachmentImageDecodeTarget.Thumbnail,
    )
    val shape = RoundedCornerShape(6.dp)
    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(
                enabled = imageState is AttachmentImageDecodeState.Success,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = shape,
        color = OpenCodePalette.Canvas,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = imageState) {
                is AttachmentImageDecodeState.Success -> Image(
                    bitmap = state.bitmap,
                    contentDescription = stringResource(R.string.a11y_open_attachment_image, filename),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )

                AttachmentImageDecodeState.Loading,
                AttachmentImageDecodeState.Error,
                -> Icon(
                    painter = painterResource(R.drawable.ic_opencode_archive),
                    contentDescription = null,
                    tint = OpenCodePalette.FaintText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
internal fun AttachmentImagePreviewDialog(
    dataUrl: String,
    filename: String,
    onDismiss: () -> Unit,
) {
    val imageState by rememberAttachmentImageState(
        dataUrl = dataUrl,
        target = AttachmentImageDecodeTarget.Preview,
    )
    val maxPreviewHeight = (LocalConfiguration.current.screenHeightDp.dp - 160.dp).coerceAtLeast(240.dp)

    OpenCodeDialog(
        title = stringResource(R.string.attachment_preview_title),
        onDismiss = onDismiss,
    ) {
        when (val state = imageState) {
            AttachmentImageDecodeState.Loading -> AttachmentImagePreviewStatus(
                text = stringResource(R.string.attachment_image_loading),
                maxHeight = maxPreviewHeight,
                loading = true,
            )

            AttachmentImageDecodeState.Error -> AttachmentImagePreviewStatus(
                text = stringResource(R.string.attachment_image_decode_failed),
                maxHeight = maxPreviewHeight,
            )

            is AttachmentImageDecodeState.Success -> Image(
                bitmap = state.bitmap,
                contentDescription = stringResource(R.string.a11y_attachment_image_preview, filename),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPreviewHeight)
                    .aspectRatio(state.bitmap.width.toFloat() / state.bitmap.height.coerceAtLeast(1)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun AttachmentImagePreviewStatus(
    text: String,
    maxHeight: androidx.compose.ui.unit.Dp,
    loading: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = maxHeight),
        contentAlignment = Alignment.Center,
    ) {
        Column(
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
        }
    }
}

@Composable
private fun rememberAttachmentImageState(
    dataUrl: String,
    target: AttachmentImageDecodeTarget,
): State<AttachmentImageDecodeState> = produceState<AttachmentImageDecodeState>(
    initialValue = AttachmentImageDecodeState.Loading,
    key1 = dataUrl,
    key2 = target,
) {
    value = withContext(Dispatchers.Default) {
        try {
            decodeAttachmentDataUrlImage(dataUrl, target)
                ?.let(AttachmentImageDecodeState::Success)
                ?: AttachmentImageDecodeState.Error
        } catch (_: Exception) {
            AttachmentImageDecodeState.Error
        }
    }
}

private fun decodeAttachmentDataUrlImage(
    dataUrl: String,
    target: AttachmentImageDecodeTarget,
): ImageBitmap? {
    val commaIndex = dataUrl.indexOf(',')
    if (
        !dataUrl.startsWith("data:", ignoreCase = true) ||
        commaIndex < 5 ||
        commaIndex > MaxDataUrlHeaderCharacters
    ) {
        return null
    }
    val metadata = dataUrl.substring(5, commaIndex)
    if (metadata.split(';').drop(1).none { it.trim().equals("base64", ignoreCase = true) }) {
        return null
    }
    val encodedLength = dataUrl.length - commaIndex - 1
    if (encodedLength <= 0 || encodedLength > MaxAttachmentImageBase64Characters) return null

    val bytes = Base64.decode(dataUrl.substring(commaIndex + 1), Base64.DEFAULT)
    if (bytes.isEmpty() || bytes.size > MaxAttachmentImageBytes) return null

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
            sampledWidth <= target.maxDimension &&
            sampledHeight <= target.maxDimension &&
            sampledWidth * sampledHeight <= target.maxPixels
        ) {
            break
        }
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inScaled = false
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

private sealed interface AttachmentImageDecodeState {
    data object Loading : AttachmentImageDecodeState
    data object Error : AttachmentImageDecodeState
    data class Success(val bitmap: ImageBitmap) : AttachmentImageDecodeState
}

private enum class AttachmentImageDecodeTarget(
    val maxDimension: Long,
    val maxPixels: Long,
) {
    Thumbnail(maxDimension = 512L, maxPixels = 512L * 512L),
    Preview(maxDimension = 4_096L, maxPixels = 4_000_000L),
}

private const val MaxDataUrlHeaderCharacters = 256
private const val MaxAttachmentImageBase64Characters = 28_000_000
private const val MaxAttachmentImageBytes = 20 * 1024 * 1024
private const val MaxSourceImageDimension = 65_536
private const val MaxSourceImagePixels = 1_000_000_000L
private const val MaxImageSampleSize = 1 shl 30
