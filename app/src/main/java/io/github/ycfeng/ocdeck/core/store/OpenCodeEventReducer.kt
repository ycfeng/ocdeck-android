package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.network.toOpenCodeMessageComment
import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.domain.model.OpenCodePermissionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodePermissionToolRef
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionInfo
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionOption
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionToolRef
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionRevert
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionStatus
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionTokens
import io.github.ycfeng.ocdeck.domain.model.ProjectIcon
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

class OpenCodeEventReducer(
    private val redactor: Redactor = Redactor(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun reduce(state: OpenCodeProjectState, event: JsonObject): OpenCodeProjectState {
        val payload = event.payloadObject()
        val type = payload.string("type") ?: event.string("type") ?: return state
        val properties = payload.objectOrNull("properties")
            ?: payload.objectOrNull("data")
            ?: payload
        val lowerType = type.lowercase(Locale.US)

        return when {
            lowerType.contains("project.updated") -> {
                val projectObject = properties.objectOrNull("info")
                    ?: properties.objectOrNull("project")
                    ?: properties
                state.copy(project = projectObject.toProjectRef(state))
            }
            lowerType.contains("session") && lowerType.contains("deleted") -> {
                val sessionId = properties.string("sessionID") ?: properties.string("id") ?: return state
                val messageIds = state.messagesBySession[sessionId].orEmpty().mapTo(mutableSetOf(), OpenCodeMessage::id)
                val nextPermissions = state.permissionsBySession - sessionId
                val nextQuestions = state.questionsBySession - sessionId
                state.copy(
                    sessions = state.sessions.filterNot { it.id == sessionId },
                    messagesBySession = state.messagesBySession - sessionId,
                    partsByMessage = state.partsByMessage - messageIds,
                    permissionsBySession = nextPermissions,
                    questionsBySession = nextQuestions,
                    statuses = state.statuses - sessionId,
                    permissionCount = nextPermissions.totalPermissionCount(),
                    questionCount = nextQuestions.totalQuestionCount(),
                )
            }
            lowerType.contains("session") && lowerType.contains("idle") -> {
                val sessionId = properties.string("sessionID") ?: properties.string("id") ?: return state
                if (state.isChildSession(sessionId)) return state
                state.copy(
                    notifications = state.notifications.append(
                        TurnCompleteNotification(
                            directory = state.normalizedDirectory,
                            sessionId = sessionId,
                            timeMillis = nowMillis(),
                            viewed = state.activeSessionId == sessionId,
                        ),
                    ),
                )
            }
            lowerType.contains("session") && lowerType.contains("error") -> {
                val sessionId = properties.string("sessionID") ?: properties.string("id")
                if (sessionId != null && state.isChildSession(sessionId)) return state
                state.copy(
                    notifications = state.notifications.append(
                        ErrorNotification(
                            directory = state.normalizedDirectory,
                            sessionId = sessionId,
                            timeMillis = nowMillis(),
                            viewed = sessionId != null && state.activeSessionId == sessionId,
                            summary = properties.errorSummary(redactor),
                        ),
                    ),
                )
            }
            lowerType.contains("session") && (lowerType.contains("created") || lowerType.contains("updated")) -> {
                val sessionObject = properties.objectOrNull("info")
                    ?: properties.objectOrNull("session")
                    ?: properties.objectOrNull("data")
                    ?: properties
                val session = sessionObject.toSession(state.normalizedDirectory) ?: return state
                if (session.archivedAt == null) {
                    state.copy(sessions = (state.sessions.filterNot { it.id == session.id } + session).sortedForDisplay())
                } else {
                    state.copy(sessions = state.sessions.filterNot { it.id == session.id })
                }
            }
            lowerType.contains("status") -> {
                val sessionId = properties.string("sessionID") ?: properties.string("id") ?: return state
                val statusObject = properties.objectOrNull("status") ?: properties
                val status = OpenCodeSessionStatus(statusObject.string("type") ?: "unknown")
                state.copy(statuses = state.statuses + (sessionId to status))
            }
            lowerType.contains("permission") && lowerType.contains("asked") -> {
                val requestObject = properties.objectOrNull("request")
                    ?: properties.objectOrNull("info")
                    ?: properties.objectOrNull("permission")
                    ?: properties
                val request = requestObject.toPermissionRequest() ?: return state
                val nextPermissions = state.permissionsBySession.upsertPermission(request)
                state.copy(
                    permissionsBySession = nextPermissions,
                    permissionCount = nextPermissions.totalPermissionCount(),
                )
            }
            lowerType.contains("permission") && lowerType.contains("replied") -> {
                val requestId = properties.string("requestID")
                    ?: properties.string("permissionID")
                    ?: properties.string("id")
                    ?: return state
                val nextPermissions = state.permissionsBySession.removePermission(
                    sessionId = properties.string("sessionID"),
                    requestId = requestId,
                )
                state.copy(
                    permissionsBySession = nextPermissions,
                    permissionCount = nextPermissions.totalPermissionCount(),
                )
            }
            lowerType.contains("question") && lowerType.contains("asked") -> {
                val requestObject = properties.objectOrNull("request")
                    ?: properties.objectOrNull("info")
                    ?: properties.objectOrNull("question")
                    ?: properties
                val request = requestObject.toQuestionRequest() ?: return state
                val nextQuestions = state.questionsBySession.upsertQuestion(request)
                state.copy(
                    questionsBySession = nextQuestions,
                    questionCount = nextQuestions.totalQuestionCount(),
                )
            }
            lowerType.contains("question") && (lowerType.contains("replied") || lowerType.contains("rejected")) -> {
                val requestId = properties.string("requestID") ?: properties.string("id") ?: return state
                val nextQuestions = state.questionsBySession.removeQuestion(
                    sessionId = properties.string("sessionID"),
                    requestId = requestId,
                )
                state.copy(
                    questionsBySession = nextQuestions,
                    questionCount = nextQuestions.totalQuestionCount(),
                )
            }
            lowerType.contains("message") && lowerType.contains("removed") && !lowerType.contains("part") -> {
                val messageId = properties.string("messageID") ?: properties.string("id") ?: return state
                val nextMessages = state.messagesBySession.mapValues { (_, messages) -> messages.filterNot { it.id == messageId } }
                state.copy(messagesBySession = nextMessages, partsByMessage = state.partsByMessage - messageId)
            }
            lowerType.contains("message") && lowerType.contains("updated") && !lowerType.contains("part") -> {
                val info = properties.objectOrNull("info") ?: properties.objectOrNull("message") ?: properties
                val parts = properties.arrayObjects("parts").mapNotNull { it.toPart(redactor) }
                val incomingMessage = info.toMessage(parts, redactor) ?: return state
                val message = if (parts.isEmpty()) {
                    incomingMessage.withExistingTextIfEmpty(state.findMessage(incomingMessage.id))
                } else {
                    incomingMessage
                }
                val nextParts = if (parts.isEmpty()) state.partsByMessage else state.partsByMessage + (message.id to parts)
                state.copy(
                    messagesBySession = state.messagesBySession.upsertMessage(message),
                    partsByMessage = nextParts,
                )
            }
            lowerType.contains("part") && lowerType.contains("removed") -> {
                val messageId = properties.string("messageID") ?: return state
                val partId = properties.string("partID") ?: properties.string("id") ?: return state
                val parts = state.partsByMessage[messageId].orEmpty().filterNot { it.id == partId }
                val message = state.findMessage(messageId)?.withText(parts.renderText())
                state.copy(
                    partsByMessage = state.partsByMessage + (messageId to parts),
                    messagesBySession = if (message == null) state.messagesBySession else state.messagesBySession.upsertMessage(message),
                )
            }
            lowerType.contains("part") && lowerType.contains("delta") -> {
                val messageId = properties.string("messageID") ?: return state
                val partId = properties.string("partID") ?: properties.string("id") ?: return state
                val field = properties.string("field") ?: "text"
                val delta = properties.string("delta") ?: return state
                if (field != "text") return state.confirmMessage(messageId)
                val currentParts = state.partsByMessage[messageId].orEmpty()
                val parts = currentParts.map { part ->
                    if (part.id == partId) part.copy(text = part.text.orEmpty() + delta) else part
                }
                val message = state.findMessage(messageId)?.withText(parts.renderText())
                state.copy(
                    partsByMessage = state.partsByMessage + (messageId to parts),
                    messagesBySession = if (message == null) state.messagesBySession else state.messagesBySession.upsertMessage(message),
                )
            }
            lowerType.contains("part") && lowerType.contains("updated") -> {
                val part = (properties.objectOrNull("part") ?: properties.objectOrNull("info") ?: properties)
                    .toPart(redactor)
                    ?: return state
                val currentParts = state.partsByMessage[part.messageId].orEmpty()
                val parts = (currentParts.filterNot { it.id == part.id } + part).sortedBy { it.id }
                val message = state.findMessage(part.messageId)
                    ?.withText(parts.renderText())
                    ?: OpenCodeMessage(
                        id = part.messageId,
                        sessionId = part.sessionId,
                        role = "assistant",
                        text = parts.renderText(),
                    )
                state.copy(
                    partsByMessage = state.partsByMessage + (part.messageId to parts),
                    messagesBySession = state.messagesBySession.upsertMessage(message),
                )
            }
            else -> state
        }
    }
}

private fun JsonObject.toProjectRef(state: OpenCodeProjectState): ProjectRef {
    val icon = objectOrNull("icon")
    return ProjectRef(
        normalizedDirectory = state.normalizedDirectory,
        projectId = string("id") ?: string("projectID") ?: state.project?.projectId,
        displayName = string("name")?.takeIf { it.isNotBlank() }
            ?: state.project?.displayName
            ?: state.normalizedDirectory.displayName(),
        vcs = string("vcs") ?: state.project?.vcs,
        icon = if (icon == null) {
            state.project?.icon
        } else {
            ProjectIcon(
                color = icon.string("color"),
                url = icon.string("url"),
            )
        },
    )
}

private fun JsonObject.payloadObject(): JsonObject {
    val payload = objectOrNull("payload") ?: this
    if (payload.string("type") == "sync") {
        return payload.objectOrNull("syncEvent") ?: payload
    }
    return payload
}

private fun JsonObject.toSession(defaultDirectory: String): OpenCodeSession? {
    val id = string("id") ?: string("sessionID") ?: return null
    val model = objectOrNull("model")
    val providerId = model?.string("providerID")
    val modelId = model?.string("id") ?: model?.string("modelID")
    val variant = model?.string("variant")
    val modelLabel = model?.let {
        listOfNotNull(providerId, modelId, variant)
            .joinToString("/")
            .ifBlank { null }
    }
    val time = objectOrNull("time")
    return OpenCodeSession(
        id = id,
        title = string("title") ?: "Untitled",
        normalizedDirectory = string("directory") ?: defaultDirectory,
        path = string("path"),
        parentId = string("parentID"),
        agent = string("agent"),
        modelLabel = modelLabel,
        updatedAt = time?.long("updated") ?: long("updated"),
        archivedAt = time?.long("archived") ?: long("archived"),
        modelProviderId = providerId,
        modelId = modelId,
        modelVariant = variant,
        createdAt = time?.long("created") ?: long("created"),
        cost = double("cost"),
        tokens = objectOrNull("tokens")?.toSessionTokens(),
        revert = objectOrNull("revert")?.let { revert ->
            revert.string("messageID")?.let { messageId ->
                OpenCodeSessionRevert(
                    messageId = messageId,
                    partId = revert.string("partID"),
                )
            }
        },
    )
}

private fun JsonObject.toSessionTokens(): OpenCodeSessionTokens {
    val cache = objectOrNull("cache")
    return OpenCodeSessionTokens(
        input = long("input") ?: 0,
        output = long("output") ?: 0,
        reasoning = long("reasoning") ?: 0,
        cacheRead = cache?.long("read") ?: 0,
        cacheWrite = cache?.long("write") ?: 0,
    )
}

private fun JsonObject.toQuestionRequest(): OpenCodeQuestionRequest? {
    val id = string("id") ?: string("requestID") ?: return null
    val sessionId = string("sessionID") ?: return null
    return OpenCodeQuestionRequest(
        id = id,
        sessionId = sessionId,
        questions = arrayObjects("questions").mapNotNull { it.toQuestionInfo() },
        tool = objectOrNull("tool")?.let { tool ->
            OpenCodeQuestionToolRef(
                messageId = tool.string("messageID"),
                callId = tool.string("callID"),
            )
        },
    )
}

private fun JsonObject.toPermissionRequest(): OpenCodePermissionRequest? {
    val id = string("id") ?: string("requestID") ?: string("permissionID") ?: return null
    val sessionId = string("sessionID") ?: return null
    return OpenCodePermissionRequest(
        id = id,
        sessionId = sessionId,
        permission = string("permission") ?: string("action") ?: "unknown",
        patterns = stringArray("patterns").ifEmpty { stringArray("resources") },
        always = stringArray("always"),
        tool = objectOrNull("tool")?.let { tool ->
            OpenCodePermissionToolRef(
                messageId = tool.string("messageID"),
                callId = tool.string("callID"),
            )
        },
    )
}

private fun JsonObject.toQuestionInfo(): OpenCodeQuestionInfo? {
    val text = string("question")?.takeIf { it.isNotBlank() } ?: return null
    return OpenCodeQuestionInfo(
        header = string("header")?.takeIf { it.isNotBlank() },
        question = text,
        options = arrayObjects("options").mapNotNull { it.toQuestionOption() },
        multiple = boolean("multiple") == true,
        custom = boolean("custom") ?: true,
    )
}

private fun JsonObject.toQuestionOption(): OpenCodeQuestionOption? {
    val label = string("label")?.takeIf { it.isNotBlank() } ?: return null
    return OpenCodeQuestionOption(
        label = label,
        description = string("description")?.takeIf { it.isNotBlank() },
    )
}

private fun JsonObject.toMessage(
    parts: List<OpenCodeMessagePart>,
    redactor: Redactor,
): OpenCodeMessage? {
    val id = string("id") ?: string("messageID") ?: return null
    val sessionId = string("sessionID") ?: parts.firstOrNull()?.sessionId ?: return null
    val model = objectOrNull("model")
    val providerId = string("providerID") ?: model?.string("providerID")
    val modelId = string("modelID") ?: model?.string("modelID") ?: model?.string("id")
    val variant = string("variant") ?: model?.string("variant")
    val modelLabel = listOfNotNull(providerId, modelId, variant).joinToString("/").ifBlank { null }
    val time = objectOrNull("time")
    return OpenCodeMessage(
        id = id,
        sessionId = sessionId,
        role = string("role") ?: "assistant",
        text = parts.renderText(),
        parentId = string("parentID"),
        agent = string("agent") ?: string("mode"),
        modelLabel = modelLabel,
        modelProviderId = providerId,
        modelId = modelId,
        modelVariant = variant,
        createdAt = time?.long("created") ?: long("created"),
        completedAt = time?.long("completed") ?: long("completed"),
        tokens = objectOrNull("tokens")?.toSessionTokens(),
        error = get("error").redactedError(redactor),
    )
}

private fun JsonObject.errorSummary(redactor: Redactor): String = redactor.redact(
    get("error").redactedError(redactor)
        ?: string("message")
        ?: string("description")
        ?: redactor.redact(this).toString(),
)

private fun OpenCodeProjectState.isChildSession(sessionId: String): Boolean = sessions
    .firstOrNull { it.id == sessionId }
    ?.parentId
    ?.isNotBlank() == true

private fun JsonObject.toPart(redactor: Redactor): OpenCodeMessagePart? {
    val id = string("id") ?: string("partID") ?: return null
    val messageId = string("messageID") ?: return null
    val sessionId = string("sessionID") ?: return null
    return OpenCodeMessagePart(
        id = id,
        messageId = messageId,
        sessionId = sessionId,
        type = string("type") ?: "unknown",
        text = string("text"),
        synthetic = boolean("synthetic") ?: false,
        filename = string("filename"),
        mime = string("mime"),
        url = string("url"),
        tool = string("tool"),
        stateStatus = objectOrNull("state")?.string("status"),
        toolInput = objectOrNull("state")?.objectOrNull("input")?.toStringMap().orEmpty(),
        toolInputJson = objectOrNull("state")?.objectOrNull("input")?.toString(),
        toolMetadata = objectOrNull("state")?.objectOrNull("metadata")?.toStringMap().orEmpty(),
        toolMetadataJson = objectOrNull("state")?.objectOrNull("metadata")?.toString(),
        toolOutput = objectOrNull("state")?.get("output")?.stringOrJson(),
        toolError = objectOrNull("state")?.get("error").redactedError(redactor),
        opencodeComment = get("metadata").toOpenCodeMessageComment(),
    )
}

private fun JsonElement?.redactedError(redactor: Redactor): String? = this
    ?.let(redactor::redact)
    ?.stringOrJson()
    ?.let(redactor::redact)

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun String.displayName(): String = trimEnd('/', '\\')
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .ifBlank { this }

private fun JsonObject.long(name: String): Long? = string(name)?.toLongOrNull()

private fun JsonObject.double(name: String): Double? = string(name)?.toDoubleOrNull()

private fun JsonObject.boolean(name: String): Boolean? = string(name)?.toBooleanStrictOrNull()

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name]?.jsonObjectOrNull()

private fun JsonObject.toStringMap(): Map<String, String> = entries.mapNotNull { (key, value) ->
    val text = value.stringOrJson()
    text?.let { key to it }
}.toMap()

private fun JsonElement.stringPrimitiveOrNull(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()

private fun JsonElement.stringOrJson(): String? = stringPrimitiveOrNull() ?: toString().takeIf { it.isNotBlank() }

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonObject.arrayObjects(name: String): List<JsonObject> = this[name]
    ?.let { element -> runCatching { element as? kotlinx.serialization.json.JsonArray }.getOrNull() }
    ?.mapNotNull { it.jsonObjectOrNull() }
    .orEmpty()

private fun JsonObject.stringArray(name: String): List<String> = (this[name] as? JsonArray)
    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
    .orEmpty()

private fun List<OpenCodeMessagePart>.renderText(): String = filter { it.type == "text" && !it.synthetic }
    .mapNotNull { it.text }
    .joinToString("\n")

private fun OpenCodeMessage.withText(text: String): OpenCodeMessage = copy(text = text, isOptimistic = false)

private fun OpenCodeMessage.withExistingTextIfEmpty(existing: OpenCodeMessage?): OpenCodeMessage {
    if (text.isNotBlank() || existing == null) return this
    return copy(
        text = existing.text,
        agent = agent ?: existing.agent,
        modelLabel = modelLabel ?: existing.modelLabel,
        modelProviderId = modelProviderId ?: existing.modelProviderId,
        modelId = modelId ?: existing.modelId,
        modelVariant = modelVariant ?: existing.modelVariant,
        createdAt = createdAt ?: existing.createdAt,
        completedAt = completedAt ?: existing.completedAt,
        tokens = tokens ?: existing.tokens,
    )
}

private fun OpenCodeProjectState.confirmMessage(messageId: String): OpenCodeProjectState {
    val message = findMessage(messageId) ?: return this
    return copy(messagesBySession = messagesBySession.upsertMessage(message.copy(isOptimistic = false)))
}

private fun Map<String, List<OpenCodeMessage>>.upsertMessage(message: OpenCodeMessage): Map<String, List<OpenCodeMessage>> {
    val messages = get(message.sessionId).orEmpty().filterNot { it.id == message.id } + message
    return this + (message.sessionId to messages.sortedBy { it.createdAt ?: 0L })
}

private fun Map<String, List<OpenCodePermissionRequest>>.upsertPermission(
    request: OpenCodePermissionRequest,
): Map<String, List<OpenCodePermissionRequest>> {
    val requests = get(request.sessionId).orEmpty().filterNot { it.id == request.id } + request
    return this + (request.sessionId to requests.sortedBy { it.id })
}

private fun Map<String, List<OpenCodePermissionRequest>>.removePermission(
    sessionId: String?,
    requestId: String,
): Map<String, List<OpenCodePermissionRequest>> {
    if (sessionId != null) {
        val nextRequests = get(sessionId).orEmpty().filterNot { it.id == requestId }
        return if (nextRequests.isEmpty()) this - sessionId else this + (sessionId to nextRequests)
    }
    return mapValues { (_, requests) -> requests.filterNot { it.id == requestId } }
        .filterValues { it.isNotEmpty() }
}

private fun Map<String, List<OpenCodeQuestionRequest>>.upsertQuestion(
    request: OpenCodeQuestionRequest,
): Map<String, List<OpenCodeQuestionRequest>> {
    val requests = get(request.sessionId).orEmpty().filterNot { it.id == request.id } + request
    return this + (request.sessionId to requests.sortedBy { it.id })
}

private fun Map<String, List<OpenCodeQuestionRequest>>.removeQuestion(
    sessionId: String?,
    requestId: String,
): Map<String, List<OpenCodeQuestionRequest>> {
    if (sessionId != null) {
        val nextRequests = get(sessionId).orEmpty().filterNot { it.id == requestId }
        return if (nextRequests.isEmpty()) this - sessionId else this + (sessionId to nextRequests)
    }
    return mapValues { (_, requests) -> requests.filterNot { it.id == requestId } }
        .filterValues { it.isNotEmpty() }
}

private fun Map<String, List<OpenCodePermissionRequest>>.totalPermissionCount(): Int = values.sumOf { it.size }

private fun Map<String, List<OpenCodeQuestionRequest>>.totalQuestionCount(): Int = values.sumOf { it.size }

private fun OpenCodeProjectState.findMessage(messageId: String): OpenCodeMessage? = messagesBySession.values
    .asSequence()
    .flatten()
    .firstOrNull { it.id == messageId }

private fun List<OpenCodeSession>.sortedForDisplay(): List<OpenCodeSession> = sortedWith(
    compareByDescending<OpenCodeSession> { it.updatedAt ?: 0L }.thenByDescending { it.id },
)
