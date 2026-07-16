package io.github.ycfeng.ocdeck.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.PUT

interface OpenCodeApi {
    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("global/health")
    suspend fun getGlobalHealth(): ServerHealthDto

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("path")
    suspend fun getPath(
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
    ): PathInfoDto

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("file")
    suspend fun getFileEntries(
        @Query("directory") directory: String,
        @Query("path") path: String = "",
    ): List<FileEntryDto>

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @Streaming
    @GET("file/content")
    suspend fun getFileContent(
        @Query("directory") directory: String,
        @Query("path") path: String,
    ): ResponseBody

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("find/file")
    suspend fun findFiles(
        @Query("directory") directory: String,
        @Query("query") query: String,
        @Query("type") type: String = "directory",
        @Query("limit") limit: Int = 20,
    ): List<String>

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("project/current")
    suspend fun getCurrentProject(
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
    ): ProjectDto

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @PATCH("project/{projectID}")
    suspend fun updateProject(
        @Path("projectID") projectId: String,
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
        @Body body: UpdateProjectRequestDto,
    ): ProjectDto

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("session")
    suspend fun getSessions(
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
        @Query("roots") roots: Boolean? = true,
        @Query("limit") limit: Int? = 100,
    ): List<SessionDto>

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("session/status")
    suspend fun getSessionStatus(
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
    ): Map<String, SessionStatusDto>

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("session")
    suspend fun createSession(
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
        @Body body: CreateSessionRequestDto = CreateSessionRequestDto(),
    ): SessionDto

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @PATCH("session/{sessionID}")
    suspend fun updateSession(
        @Path("sessionID") sessionId: String,
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
        @Body body: UpdateSessionRequestDto,
    ): SessionDto

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.EMPTY_SUCCESS)
    @DELETE("session/{sessionID}")
    suspend fun deleteSession(
        @Path("sessionID") sessionId: String,
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
    ): Response<Unit>

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("session/{sessionID}/diff")
    suspend fun getSessionDiff(
        @Path("sessionID") sessionId: String,
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("vcs/diff")
    suspend fun getVcsDiff(
        @Query("mode") mode: String = "git",
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.EMPTY_SUCCESS)
    @POST("session/{sessionID}/prompt_async")
    suspend fun sendPromptAsync(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String,
        @Header("x-opencode-workspace") workspace: String? = null,
        @Body body: PromptRequestDto,
    ): Response<Unit>

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.EMPTY_SUCCESS)
    @POST("session/{sessionID}/command")
    suspend fun sendCommand(
        @Path("sessionID") sessionId: String,
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
        @Body body: CommandRequestDto,
    ): Response<Unit>

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("session/{sessionID}/abort")
    suspend fun abortSession(
        @Path("sessionID") sessionId: String,
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
    ): Boolean

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("session/{sessionID}/revert")
    suspend fun revertSession(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String,
        @Header("x-opencode-workspace") workspace: String? = null,
        @Body body: RevertSessionRequestDto,
    ): SessionDto

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("session/{sessionID}/unrevert")
    suspend fun unrevertSession(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String,
        @Header("x-opencode-workspace") workspace: String? = null,
    ): SessionDto

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("config")
    suspend fun getConfig(
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @PATCH("config")
    suspend fun updateConfig(
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
        @Body body: JsonElement,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("mcp")
    suspend fun getMcps(
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("mcp/{name}/connect")
    suspend fun connectMcp(
        @Path("name") name: String,
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
    ): Boolean

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("mcp/{name}/disconnect")
    suspend fun disconnectMcp(
        @Path("name") name: String,
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
    ): Boolean

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("lsp")
    suspend fun getLsps(
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("provider")
    suspend fun getProviders(
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("provider/auth")
    suspend fun getProviderAuthMethods(
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @PUT("auth/{providerID}")
    suspend fun putProviderAuth(
        @Path("providerID") providerId: String,
        @Body body: ProviderApiAuthRequestDto,
    ): Boolean

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @DELETE("auth/{providerID}")
    suspend fun deleteProviderAuth(
        @Path("providerID") providerId: String,
    ): Boolean

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @Streaming
    @POST("provider/{providerID}/oauth/authorize")
    suspend fun authorizeProviderOAuth(
        @Path("providerID") providerId: String,
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
        @Body body: ProviderOAuthAuthorizeRequestDto,
    ): ResponseBody

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("provider/{providerID}/oauth/callback")
    suspend fun completeProviderOAuth(
        @Path("providerID") providerId: String,
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
        @Body body: ProviderOAuthCallbackRequestDto,
    ): Boolean

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("global/config")
    suspend fun getGlobalConfig(): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @PATCH("global/config")
    suspend fun updateGlobalConfig(
        @Body body: JsonElement,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("global/dispose")
    suspend fun disposeGlobalInstances(): Boolean

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("agent")
    suspend fun getAgents(
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("command")
    suspend fun getCommands(
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
    ): JsonElement

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("permission")
    suspend fun getPermissions(
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
    ): List<PermissionRequestDto>

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("session/{sessionID}/permissions/{permissionID}")
    suspend fun respondPermission(
        @Path("sessionID") sessionId: String,
        @Path("permissionID") permissionId: String,
        @Header("x-opencode-directory") directory: String,
        @Header("x-opencode-workspace") workspace: String? = null,
        @Body body: PermissionRespondRequestDto,
    ): Boolean

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @GET("question")
    suspend fun getQuestions(
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
    ): List<QuestionRequestDto>

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("question/{requestID}/reply")
    suspend fun replyQuestion(
        @Path("requestID") requestId: String,
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
        @Body body: QuestionReplyRequestDto,
    ): Boolean

    @RetrofitInboundResponse(mode = RetrofitInboundResponseMode.BOUNDED)
    @POST("question/{requestID}/reject")
    suspend fun rejectQuestion(
        @Path("requestID") requestId: String,
        @Query("directory") directory: String,
        @Query("workspace") workspace: String? = null,
    ): Boolean
}

@Serializable
data class ServerHealthDto(
    val healthy: Boolean? = null,
    val version: String? = null,
) {
    override fun toString(): String =
        "ServerHealthDto(healthy=$healthy, versionPresent=${version != null})"
}

@Serializable
data class ProviderApiAuthRequestDto(
    val type: String = "api",
    val key: String,
    val metadata: Map<String, String>? = null,
) {
    override fun toString(): String =
        "ProviderApiAuthRequestDto(type=$type, key=<redacted>, metadataCount=${metadata?.size ?: 0})"
}

@Serializable
data class ProviderOAuthAuthorizeRequestDto(
    val method: Int,
    val inputs: Map<String, String>? = null,
) {
    override fun toString(): String =
        "ProviderOAuthAuthorizeRequestDto(method=$method, inputCount=${inputs?.size ?: 0})"
}

@Serializable
data class ProviderOAuthCallbackRequestDto(
    val method: Int,
    val code: String? = null,
) {
    override fun toString(): String =
        "ProviderOAuthCallbackRequestDto(method=$method, code=${if (code == null) "null" else "<redacted>"})"
}

@Serializable
data class PathInfoDto(
    val home: String? = null,
    val state: String? = null,
    val config: String? = null,
    val worktree: String? = null,
    val directory: String? = null,
) {
    override fun toString(): String =
        "PathInfoDto(home=${redactedIfPresent(home)}, state=${redactedIfPresent(state)}, " +
            "config=${redactedIfPresent(config)}, worktree=${redactedIfPresent(worktree)}, " +
            "directory=${redactedIfPresent(directory)})"
}

@Serializable
data class FileEntryDto(
    val name: String,
    val path: String,
    val absolute: String,
    val type: String,
    val ignored: Boolean,
) {
    override fun toString(): String =
        "FileEntryDto(name=$REDACTED, path=$REDACTED, absolute=$REDACTED, " +
            "type=$type, ignored=$ignored)"
}

@Serializable
data class FileContentDto(
    val type: String,
    val content: String,
    val encoding: String? = null,
    val mimeType: String? = null,
) {
    override fun toString(): String =
        "FileContentDto(type=$type, contentLength=${content.length}, " +
            "encodingPresent=${encoding != null}, mimeTypePresent=${mimeType != null})"
}

@Serializable
data class ProjectDto(
    val id: String,
    val worktree: String,
    val vcs: String? = null,
    val name: String? = null,
    val icon: ProjectIconDto? = null,
) {
    override fun toString(): String =
        "ProjectDto(id=$REDACTED, worktree=$REDACTED, vcsPresent=${vcs != null}, " +
            "name=${redactedIfPresent(name)}, iconPresent=${icon != null})"
}

@Serializable
data class ProjectIconDto(
    val color: String? = null,
    val url: String? = null,
) {
    override fun toString(): String =
        "ProjectIconDto(colorPresent=${color != null}, url=${redactedIfPresent(url)})"
}

@Serializable
data class UpdateProjectRequestDto(
    val name: String? = null,
) {
    override fun toString(): String =
        "UpdateProjectRequestDto(name=${redactedIfPresent(name)})"
}

@Serializable
data class SessionDto(
    val id: String,
    val slug: String? = null,
    val projectID: String? = null,
    val workspaceID: String? = null,
    val directory: String? = null,
    val path: String? = null,
    val parentID: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: SessionModelDto? = null,
    val cost: Double? = null,
    val tokens: SessionTokensDto? = null,
    val version: String? = null,
    val time: SessionTimeDto? = null,
    val revert: SessionRevertDto? = null,
) {
    override fun toString(): String =
        "SessionDto(id=$REDACTED, slug=${redactedIfPresent(slug)}, " +
            "projectID=${redactedIfPresent(projectID)}, workspaceID=${redactedIfPresent(workspaceID)}, " +
            "directory=${redactedIfPresent(directory)}, path=${redactedIfPresent(path)}, " +
            "parentID=${redactedIfPresent(parentID)}, title=${redactedIfPresent(title)}, " +
            "agentPresent=${agent != null}, modelPresent=${model != null}, costPresent=${cost != null}, " +
            "tokensPresent=${tokens != null}, versionPresent=${version != null}, timePresent=${time != null}, " +
            "revertPresent=${revert != null})"
}

@Serializable
data class SessionRevertDto(
    val messageID: String,
    val partID: String? = null,
) {
    override fun toString(): String =
        "SessionRevertDto(messageID=$REDACTED, partID=${redactedIfPresent(partID)})"
}

@Serializable
data class SessionTokensDto(
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cache: SessionTokenCacheDto? = null,
)

@Serializable
data class SessionTokenCacheDto(
    val read: Long? = null,
    val write: Long? = null,
)

@Serializable
data class SessionModelDto(
    val id: String? = null,
    val modelID: String? = null,
    val providerID: String? = null,
    val variant: String? = null,
) {
    override fun toString(): String =
        "SessionModelDto(id=${redactedIfPresent(id)}, modelID=${redactedIfPresent(modelID)}, " +
            "providerID=${redactedIfPresent(providerID)}, variant=${redactedIfPresent(variant)})"
}

@Serializable
data class SessionTimeDto(
    val created: Long? = null,
    val updated: Long? = null,
    val compacting: Long? = null,
    val archived: Long? = null,
)

@Serializable
data class SessionStatusDto(
    val type: String? = null,
)

@Serializable
data class CreateSessionRequestDto(
    val parentID: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: CreateSessionModelDto? = null,
) {
    override fun toString(): String =
        "CreateSessionRequestDto(parentID=${redactedIfPresent(parentID)}, " +
            "title=${redactedIfPresent(title)}, agentPresent=${agent != null}, modelPresent=${model != null})"
}

@Serializable
data class CreateSessionModelDto(
    val id: String,
    val providerID: String,
    val variant: String? = null,
) {
    override fun toString(): String =
        "CreateSessionModelDto(id=$REDACTED, providerID=$REDACTED, " +
            "variant=${redactedIfPresent(variant)})"
}

@Serializable
data class UpdateSessionRequestDto(
    val title: String? = null,
    val time: UpdateSessionTimeDto? = null,
) {
    override fun toString(): String =
        "UpdateSessionRequestDto(title=${redactedIfPresent(title)}, timePresent=${time != null})"
}

@Serializable
data class UpdateSessionTimeDto(
    val archived: Long? = null,
)

@Serializable
data class RevertSessionRequestDto(
    val messageID: String,
    val partID: String? = null,
) {
    override fun toString(): String =
        "RevertSessionRequestDto(messageID=$REDACTED, partID=${redactedIfPresent(partID)})"
}

@Serializable
data class PromptRequestDto(
    val messageID: String? = null,
    val parts: List<PromptPartDto>,
    val agent: String? = null,
    val model: PromptModelDto? = null,
    val variant: String? = null,
) {
    override fun toString(): String =
        "PromptRequestDto(messageID=${redactedIfPresent(messageID)}, partCount=${parts.size}, " +
            "agentPresent=${agent != null}, modelPresent=${model != null}, " +
            "variant=${redactedIfPresent(variant)})"
}

@Serializable
data class PromptPartDto(
    val id: String? = null,
    val type: String,
    val text: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null,
) {
    override fun toString(): String =
        "PromptPartDto(id=${redactedIfPresent(id)}, type=$type, textPresent=${text != null}, " +
            "mimePresent=${mime != null}, url=${redactedIfPresent(url)}, " +
            "filename=${redactedIfPresent(filename)})"
}

@Serializable
data class PromptModelDto(
    val providerID: String,
    val modelID: String,
    val variant: String? = null,
) {
    override fun toString(): String =
        "PromptModelDto(providerID=$REDACTED, modelID=$REDACTED, " +
            "variant=${redactedIfPresent(variant)})"
}

@Serializable
data class CommandRequestDto(
    val messageID: String? = null,
    val command: String,
    val arguments: String? = null,
    val parts: List<PromptPartDto> = emptyList(),
    val agent: String? = null,
    val model: String? = null,
    val variant: String? = null,
) {
    override fun toString(): String =
        "CommandRequestDto(messageID=${redactedIfPresent(messageID)}, command=$REDACTED, " +
            "arguments=${redactedIfPresent(arguments)}, partCount=${parts.size}, " +
            "agentPresent=${agent != null}, modelPresent=${model != null}, " +
            "variant=${redactedIfPresent(variant)})"
}

@Serializable
data class PermissionRequestDto(
    val id: String,
    val sessionID: String,
    val permission: String? = null,
    val patterns: List<String> = emptyList(),
    val always: List<String> = emptyList(),
    val tool: PermissionToolDto? = null,
) {
    override fun toString(): String =
        "PermissionRequestDto(id=$REDACTED, sessionID=$REDACTED, " +
            "permissionPresent=${permission != null}, patternCount=${patterns.size}, " +
            "alwaysCount=${always.size}, toolPresent=${tool != null})"
}

@Serializable
data class PermissionToolDto(
    val messageID: String? = null,
    val callID: String? = null,
) {
    override fun toString(): String =
        "PermissionToolDto(messageID=${redactedIfPresent(messageID)}, " +
            "callID=${redactedIfPresent(callID)})"
}

@Serializable
data class PermissionRespondRequestDto(
    val response: String,
)

@Serializable
data class QuestionRequestDto(
    val id: String,
    val sessionID: String,
    val questions: List<QuestionInfoDto> = emptyList(),
    val tool: QuestionToolDto? = null,
) {
    override fun toString(): String =
        "QuestionRequestDto(id=$REDACTED, sessionID=$REDACTED, " +
            "questionCount=${questions.size}, toolPresent=${tool != null})"
}

@Serializable
data class QuestionInfoDto(
    val header: String? = null,
    val question: String? = null,
    val options: List<QuestionOptionDto> = emptyList(),
    val multiple: Boolean? = null,
    val custom: Boolean? = null,
) {
    override fun toString(): String =
        "QuestionInfoDto(headerPresent=${header != null}, questionPresent=${question != null}, " +
            "optionCount=${options.size}, multiple=$multiple, custom=$custom)"
}

@Serializable
data class QuestionOptionDto(
    val label: String? = null,
    val description: String? = null,
) {
    override fun toString(): String =
        "QuestionOptionDto(labelPresent=${label != null}, descriptionPresent=${description != null})"
}

@Serializable
data class QuestionToolDto(
    val messageID: String? = null,
    val callID: String? = null,
) {
    override fun toString(): String =
        "QuestionToolDto(messageID=${redactedIfPresent(messageID)}, " +
            "callID=${redactedIfPresent(callID)})"
}

@Serializable
data class QuestionReplyRequestDto(
    val answers: List<List<String>>,
) {
    override fun toString(): String =
        "QuestionReplyRequestDto(questionCount=${answers.size}, answerCount=${answers.sumOf(List<String>::size)})"
}

@Serializable
data class MessageWithPartsDto(
    val info: MessageInfoDto,
    val parts: List<MessagePartDto> = emptyList(),
) {
    override fun toString(): String =
        "MessageWithPartsDto(infoPresent=true, partCount=${parts.size})"
}

@Serializable
data class MessageInfoDto(
    val id: String,
    val sessionID: String,
    val role: String,
    val parentID: String? = null,
    val agent: String? = null,
    val mode: String? = null,
    val model: PromptModelDto? = null,
    val modelID: String? = null,
    val providerID: String? = null,
    val variant: String? = null,
    val finish: String? = null,
    val tokens: SessionTokensDto? = null,
    val time: MessageTimeDto? = null,
    val error: JsonElement? = null,
) {
    override fun toString(): String =
        "MessageInfoDto(id=$REDACTED, sessionID=$REDACTED, role=$role, " +
            "parentID=${redactedIfPresent(parentID)}, agentPresent=${agent != null}, " +
            "modePresent=${mode != null}, modelPresent=${model != null || modelID != null}, " +
            "providerPresent=${providerID != null}, variant=${redactedIfPresent(variant)}, " +
            "finishPresent=${finish != null}, tokensPresent=${tokens != null}, timePresent=${time != null}, " +
            "errorPresent=${error != null})"
}

@Serializable
data class MessageTimeDto(
    val created: Long? = null,
    val completed: Long? = null,
)

@Serializable
data class MessagePartDto(
    val id: String,
    val messageID: String,
    val sessionID: String,
    val type: String,
    val text: String? = null,
    val synthetic: Boolean? = null,
    val filename: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val tool: String? = null,
    val state: JsonElement? = null,
    val metadata: JsonElement? = null,
) {
    override fun toString(): String =
        "MessagePartDto(id=$REDACTED, messageID=$REDACTED, sessionID=$REDACTED, type=$type, " +
            "textPresent=${text != null}, synthetic=$synthetic, filename=${redactedIfPresent(filename)}, " +
            "mimePresent=${mime != null}, url=${redactedIfPresent(url)}, toolPresent=${tool != null}, " +
            "statePresent=${state != null}, metadataPresent=${metadata != null})"
}

private const val REDACTED = "<redacted>"

private fun redactedIfPresent(value: Any?): String = if (value == null) "null" else REDACTED
