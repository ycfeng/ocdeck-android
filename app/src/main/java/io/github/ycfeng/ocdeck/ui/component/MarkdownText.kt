package io.github.ycfeng.ocdeck.ui.component

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.recycler.MarkwonAdapter
import io.noties.markwon.recycler.table.TableEntry
import io.noties.markwon.recycler.table.TableEntryPlugin
import io.noties.prism4j.Prism4j
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.Code
import kotlin.math.roundToInt

@Composable
fun OpenCodeMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = OpenCodePalette.Text,
    linkColor: Color = OpenCodePalette.Accent,
    selectable: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val codeTextSizePx = with(density) { 14.sp.toPx().toInt() }
    val syntaxColors = openCodeSyntaxColors()
    val tableColors = openCodeTableColors()
    val markwon = remember(context, codeTextSizePx, syntaxColors.inlineCode) {
        createOpenCodeMarkwon(context, codeTextSizePx, syntaxColors.inlineCode)
    }
    val tableMarkwon = remember(context, codeTextSizePx, syntaxColors.inlineCode, linkColor, tableColors) {
        createOpenCodeTableMarkwon(
            context = context,
            codeTextSizePx = codeTextSizePx,
            inlineCodeColor = syntaxColors.inlineCode,
            linkColor = linkColor,
            tableColors = tableColors,
        )
    }
    val prism4j = remember { OpenCodePrismFactory.create() }
    val highlighter = remember(prism4j, syntaxColors) { OpenCodeSyntaxHighlighter(prism4j, syntaxColors) }
    val blocks = remember(text) { splitMarkdownBlocks(text.ifBlank { " " }) }
    val selectionHighlightColor = OpenCodePalette.SelectionBackground
    val textSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() else 14.sp.toPx()
    }
    val lineSpacingExtraPx = with(density) {
        if (style.lineHeight.isSpecified && style.fontSize.isSpecified) {
            (style.lineHeight.toPx() - textSizePx).coerceAtLeast(0f)
        } else {
            0f
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Markdown -> MarkdownTextView(
                    text = block.text,
                    markwon = markwon,
                    textSizePx = textSizePx,
                    lineSpacingExtraPx = lineSpacingExtraPx,
                    color = color,
                    linkColor = linkColor,
                    selectable = selectable,
                    selectionHighlightColor = selectionHighlightColor,
                )

                is MarkdownBlock.Code -> OpenCodeCodeBlock(
                    code = block.code,
                    language = block.language,
                    highlighter = highlighter,
                    syntaxColors = syntaxColors,
                    selectable = selectable,
                )

                is MarkdownBlock.Table -> OpenCodeMarkdownTable(
                    text = block.text,
                    markwon = tableMarkwon,
                    textColor = color,
                    linkColor = linkColor,
                )
            }
        }
    }
}

private fun createOpenCodeMarkwon(
    context: Context,
    codeTextSizePx: Int,
    inlineCodeColor: Color,
): Markwon = Markwon.builder(context)
    .usePlugin(createOpenCodeMarkdownPlugin(codeTextSizePx, inlineCodeColor))
    .build()

private fun createOpenCodeTableMarkwon(
    context: Context,
    codeTextSizePx: Int,
    inlineCodeColor: Color,
    linkColor: Color,
    tableColors: MarkdownTableColors,
): Markwon {
    val tableTheme = TableTheme.emptyBuilder()
        .tableCellPadding(context.dpToPx(8f))
        .tableBorderWidth(context.dpToPx(1f))
        .tableBorderColor(tableColors.border.toArgb())
        .tableHeaderRowBackgroundColor(tableColors.headerRow.toArgb())
        .tableOddRowBackgroundColor(tableColors.oddRow.toArgb())
        .tableEvenRowBackgroundColor(tableColors.evenRow.toArgb())
        .build()

    return Markwon.builder(context)
        .usePlugin(TableEntryPlugin.create(tableTheme))
        .usePlugin(createOpenCodeMarkdownPlugin(codeTextSizePx, inlineCodeColor, linkColor))
        .build()
}

private fun createOpenCodeMarkdownPlugin(
    codeTextSizePx: Int,
    inlineCodeColor: Color,
    linkColor: Color? = null,
): AbstractMarkwonPlugin =
    object : AbstractMarkwonPlugin() {
        override fun configureTheme(builder: MarkwonTheme.Builder) {
            builder
                .codeTextColor(inlineCodeColor.toArgb())
                .codeBackgroundColor(android.graphics.Color.TRANSPARENT)
                .codeTypeface(Typeface.MONOSPACE)
                .codeTextSize(codeTextSizePx)
            linkColor?.let { builder.linkColor(it.toArgb()) }
        }

        override fun configureVisitor(builder: MarkwonVisitor.Builder) {
            builder.on(
                Code::class.java,
                object : MarkwonVisitor.NodeVisitor<Code> {
                    override fun visit(visitor: MarkwonVisitor, code: Code) {
                        val start = visitor.length()
                        visitor.builder().append(code.literal)
                        val end = visitor.length()
                        visitor.builder().setSpan(
                            ForegroundColorSpan(inlineCodeColor.toArgb()),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        visitor.builder().setSpan(
                            TypefaceSpan("monospace"),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        visitor.builder().setSpan(
                            AbsoluteSizeSpan(codeTextSizePx),
                            start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                },
            )
        }
    }

private fun Context.dpToPx(dp: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).roundToInt()

@Composable
private fun OpenCodeMarkdownTable(
    text: String,
    markwon: Markwon,
    textColor: Color,
    linkColor: Color,
) {
    val adapter = remember { createOpenCodeTableAdapter() }
    val textColorArgb = textColor.toArgb()
    val linkColorArgb = linkColor.toArgb()

    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { viewContext ->
            MarkdownTableRecyclerView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                itemAnimator = null
                layoutManager = LinearLayoutManager(viewContext)
                this.adapter = adapter
            }
        },
        update = { recyclerView ->
            if (recyclerView.adapter !== adapter) {
                recyclerView.adapter = adapter
            }
            recyclerView.setMarkdownTextColors(textColorArgb, linkColorArgb)
            adapter.setMarkdown(markwon, text)
            adapter.notifyDataSetChanged()
            recyclerView.post { recyclerView.applyMarkdownTableTextColors(textColorArgb, linkColorArgb) }
        },
    )
}

private class MarkdownTableRecyclerView(context: Context) : RecyclerView(context) {
    private var markdownTextColor: Int = 0
    private var markdownLinkColor: Int = 0

    fun setMarkdownTextColors(textColor: Int, linkColor: Int) {
        markdownTextColor = textColor
        markdownLinkColor = linkColor
        applyMarkdownTableTextColors(textColor, linkColor)
    }

    override fun onChildAttachedToWindow(child: View) {
        super.onChildAttachedToWindow(child)
        child.applyMarkdownTableTextColors(markdownTextColor, markdownLinkColor)
    }
}

private fun View.applyMarkdownTableTextColors(textColor: Int, linkColor: Int) {
    when (this) {
        is TextView -> {
            setTextColor(textColor)
            setLinkTextColor(linkColor)
            linksClickable = true
            movementMethod = LinkMovementMethod.getInstance()
        }

        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyMarkdownTableTextColors(textColor, linkColor)
            }
        }
    }
}

private fun createOpenCodeTableAdapter(): MarkwonAdapter =
    MarkwonAdapter.builderTextViewIsRoot(R.layout.markwon_adapter_text)
        .include(
            TableBlock::class.java,
            TableEntry.create { builder ->
                builder
                    .tableLayout(R.layout.markwon_table_block, R.id.markwon_table_layout)
                    .textLayoutIsRoot(R.layout.markwon_table_cell)
                    .cellTextCenterVertical(true)
                    .isRecyclable(false)
            },
        )
        .build()

@Composable
private fun MarkdownTextView(
    text: String,
    markwon: Markwon,
    textSizePx: Float,
    lineSpacingExtraPx: Float,
    color: Color,
    linkColor: Color,
    selectable: Boolean,
    selectionHighlightColor: Color,
) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { viewContext ->
            TextView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                includeFontPadding = false
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                highlightColor = Color.Transparent.toArgb()
            }
        },
        update = { textView ->
            textView.setTextColor(color.toArgb())
            textView.setLinkTextColor(linkColor.toArgb())
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            textView.setLineSpacing(lineSpacingExtraPx, 1f)
            markwon.setMarkdown(textView, text)
            textView.highlightColor = if (selectable) selectionHighlightColor.toArgb() else Color.Transparent.toArgb()
            textView.setTextIsSelectable(selectable)
            textView.movementMethod = LinkMovementMethod.getInstance()
        },
    )
}

@Composable
private fun OpenCodeCodeBlock(
    code: String,
    language: String?,
    highlighter: OpenCodeSyntaxHighlighter,
    syntaxColors: CodeSyntaxColors,
    selectable: Boolean,
) {
    val highlighted = remember(code, language, highlighter) { highlighter.highlight(code, language) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 24.dp),
        shape = RoundedCornerShape(6.dp),
        color = OpenCodePalette.PanelMuted,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        if (selectable) {
            SelectionContainer {
                CodeBlockText(highlighted, syntaxColors.default)
            }
        } else {
            CodeBlockText(highlighted, syntaxColors.default)
        }
    }
}

@Composable
private fun CodeBlockText(highlighted: AnnotatedString, defaultColor: Color) {
    Text(
        text = highlighted,
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Normal,
        ),
        color = defaultColor,
        softWrap = false,
    )
}

private sealed interface MarkdownBlock {
    data class Markdown(val text: String) : MarkdownBlock {
        override fun toString(): String = "MarkdownBlock.Markdown(textLength=${text.length})"
    }

    data class Code(val language: String?, val code: String) : MarkdownBlock {
        override fun toString(): String =
            "MarkdownBlock.Code(languagePresent=${language != null}, codeLength=${code.length})"
    }

    data class Table(val text: String) : MarkdownBlock {
        override fun toString(): String = "MarkdownBlock.Table(textLength=${text.length})"
    }
}

private data class Fence(
    val marker: Char,
    val length: Int,
    val language: String?,
)

private fun splitMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val markdownBuffer = StringBuilder()
    var index = 0

    fun flushMarkdown() {
        if (markdownBuffer.isNotEmpty()) {
            blocks += MarkdownBlock.Markdown(markdownBuffer.toString())
            markdownBuffer.clear()
        }
    }

    while (index < lines.size) {
        val fence = openingFence(lines[index])
        if (fence != null) {
            flushMarkdown()
            index++
            val code = StringBuilder()
            while (index < lines.size && !isClosingFence(lines[index], fence)) {
                code.append(lines[index])
                if (index != lines.lastIndex && !isClosingFence(lines.getOrNull(index + 1).orEmpty(), fence)) {
                    code.append('\n')
                }
                index++
            }
            if (index < lines.size) index++
            blocks += MarkdownBlock.Code(fence.language, code.toString())
            continue
        }

        val tableEnd = markdownTableEnd(lines, index)
        if (tableEnd != null) {
            flushMarkdown()
            blocks += MarkdownBlock.Table(lines.subList(index, tableEnd).joinToString("\n"))
            index = tableEnd
            continue
        }

        markdownBuffer.append(lines[index])
        if (index != lines.lastIndex) markdownBuffer.append('\n')
        index++
    }

    flushMarkdown()
    return blocks.ifEmpty { listOf(MarkdownBlock.Markdown(" ")) }
}

private fun openingFence(line: String): Fence? {
    val trimmed = line.dropWhile { it == ' ' || it == '\t' }
    if (line.length - trimmed.length > 3) return null
    val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = trimmed.takeWhile { it == marker }.length
    if (length < 3) return null
    val info = trimmed.drop(length).trim()
    val language = info.takeIf { it.isNotBlank() }
        ?.substringBefore(' ')
        ?.trim('{', '}')
        ?.lowercase()
        ?.ifBlank { null }
    return Fence(marker, length, language)
}

private fun isClosingFence(line: String, fence: Fence): Boolean {
    val trimmed = line.dropWhile { it == ' ' || it == '\t' }
    if (line.length - trimmed.length > 3) return false
    val length = trimmed.takeWhile { it == fence.marker }.length
    return length >= fence.length && trimmed.drop(length).trim().isEmpty()
}

private fun markdownTableEnd(lines: List<String>, start: Int): Int? {
    val headerCells = splitTableCells(lines.getOrNull(start).orEmpty())
    val separatorCells = splitTableCells(lines.getOrNull(start + 1).orEmpty())
    if (headerCells.isEmpty() || separatorCells.isEmpty() || headerCells.size != separatorCells.size) return null
    if (!separatorCells.all(::isTableSeparatorCell)) return null

    var end = start + 2
    while (end < lines.size && splitTableCells(lines[end]).isNotEmpty()) {
        end++
    }
    return end
}

private fun splitTableCells(line: String): List<String> {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || !containsUnescapedPipe(trimmed)) return emptyList()

    val start = if (trimmed.first() == '|') 1 else 0
    val end = if (trimmed.last() == '|' && !isEscaped(trimmed, trimmed.lastIndex)) {
        trimmed.lastIndex
    } else {
        trimmed.length
    }
    if (start >= end) return emptyList()

    val cells = mutableListOf<String>()
    val current = StringBuilder()
    for (index in start until end) {
        val char = trimmed[index]
        if (char == '|' && !isEscaped(trimmed, index)) {
            cells += current.toString().trim()
            current.clear()
        } else {
            current.append(char)
        }
    }
    cells += current.toString().trim()
    return cells
}

private fun containsUnescapedPipe(line: String): Boolean =
    line.indices.any { index -> line[index] == '|' && !isEscaped(line, index) }

private fun isEscaped(text: String, index: Int): Boolean {
    var backslashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
        backslashCount++
        cursor--
    }
    return backslashCount % 2 == 1
}

private fun isTableSeparatorCell(cell: String): Boolean {
    val trimmed = cell.trim()
    val withoutLeadingAlignment = if (trimmed.startsWith(':')) trimmed.drop(1) else trimmed
    val marker = if (withoutLeadingAlignment.endsWith(':')) {
        withoutLeadingAlignment.dropLast(1)
    } else {
        withoutLeadingAlignment
    }
    return marker.length >= 3 && marker.all { it == '-' }
}

internal class OpenCodeSyntaxHighlighter(
    private val prism4j: Prism4j,
    private val colors: CodeSyntaxColors,
) {
    fun highlight(code: String, language: String?): AnnotatedString {
        val normalizedLanguage = language.normalizedCodeLanguage()
        val grammar = normalizedLanguage?.let(prism4j::grammar) ?: return AnnotatedString(code)
        return runCatching {
            buildAnnotatedString {
                appendNodes(prism4j.tokenize(code, grammar), colors.default)
            }
        }.getOrElse { AnnotatedString(code) }
    }

    private fun AnnotatedString.Builder.appendNodes(nodes: List<Prism4j.Node>, inheritedColor: Color) {
        nodes.forEach { node -> appendNode(node, inheritedColor) }
    }

    private fun AnnotatedString.Builder.appendNode(node: Prism4j.Node, inheritedColor: Color) {
        when (node) {
            is Prism4j.Text -> withStyle(SpanStyle(color = inheritedColor)) { append(node.literal()) }
            is Prism4j.Syntax -> {
                val color = node.type().syntaxColor(colors) ?: inheritedColor
                appendNodes(node.children(), color)
            }
        }
    }
}

internal data class CodeSyntaxColors(
    val default: Color,
    val muted: Color,
    val text: Color,
    val inlineCode: Color,
    val blue: Color,
    val teal: Color,
    val danger: Color,
)

private data class MarkdownTableColors(
    val border: Color,
    val headerRow: Color,
    val oddRow: Color,
    val evenRow: Color,
)

internal fun String?.normalizedCodeLanguage(): String? = when (this?.lowercase()?.trim()) {
    null, "" -> null
    "js", "jsx", "mjs", "cjs", "ts", "tsx", "typescript" -> "javascript"
    "html", "xml", "svg", "mathml" -> "markup"
    "kt", "kts" -> "kotlin"
    "md" -> "markdown"
    "py" -> "python"
    "yml" -> "yaml"
    "golang" -> "go"
    "jsonc", "json5" -> "json"
    "sh", "shell", "bash", "zsh", "fish", "cmd", "bat", "ps1", "pwsh", "powershell" -> null
    else -> this.lowercase().trim()
}

@Composable
internal fun openCodeSyntaxColors(): CodeSyntaxColors {
    return CodeSyntaxColors(
        default = OpenCodePalette.SyntaxDefault,
        muted = OpenCodePalette.SyntaxComment,
        text = OpenCodePalette.Text,
        inlineCode = OpenCodePalette.SyntaxInline,
        blue = OpenCodePalette.SyntaxBlue,
        teal = OpenCodePalette.SyntaxTeal,
        danger = OpenCodePalette.Danger,
    )
}

@Composable
private fun openCodeTableColors(): MarkdownTableColors {
    return MarkdownTableColors(
        border = OpenCodePalette.Border,
        headerRow = OpenCodePalette.PanelMuted,
        oddRow = OpenCodePalette.Panel,
        evenRow = OpenCodePalette.TableRowAlternate,
    )
}

private fun String.syntaxColor(colors: CodeSyntaxColors): Color? = when (this) {
    "property", "attr-name", "function", "builtin", "keyword" -> colors.blue
    "string", "attr-value", "char", "inserted" -> colors.inlineCode
    "number", "boolean", "constant", "symbol" -> colors.teal
    "comment", "prolog", "doctype", "cdata" -> colors.muted
    "tag", "selector", "class-name" -> colors.text
    "operator", "punctuation" -> colors.default
    "deleted" -> colors.danger
    else -> null
}
