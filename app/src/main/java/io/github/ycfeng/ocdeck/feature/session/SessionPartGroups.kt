package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.ui.text.UiText
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal sealed interface AssistantPartGroup {
    val key: String

    data class Text(val part: OpenCodeMessagePart) : AssistantPartGroup {
        override val key: String = part.id
    }

    data class Context(val parts: List<OpenCodeMessagePart>) : AssistantPartGroup {
        override val key: String = "context:${parts.first().id}"

        fun isPending(forceBusy: Boolean): Boolean = forceBusy || parts.any { it.isPendingTool() }
    }

    data class Shell(val part: OpenCodeMessagePart) : AssistantPartGroup {
        override val key: String = part.id
    }

    data class Patch(val part: OpenCodeMessagePart) : AssistantPartGroup {
        override val key: String = part.id
    }

    data class Skill(val part: OpenCodeMessagePart) : AssistantPartGroup {
        override val key: String = part.id
    }

    data class Question(val part: OpenCodeMessagePart) : AssistantPartGroup {
        override val key: String = part.id
    }

    data class SubagentTask(val part: OpenCodeMessagePart) : AssistantPartGroup {
        override val key: String = part.id
    }

    data class GenericTool(val part: OpenCodeMessagePart) : AssistantPartGroup {
        override val key: String = part.id
    }

    data class ToolError(val part: OpenCodeMessagePart) : AssistantPartGroup {
        override val key: String = part.id
    }
}

internal data class ContextToolSummary(
    val read: Int,
    val search: Int,
    val list: Int,
) {
    val isEmpty: Boolean = read == 0 && search == 0 && list == 0
}

internal data class ContextToolDisplay(
    val title: UiText,
    val subtitle: String?,
    val args: List<String>,
    val running: Boolean,
) {
    override fun toString(): String =
        "ContextToolDisplay(titlePresent=true, subtitlePresent=${subtitle != null}, " +
            "argCount=${args.size}, running=$running)"
}

internal data class ShellToolDisplay(
    val command: String,
    val output: String?,
    val error: String?,
    val pending: Boolean,
) {
    val copyText: String
        get() = when {
            error != null -> error
            command.isNotBlank() && !output.isNullOrBlank() -> "$ $command\n\n$output"
            command.isNotBlank() -> "$ $command"
            !output.isNullOrBlank() -> output
            else -> ""
        }

    override fun toString(): String =
        "ShellToolDisplay(commandPresent=${command.isNotEmpty()}, outputPresent=${output != null}, " +
            "errorPresent=${error != null}, pending=$pending)"
}

internal data class PatchToolDisplay(
    val files: List<PatchFileDisplay>,
    val pending: Boolean,
) {
    val singleFile: PatchFileDisplay? = files.singleOrNull()
    val fileCountLabel: UiText? = files.size.takeIf { it > 0 }?.let { count ->
        UiText.Resource(R.string.tool_file_count, listOf(count))
    }

    override fun toString(): String =
        "PatchToolDisplay(fileCount=${files.size}, pending=$pending)"
}

internal data class PatchFileDisplay(
    val filePath: String,
    val relativePath: String,
    val type: String,
    val additions: Int,
    val deletions: Int,
    val movePath: String?,
    val diff: String?,
) {
    val fileName: String = relativePath.fileName()
    val directory: String? = relativePath.directoryName()
    val hasChangeCount: Boolean = additions > 0 || deletions > 0
    val actionLabel: UiText? = when (type) {
        "add" -> UiText.Resource(R.string.tool_file_created)
        "delete" -> UiText.Resource(R.string.tool_file_deleted)
        "move" -> UiText.Resource(R.string.tool_file_moved)
        else -> null
    }

    override fun toString(): String =
        "PatchFileDisplay(filePath=<redacted>, relativePath=<redacted>, type=$type, " +
            "additions=$additions, deletions=$deletions, movePath=${redactedIfPresent(movePath)}, " +
            "diffPresent=${diff != null})"
}

internal data class SkillToolDisplay(
    val title: UiText,
    val pending: Boolean,
)

internal data class QuestionToolDisplay(
    val subtitle: UiText,
    val answers: List<QuestionAnswerDisplay>,
    val dismissed: Boolean,
) {
    val defaultOpen: Boolean = answers.isNotEmpty()
}

internal data class QuestionAnswerDisplay(
    val question: UiText,
    val answer: UiText,
)

internal data class SubagentTaskDisplay(
    val agent: String?,
    val description: String?,
    val childSessionId: String?,
    val pending: Boolean,
    val background: Boolean,
) {
    override fun toString(): String =
        "SubagentTaskDisplay(agentPresent=${agent != null}, descriptionPresent=${description != null}, " +
            "childSessionId=${redactedIfPresent(childSessionId)}, pending=$pending, background=$background)"
}

internal data class GenericToolDisplay(
    val title: UiText,
    val subtitle: String?,
    val args: List<String>,
    val pending: Boolean,
) {
    override fun toString(): String =
        "GenericToolDisplay(titlePresent=true, subtitlePresent=${subtitle != null}, " +
            "argCount=${args.size}, pending=$pending)"
}

internal data class ToolErrorDisplay(
    val title: UiText,
    val subtitle: UiText,
    val body: String,
    val copyText: String,
) {
    override fun toString(): String =
        "ToolErrorDisplay(titlePresent=true, subtitlePresent=true, bodyPresent=${body.isNotEmpty()}, " +
            "copyTextPresent=${copyText.isNotEmpty()})"
}

private fun redactedIfPresent(value: Any?): String = if (value == null) "null" else "<redacted>"

private val ContextToolNames = setOf("read", "glob", "grep", "list")
private val HiddenTools = setOf("todowrite")
private val RegisteredWebTools = ContextToolNames + setOf(
    "bash",
    "webfetch",
    "websearch",
    "task",
    "edit",
    "write",
    "apply_patch",
    "todowrite",
    "question",
    "skill",
)
private val GenericLabelKeys = listOf("description", "query", "url", "filePath", "path", "pattern", "name")
private val ToolJson = Json { ignoreUnknownKeys = true }

internal fun groupAssistantParts(parts: List<OpenCodeMessagePart>): List<AssistantPartGroup> {
    val groups = mutableListOf<AssistantPartGroup>()
    val contextParts = mutableListOf<OpenCodeMessagePart>()

    fun flushContext() {
        if (contextParts.isNotEmpty()) {
            groups += AssistantPartGroup.Context(contextParts.toList())
            contextParts.clear()
        }
    }

    parts.forEach { part ->
        when {
            part.isContextTool() -> contextParts += part
            part.isQuestionTool() -> {
                flushContext()
                when {
                    part.isPendingTool() -> Unit
                    part.isRenderableQuestionTool() -> groups += AssistantPartGroup.Question(part)
                    part.isToolError() -> groups += AssistantPartGroup.ToolError(part)
                }
            }
            part.isShellTool() -> {
                flushContext()
                groups += AssistantPartGroup.Shell(part)
            }
            part.isToolError() -> {
                flushContext()
                groups += AssistantPartGroup.ToolError(part)
            }
            part.isPatchTool() -> {
                flushContext()
                groups += AssistantPartGroup.Patch(part)
            }
            part.isSkillTool() -> {
                flushContext()
                groups += AssistantPartGroup.Skill(part)
            }
            part.isTaskTool() -> {
                flushContext()
                groups += AssistantPartGroup.SubagentTask(part)
            }
            part.isGenericTool() -> {
                flushContext()
                groups += AssistantPartGroup.GenericTool(part)
            }
            part.type == "text" && !part.synthetic && !part.text.isNullOrBlank() -> {
                flushContext()
                groups += AssistantPartGroup.Text(part)
            }
            else -> flushContext()
        }
    }
    flushContext()
    return groups
}

internal fun hasRenderableSessionPart(parts: List<OpenCodeMessagePart>): Boolean = groupAssistantParts(parts).isNotEmpty()

internal fun contextToolSummary(parts: List<OpenCodeMessagePart>): ContextToolSummary = ContextToolSummary(
    read = parts.count { it.tool == "read" },
    search = parts.count { it.tool == "glob" || it.tool == "grep" },
    list = parts.count { it.tool == "list" },
)

internal fun contextToolDisplay(part: OpenCodeMessagePart): ContextToolDisplay {
    val input = part.toolInput
    val running = part.isPendingTool()
    return when (part.tool) {
        "read" -> ContextToolDisplay(
            title = UiText.Resource(R.string.tool_read),
            subtitle = input.firstValue("filePath", "path")?.fileName(),
            args = listOfNotNull(input.arg("offset"), input.arg("limit")),
            running = running,
        )
        "list" -> ContextToolDisplay(
            title = UiText.Resource(R.string.tool_list),
            subtitle = input.firstValue("path", "directory"),
            args = emptyList(),
            running = running,
        )
        "glob" -> ContextToolDisplay(
            title = UiText.Raw("Glob"),
            subtitle = input.firstValue("path", "directory", "cwd"),
            args = listOfNotNull(input.arg("pattern")),
            running = running,
        )
        "grep" -> ContextToolDisplay(
            title = UiText.Raw("Grep"),
            subtitle = input.firstValue("path", "directory", "cwd"),
            args = listOfNotNull(input.arg("pattern"), input.arg("include")),
            running = running,
        )
        else -> ContextToolDisplay(
            title = part.tool
                ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                ?.let(UiText::Raw)
                ?: UiText.Resource(R.string.tool_generic),
            subtitle = null,
            args = emptyList(),
            running = running,
        )
    }
}

internal fun shellToolDisplay(part: OpenCodeMessagePart): ShellToolDisplay {
    val command = part.toolInput.firstValue("command")
        ?: part.toolMetadata.firstValue("command")
        ?: ""
    val output = (part.toolOutput?.takeIf { it.isNotBlank() }
        ?: part.toolMetadata.firstValue("output"))
        ?.normalizeShellOutput()
    val error = part.toolError
        ?.takeIf { it.isNotBlank() }
        ?.normalizeShellOutput()
    return ShellToolDisplay(
        command = command,
        output = output,
        error = error,
        pending = part.isPendingTool(),
    )
}

internal fun patchToolDisplay(part: OpenCodeMessagePart): PatchToolDisplay = PatchToolDisplay(
    files = part.patchFiles(),
    pending = part.isPendingTool(),
)

internal fun skillToolDisplay(part: OpenCodeMessagePart): SkillToolDisplay {
    val title = part.inputPrimitiveMap().firstValue("name")
        ?: part.toolMetadata.firstValue("name")
        ?: ""
    return SkillToolDisplay(
        title = title
            .takeIf { it.isNotBlank() }
            ?.capitalizeToolTitle()
            ?.let(UiText::Raw)
            ?: UiText.Resource(R.string.tool_skill),
        pending = part.isPendingTool(),
    )
}

internal fun questionToolDisplay(part: OpenCodeMessagePart): QuestionToolDisplay {
    val dismissed = part.isDismissedQuestionError()
    val questions = part.questionTexts()
    val answerRows = part.questionAnswerRows()
    val count = questions.size.coerceAtLeast(answerRows.size)
    val answers = (0 until count).map { index ->
        QuestionAnswerDisplay(
            question = questions.getOrNull(index)?.let(UiText::Raw)
                ?: UiText.Resource(R.string.question_fallback, listOf(index + 1)),
            answer = answerRows.getOrNull(index)
                ?.joinToString(", ")
                ?.takeIf { it.isNotBlank() }
                ?.let(UiText::Raw)
                ?: UiText.Resource(R.string.question_no_answer),
        )
    }
    return QuestionToolDisplay(
        subtitle = when {
            dismissed -> UiText.Resource(R.string.question_dismissed)
            answers.isNotEmpty() -> UiText.Resource(R.string.question_answered_count, listOf(answers.size))
            questions.isNotEmpty() -> UiText.Resource(R.string.question_count, listOf(questions.size, questions.size))
            else -> UiText.Resource(R.string.tool_question)
        },
        answers = answers,
        dismissed = dismissed,
    )
}

internal fun subagentTaskDisplay(part: OpenCodeMessagePart): SubagentTaskDisplay {
    val input = part.inputPrimitiveMap()
    val metadata = part.toolMetadata
    return SubagentTaskDisplay(
        agent = input.firstValue("subagent_type", "subagentType")
            ?: metadata.firstValue("agent", "subagent_type", "subagentType"),
        description = input.firstValue("description")
            ?: metadata.firstValue("title", "description"),
        childSessionId = metadata.firstValue("sessionId", "sessionID"),
        pending = part.isPendingTool(),
        background = metadata.firstValue("background")?.toBooleanStrictOrNull() == true,
    )
}

internal fun genericToolDisplay(part: OpenCodeMessagePart): GenericToolDisplay {
    val input = part.inputPrimitiveMap()
    val subtitle = GenericLabelKeys.firstNotNullOfOrNull { key -> input[key]?.takeIf { it.isNotBlank() } }
    val args = input.entries
        .asSequence()
        .filterNot { it.key in GenericLabelKeys }
        .filter { it.value.isNotBlank() }
        .take(3)
        .map { (key, value) -> "$key=$value" }
        .toList()
    val tool = part.tool.orEmpty().ifBlank { "tool" }
    return GenericToolDisplay(
        title = UiText.Resource(R.string.tool_called, listOf(tool)),
        subtitle = subtitle,
        args = args,
        pending = part.isPendingTool(),
    )
}

internal fun toolErrorDisplay(part: OpenCodeMessagePart): ToolErrorDisplay {
    val tool = part.tool.orEmpty()
    val cleaned = part.toolError.orEmpty().removePrefix("Error:").trim()
    val prefix = "$tool "
    val tail = if (tool.isNotBlank() && cleaned.startsWith(prefix)) cleaned.removePrefix(prefix) else cleaned
    val parts = tail.split(": ")
    val subtitle = parts.firstOrNull()
        ?.takeIf { parts.size > 1 && it.isNotBlank() }
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        ?.let(UiText::Raw)
        ?: UiText.Resource(R.string.tool_failed)
    val body = if (parts.size > 1) parts.drop(1).joinToString(": ").trim().ifBlank { cleaned } else cleaned
    return ToolErrorDisplay(
        title = when (tool) {
            "apply_patch" -> UiText.Resource(R.string.tool_patch)
            "bash" -> UiText.Raw("Shell")
            "question" -> UiText.Resource(R.string.tool_question)
            else -> tool.takeIf { it.isNotBlank() }?.let(UiText::Raw) ?: UiText.Resource(R.string.tool_generic)
        },
        subtitle = subtitle,
        body = body,
        copyText = cleaned,
    )
}

private fun OpenCodeMessagePart.isContextTool(): Boolean = type == "tool" && tool in ContextToolNames

private fun OpenCodeMessagePart.isShellTool(): Boolean = type == "tool" && tool == "bash"

private fun OpenCodeMessagePart.isPatchTool(): Boolean = type == "tool" && tool == "apply_patch"

private fun OpenCodeMessagePart.isSkillTool(): Boolean = type == "tool" && tool == "skill"

private fun OpenCodeMessagePart.isQuestionTool(): Boolean = type == "tool" && tool == "question"

private fun OpenCodeMessagePart.isTaskTool(): Boolean = type == "tool" && tool == "task"

private fun OpenCodeMessagePart.isRenderableQuestionTool(): Boolean {
    if (isDismissedQuestionError()) return true
    return questionToolDisplay(this).answers.isNotEmpty()
}

private fun OpenCodeMessagePart.isToolError(): Boolean = type == "tool" && !toolError.isNullOrBlank()

private fun OpenCodeMessagePart.isGenericTool(): Boolean = type == "tool" &&
    !tool.isNullOrBlank() &&
    tool !in RegisteredWebTools &&
    tool !in HiddenTools

private fun OpenCodeMessagePart.isPendingTool(): Boolean = stateStatus == "pending" || stateStatus == "running"

private fun OpenCodeMessagePart.patchFiles(): List<PatchFileDisplay> {
    val files = metadataObject()?.get("files")?.jsonArrayOrNull()
        ?: toolMetadata["files"]?.jsonArrayOrNull()
        ?: return emptyList()
    return files.mapNotNull { element ->
        val file = element.jsonObjectOrNull() ?: return@mapNotNull null
        val filePath = file.string("filePath") ?: return@mapNotNull null
        val relativePath = file.string("relativePath") ?: filePath
        val diff = (file.string("patch") ?: file.string("diff"))
            ?.replace("\r\n", "\n")
            ?.replace("\r", "\n")
        PatchFileDisplay(
            filePath = filePath,
            relativePath = relativePath,
            type = file.string("type") ?: "update",
            additions = file.int("additions") ?: 0,
            deletions = file.int("deletions") ?: 0,
            movePath = file.string("movePath"),
            diff = diff,
        )
    }
}

private fun OpenCodeMessagePart.questionTexts(): List<String> {
    val questions = inputObject()?.get("questions")?.jsonArrayOrNull()
        ?: toolInput["questions"]?.jsonArrayOrNull()
        ?: return emptyList()
    return questions.mapNotNull { element ->
        val item = element.jsonObjectOrNull() ?: return@mapNotNull null
        item.string("question")
            ?: item.string("header")
    }
}

private fun OpenCodeMessagePart.questionAnswerRows(): List<List<String>> {
    val rows = metadataObject()?.get("answers")?.jsonArrayOrNull()
        ?: toolMetadata["answers"]?.jsonArrayOrNull()
        ?: return emptyList()
    return rows.map { row ->
        row.jsonArrayOrNull()
            ?.mapNotNull { answer -> answer.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() } }
            .orEmpty()
    }
}

private fun OpenCodeMessagePart.metadataObject(): JsonObject? = toolMetadataJson?.jsonObjectOrNull()

private fun OpenCodeMessagePart.inputObject(): JsonObject? = toolInputJson?.jsonObjectOrNull()

private fun OpenCodeMessagePart.isDismissedQuestionError(): Boolean = toolError
    ?.contains("dismissed this question", ignoreCase = true) == true

private fun OpenCodeMessagePart.inputPrimitiveMap(): Map<String, String> = toolInputJson
    ?.jsonObjectOrNull()
    ?.primitiveStringMap()
    ?: toolInput.filterValues { value -> !value.trimStart().startsWith("[") && !value.trimStart().startsWith("{") }

private fun Map<String, String>.firstValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    get(key)?.takeIf { it.isNotBlank() }
}

private fun Map<String, String>.arg(name: String): String? = get(name)
    ?.takeIf { it.isNotBlank() }
    ?.let { "$name=$it" }

private fun String.fileName(): String = trimEnd('/', '\\')
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .ifBlank { this }

private fun String.directoryName(): String? {
    val normalized = replace('\\', '/').trimEnd('/')
    val directory = normalized.substringBeforeLast('/', missingDelimiterValue = "")
    return directory.takeIf { it.isNotBlank() }?.let { "$it/" }
}

private fun String.capitalizeToolTitle(): String = splitToSequence(' ')
    .joinToString(" ") { word ->
        word.splitToSequence('-')
            .joinToString("-") { segment ->
                segment.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
    }

private fun String.normalizeShellOutput(): String = replace("\r\n", "\n")
    .replace("\r", "\n")
    .replace(AnsiEscapeRegex, "")

private val AnsiEscapeRegex = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")

private fun String.jsonObjectOrNull(): JsonObject? = jsonElementOrNull()?.jsonObjectOrNull()

private fun String.jsonArrayOrNull(): JsonArray? = jsonElementOrNull() as? JsonArray

private fun String.jsonElementOrNull(): JsonElement? = runCatching { ToolJson.parseToJsonElement(this) }.getOrNull()

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitiveOrNull()?.contentOrNull

private fun JsonObject.int(name: String): Int? = string(name)?.toIntOrNull()

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = runCatching { jsonPrimitive }.getOrNull()

private fun JsonObject.primitiveStringMap(): Map<String, String> = entries.mapNotNull { (key, value) ->
    val primitive = value as? JsonPrimitive ?: return@mapNotNull null
    val text = primitive.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    key to text
}.toMap()
