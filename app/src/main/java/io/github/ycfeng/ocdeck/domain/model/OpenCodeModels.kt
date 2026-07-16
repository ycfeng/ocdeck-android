package io.github.ycfeng.ocdeck.domain.model

data class OpenCodePathInfo(
    val home: String?,
    val state: String?,
    val config: String?,
    val worktree: String?,
    val directory: String,
) {
    override fun toString(): String =
        "OpenCodePathInfo(home=${redactedIfPresent(home)}, state=${redactedIfPresent(state)}, " +
            "config=${redactedIfPresent(config)}, worktree=${redactedIfPresent(worktree)}, " +
            "directory=$REDACTED)"
}

data class OpenCodeSession(
    val id: String,
    val title: String,
    val normalizedDirectory: String,
    val path: String?,
    val parentId: String?,
    val agent: String?,
    val modelLabel: String?,
    val updatedAt: Long?,
    val archivedAt: Long?,
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val modelVariant: String? = null,
    val createdAt: Long? = null,
    val cost: Double? = null,
    val tokens: OpenCodeSessionTokens? = null,
    val revert: OpenCodeSessionRevert? = null,
) {
    override fun toString(): String =
        "OpenCodeSession(id=$REDACTED, title=$REDACTED, normalizedDirectory=$REDACTED, " +
            "path=${redactedIfPresent(path)}, parentId=${redactedIfPresent(parentId)}, " +
            "agentPresent=${agent != null}, modelPresent=${modelId != null || modelLabel != null}, " +
            "created=${createdAt != null}, updated=${updatedAt != null}, archived=${archivedAt != null}, " +
            "tokensPresent=${tokens != null}, revertPresent=${revert != null})"
}

data class OpenCodeSessionRevert(
    val messageId: String,
    val partId: String? = null,
) {
    override fun toString(): String =
        "OpenCodeSessionRevert(messageId=$REDACTED, partId=${redactedIfPresent(partId)})"
}

data class OpenCodeSessionTokens(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    val total: Long
        get() = input + output + reasoning + cacheRead + cacheWrite
}

data class OpenCodeSessionStatus(
    val type: String,
)

data class OpenCodeMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val parts: List<OpenCodeMessagePart> = emptyList(),
    val parentId: String? = null,
    val agent: String? = null,
    val modelLabel: String? = null,
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val modelVariant: String? = null,
    val createdAt: Long? = null,
    val completedAt: Long? = null,
    val tokens: OpenCodeSessionTokens? = null,
    val isOptimistic: Boolean = false,
    val error: String? = null,
) {
    override fun toString(): String =
        "OpenCodeMessage(id=$REDACTED, sessionId=$REDACTED, role=$role, " +
            "textLength=${text.length}, partCount=${parts.size}, parentId=${redactedIfPresent(parentId)}, " +
            "agentPresent=${agent != null}, modelPresent=${modelId != null || modelLabel != null}, " +
            "created=${createdAt != null}, completed=${completedAt != null}, tokensPresent=${tokens != null}, " +
            "isOptimistic=$isOptimistic, errorPresent=${error != null})"
}

data class OpenCodeCommentSelection(
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
)

enum class OpenCodeCommentOrigin {
    File,
    Review,
}

data class OpenCodeMessageComment(
    val path: String,
    val selection: OpenCodeCommentSelection?,
    val comment: String,
    val preview: String? = null,
    val origin: OpenCodeCommentOrigin? = null,
) {
    override fun toString(): String =
        "OpenCodeMessageComment(path=$REDACTED, selectionPresent=${selection != null}, " +
            "commentLength=${comment.length}, previewPresent=${preview != null}, origin=$origin)"
}

data class OpenCodeMessagePart(
    val id: String,
    val messageId: String,
    val sessionId: String,
    val type: String,
    val text: String?,
    val synthetic: Boolean,
    val filename: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val tool: String? = null,
    val stateStatus: String? = null,
    val toolInput: Map<String, String> = emptyMap(),
    val toolInputJson: String? = null,
    val toolMetadata: Map<String, String> = emptyMap(),
    val toolMetadataJson: String? = null,
    val toolOutput: String? = null,
    val toolError: String? = null,
    val opencodeComment: OpenCodeMessageComment? = null,
) {
    override fun toString(): String =
        "OpenCodeMessagePart(id=$REDACTED, messageId=$REDACTED, sessionId=$REDACTED, type=$type, " +
            "textPresent=${text != null}, synthetic=$synthetic, filename=${redactedIfPresent(filename)}, " +
            "mimePresent=${mime != null}, url=${redactedIfPresent(url)}, toolPresent=${tool != null}, " +
            "stateStatusPresent=${stateStatus != null}, toolInputCount=${toolInput.size}, " +
            "toolInputJsonPresent=${toolInputJson != null}, toolMetadataCount=${toolMetadata.size}, " +
            "toolMetadataJsonPresent=${toolMetadataJson != null}, toolOutputPresent=${toolOutput != null}, " +
            "toolErrorPresent=${toolError != null}, commentPresent=${opencodeComment != null})"
}

data class OpenCodeMessageBundle(
    val messages: List<OpenCodeMessage>,
    val parts: List<OpenCodeMessagePart>,
) {
    override fun toString(): String =
        "OpenCodeMessageBundle(messageCount=${messages.size}, partCount=${parts.size})"
}

data class OpenCodeDiffFile(
    val file: String,
    val oldFile: String? = null,
    val status: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null,
) {
    override fun toString(): String =
        "OpenCodeDiffFile(file=$REDACTED, oldFile=${redactedIfPresent(oldFile)}, " +
            "statusPresent=${status != null}, additions=$additions, deletions=$deletions, " +
            "patchPresent=${patch != null})"
}

data class OpenCodePermissionRequest(
    val id: String,
    val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val always: List<String> = emptyList(),
    val tool: OpenCodePermissionToolRef? = null,
) {
    override fun toString(): String =
        "OpenCodePermissionRequest(id=$REDACTED, sessionId=$REDACTED, " +
            "permissionPresent=${permission.isNotEmpty()}, patternCount=${patterns.size}, " +
            "alwaysCount=${always.size}, toolPresent=${tool != null})"
}

data class OpenCodePermissionToolRef(
    val messageId: String?,
    val callId: String?,
) {
    override fun toString(): String =
        "OpenCodePermissionToolRef(messageId=${redactedIfPresent(messageId)}, " +
            "callId=${redactedIfPresent(callId)})"
}

data class OpenCodeQuestionRequest(
    val id: String,
    val sessionId: String,
    val questions: List<OpenCodeQuestionInfo>,
    val tool: OpenCodeQuestionToolRef? = null,
) {
    override fun toString(): String =
        "OpenCodeQuestionRequest(id=$REDACTED, sessionId=$REDACTED, " +
            "questionCount=${questions.size}, toolPresent=${tool != null})"
}

data class OpenCodeQuestionInfo(
    val header: String?,
    val question: String,
    val options: List<OpenCodeQuestionOption>,
    val multiple: Boolean = false,
    val custom: Boolean = true,
) {
    override fun toString(): String =
        "OpenCodeQuestionInfo(headerPresent=${header != null}, questionPresent=${question.isNotEmpty()}, " +
            "optionCount=${options.size}, multiple=$multiple, custom=$custom)"
}

data class OpenCodeQuestionOption(
    val label: String,
    val description: String?,
) {
    override fun toString(): String =
        "OpenCodeQuestionOption(labelPresent=${label.isNotEmpty()}, descriptionPresent=${description != null})"
}

data class OpenCodeQuestionToolRef(
    val messageId: String?,
    val callId: String?,
) {
    override fun toString(): String =
        "OpenCodeQuestionToolRef(messageId=${redactedIfPresent(messageId)}, " +
            "callId=${redactedIfPresent(callId)})"
}

data class OpenCodeAgent(
    val id: String,
    val name: String,
    val description: String?,
) {
    override fun toString(): String =
        "OpenCodeAgent(id=$REDACTED, name=$REDACTED, descriptionPresent=${description != null})"
}

data class OpenCodeModel(
    val providerId: String,
    val providerConfigKey: String = providerId,
    val modelId: String,
    val name: String,
    val providerName: String = providerId,
    val isFree: Boolean = false,
    val isConnected: Boolean = false,
    val variants: List<String> = emptyList(),
    val isEnabled: Boolean = true,
    val contextLimit: Long? = null,
) {
    override fun toString(): String =
        "OpenCodeModel(providerId=$REDACTED, providerConfigKey=$REDACTED, modelId=$REDACTED, " +
            "name=$REDACTED, providerName=$REDACTED, isFree=$isFree, isConnected=$isConnected, " +
            "variantCount=${variants.size}, isEnabled=$isEnabled, contextLimitPresent=${contextLimit != null})"
}

data class OpenCodeModelSettingsGroup(
    val providerId: String,
    val providerName: String,
    val models: List<OpenCodeModel>,
) {
    override fun toString(): String =
        "OpenCodeModelSettingsGroup(providerId=$REDACTED, providerName=$REDACTED, modelCount=${models.size})"
}

data class OpenCodeCommand(
    val id: String,
    val name: String,
    val description: String?,
    val source: String?,
) {
    override fun toString(): String =
        "OpenCodeCommand(id=$REDACTED, name=$REDACTED, descriptionPresent=${description != null}, " +
            "sourcePresent=${source != null})"
}

data class PromptCapabilities(
    val models: List<OpenCodeModel> = emptyList(),
    val agents: List<OpenCodeAgent> = emptyList(),
    val commands: List<OpenCodeCommand> = emptyList(),
    val serverDefaultModels: List<PromptModelSelection> = emptyList(),
    val isLoaded: Boolean = false,
    val revision: Long = 0L,
) {
    override fun toString(): String =
        "PromptCapabilities(modelCount=${models.size}, agentCount=${agents.size}, " +
            "commandCount=${commands.size}, serverDefaultModelCount=${serverDefaultModels.size}, " +
            "isLoaded=$isLoaded, revision=$revision)"
}

data class OpenCodeMcp(
    val name: String,
    val status: String?,
    val error: String? = null,
) {
    override fun toString(): String =
        "OpenCodeMcp(name=$REDACTED, statusPresent=${status != null}, errorPresent=${error != null})"
}

data class OpenCodeLsp(
    val id: String,
    val name: String?,
    val status: String?,
    val root: String? = null,
) {
    override fun toString(): String =
        "OpenCodeLsp(id=$REDACTED, name=${redactedIfPresent(name)}, statusPresent=${status != null}, " +
            "root=${redactedIfPresent(root)})"
}

data class OpenCodePlugin(
    val name: String,
) {
    override fun toString(): String = "OpenCodePlugin(name=$REDACTED)"
}

data class OpenCodeDirectorySuggestion(
    val directory: String,
    val name: String,
    val parent: String?,
    val source: OpenCodeDirectorySuggestionSource,
) {
    override fun toString(): String =
        "OpenCodeDirectorySuggestion(directory=$REDACTED, name=$REDACTED, " +
            "parent=${redactedIfPresent(parent)}, source=$source)"
}

enum class OpenCodeDirectorySuggestionSource {
    Browse,
    Search,
}

data class OpenCodeProjectSnapshot(
    val serverId: String,
    val normalizedDirectory: String,
    val workspace: String? = null,
    val project: ProjectRef? = null,
    val pathInfo: OpenCodePathInfo?,
    val sessions: List<OpenCodeSession>,
    val statuses: Map<String, OpenCodeSessionStatus>,
    val providerCount: Int,
    val models: List<OpenCodeModel>,
    val agents: List<OpenCodeAgent>,
    val commands: List<OpenCodeCommand>,
    val promptCapabilities: PromptCapabilities,
    val mcps: List<OpenCodeMcp>,
    val lsps: List<OpenCodeLsp>,
    val plugins: List<OpenCodePlugin>,
    val commandCount: Int,
    val permissionCount: Int,
    val questionCount: Int,
    val permissions: List<OpenCodePermissionRequest> = emptyList(),
    val questions: List<OpenCodeQuestionRequest> = emptyList(),
) {
    override fun toString(): String =
        "OpenCodeProjectSnapshot(serverId=$REDACTED, normalizedDirectory=$REDACTED, " +
            "workspace=${redactedIfPresent(workspace)}, projectPresent=${project != null}, " +
            "pathInfoPresent=${pathInfo != null}, sessionCount=${sessions.size}, statusCount=${statuses.size}, " +
            "providerCount=$providerCount, modelCount=${models.size}, agentCount=${agents.size}, " +
            "commandListCount=${commands.size}, promptCapabilitiesRevision=${promptCapabilities.revision}, " +
            "mcpCount=${mcps.size}, lspCount=${lsps.size}, pluginCount=${plugins.size}, " +
            "commandCount=$commandCount, permissionCount=$permissionCount, questionCount=$questionCount, " +
            "permissionListCount=${permissions.size}, questionListCount=${questions.size})"
}

data class PromptModelSelection(
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
) {
    override fun toString(): String =
        "PromptModelSelection(providerId=$REDACTED, modelId=$REDACTED, " +
            "variant=${redactedIfPresent(variant)})"
}

private const val REDACTED = "<redacted>"

private fun redactedIfPresent(value: Any?): String = if (value == null) "null" else REDACTED
