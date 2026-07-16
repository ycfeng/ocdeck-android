package io.github.ycfeng.ocdeck.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun OpenCodeCodeViewer(
    code: String,
    filePath: String,
    modifier: Modifier = Modifier,
) {
    val colors = openCodeSyntaxColors()
    val prism4j = remember { OpenCodePrismFactory.create() }
    val highlighter = remember(prism4j, colors) { OpenCodeSyntaxHighlighter(prism4j, colors) }
    val language = remember(filePath) { filePath.substringAfterLast('.', missingDelimiterValue = "") }
    val highlightedLines by produceState<List<AnnotatedString>?>(
        initialValue = null,
        key1 = code,
        key2 = language,
        key3 = highlighter,
    ) {
        value = withContext(Dispatchers.Default) {
            val normalizedCode = code.replace("\r\n", "\n").replace('\r', '\n')
            highlighter.highlight(normalizedCode, language).splitLines()
        }
    }

    val lines = highlightedLines
    if (lines == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OpenCodePalette.Accent)
        }
        return
    }

    var horizontalOffset by remember(code) { mutableFloatStateOf(0f) }
    var codeViewportWidth by remember(code) { mutableIntStateOf(0) }
    var widestCodeLineWidth by remember(code) { mutableIntStateOf(0) }
    val horizontalPaddingPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val horizontalScrollableState = rememberScrollableState { delta ->
        val maxOffset = (widestCodeLineWidth - codeViewportWidth).coerceAtLeast(0).toFloat()
        val previousOffset = horizontalOffset
        horizontalOffset = (horizontalOffset - delta).coerceIn(0f, maxOffset)
        previousOffset - horizontalOffset
    }

    LaunchedEffect(widestCodeLineWidth, codeViewportWidth) {
        val maxOffset = (widestCodeLineWidth - codeViewportWidth).coerceAtLeast(0).toFloat()
        horizontalOffset = horizontalOffset.coerceAtMost(maxOffset)
    }

    SelectionContainer {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(OpenCodePalette.Panel)
                .clipScrollableContainer(Orientation.Horizontal)
                .scrollable(
                    state = horizontalScrollableState,
                    orientation = Orientation.Horizontal,
                ),
        ) {
            itemsIndexed(
                items = lines,
                key = { index, _ -> index },
            ) { index, line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .heightIn(min = 24.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .fillMaxHeight()
                            .background(OpenCodePalette.PanelMuted)
                            .padding(end = 10.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        DisableSelection {
                            Text(
                                text = (index + 1).toString(),
                                style = CodeViewerTextStyle,
                                color = colors.muted,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .onSizeChanged { codeViewportWidth = it.width },
                    ) {
                        Text(
                            text = line,
                            modifier = Modifier
                                .offset { IntOffset(-horizontalOffset.roundToInt(), 0) }
                                .wrapContentWidth(
                                    align = Alignment.Start,
                                    unbounded = true,
                                )
                                .heightIn(min = 24.dp)
                                .padding(horizontal = 12.dp),
                            style = CodeViewerTextStyle,
                            color = colors.default,
                            softWrap = false,
                            onTextLayout = { result ->
                                widestCodeLineWidth = maxOf(
                                    widestCodeLineWidth,
                                    result.size.width + horizontalPaddingPx,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private val CodeViewerTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 24.sp,
    fontWeight = FontWeight.Normal,
)

private fun AnnotatedString.splitLines(): List<AnnotatedString> {
    val lines = mutableListOf<AnnotatedString>()
    var lineStart = 0
    text.forEachIndexed { index, character ->
        if (character == '\n') {
            lines += subSequence(lineStart, index)
            lineStart = index + 1
        }
    }
    lines += subSequence(lineStart, length)
    return lines
}
