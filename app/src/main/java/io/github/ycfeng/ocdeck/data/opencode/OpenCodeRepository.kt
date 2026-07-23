package io.github.ycfeng.ocdeck.data.opencode

import io.github.ycfeng.ocdeck.core.network.CommandRequestDto
import io.github.ycfeng.ocdeck.core.network.CreateSessionModelDto
import io.github.ycfeng.ocdeck.core.network.CreateSessionRequestDto
import io.github.ycfeng.ocdeck.core.network.ActiveSessionMessagesRequest
import io.github.ycfeng.ocdeck.core.network.FileContentDto
import io.github.ycfeng.ocdeck.core.network.FileEntryDto
import io.github.ycfeng.ocdeck.core.network.InboundPayloadTooLargeException
import io.github.ycfeng.ocdeck.core.network.MessagePartDto
import io.github.ycfeng.ocdeck.core.network.MessageWithPartsDto
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailure
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailureClassifier
import io.github.ycfeng.ocdeck.core.network.OpenCodeHeaderEncoder
import io.github.ycfeng.ocdeck.core.network.OpenCodeRequestException
import io.github.ycfeng.ocdeck.core.network.PathInfoDto
import io.github.ycfeng.ocdeck.core.network.PermissionRequestDto
import io.github.ycfeng.ocdeck.core.network.PermissionRespondRequestDto
import io.github.ycfeng.ocdeck.core.network.PermissionToolDto
import io.github.ycfeng.ocdeck.core.network.LoadedProjectSnapshot
import io.github.ycfeng.ocdeck.core.network.LoadedActiveSessionMessages
import io.github.ycfeng.ocdeck.core.network.ProjectSnapshotLoader
import io.github.ycfeng.ocdeck.core.network.LoadedSessionListWindow
import io.github.ycfeng.ocdeck.core.network.SessionListWindowLoader
import io.github.ycfeng.ocdeck.core.network.ProjectDto
import io.github.ycfeng.ocdeck.core.network.ProjectIconDto
import io.github.ycfeng.ocdeck.core.network.PromptModelDto
import io.github.ycfeng.ocdeck.core.network.PromptPartDto
import io.github.ycfeng.ocdeck.core.network.PromptRequestDto
import io.github.ycfeng.ocdeck.core.network.QuestionInfoDto
import io.github.ycfeng.ocdeck.core.network.QuestionOptionDto
import io.github.ycfeng.ocdeck.core.network.QuestionReplyRequestDto
import io.github.ycfeng.ocdeck.core.network.QuestionRequestDto
import io.github.ycfeng.ocdeck.core.network.QuestionToolDto
import io.github.ycfeng.ocdeck.core.network.RevertSessionRequestDto
import io.github.ycfeng.ocdeck.core.network.SessionDto
import io.github.ycfeng.ocdeck.core.network.UpdateProjectRequestDto
import io.github.ycfeng.ocdeck.core.network.UpdateSessionRequestDto
import io.github.ycfeng.ocdeck.core.network.UpdateSessionTimeDto
import io.github.ycfeng.ocdeck.core.network.toOpenCodeMessageComment
import io.github.ycfeng.ocdeck.core.network.readFileContentDto
import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.core.store.SessionListWindowLoadRequest
import io.github.ycfeng.ocdeck.core.store.SessionListWindowState
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.core.util.ProjectFilePathNormalizer
import io.github.ycfeng.ocdeck.core.util.ProjectFileUrlBuilder
import io.github.ycfeng.ocdeck.data.server.OpenCodeServerRepository
import io.github.ycfeng.ocdeck.data.server.ServerHiddenModelPreference
import io.github.ycfeng.ocdeck.data.server.withStableServerConnection
import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommand
import io.github.ycfeng.ocdeck.domain.model.OpenCodeDiffFile
import io.github.ycfeng.ocdeck.domain.model.OpenCodeDirectorySuggestion
import io.github.ycfeng.ocdeck.domain.model.OpenCodeDirectorySuggestionSource
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileContent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileEntry
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileType
import io.github.ycfeng.ocdeck.domain.model.OpenCodeLsp
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMcp
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageBundle
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModelSettingsGroup
import io.github.ycfeng.ocdeck.domain.model.OpenCodePathInfo
import io.github.ycfeng.ocdeck.domain.model.OpenCodePermissionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodePermissionToolRef
import io.github.ycfeng.ocdeck.domain.model.OpenCodePlugin
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProjectSnapshot
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionInfo
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionOption
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionToolRef
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionRevert
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionStatus
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionTokens
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.domain.model.ProjectIcon
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachment
import io.github.ycfeng.ocdeck.domain.prompt.PromptGateway
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContext
import io.github.ycfeng.ocdeck.domain.prompt.SessionRevertGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.util.concurrent.atomic.AtomicLong

private val projectFileEntryComparator = compareBy<OpenCodeFileEntry>(
    { it.type != OpenCodeFileType.Directory },
    { it.name.lowercase() },
    { it.name },
)

internal const val MaxSessionMetadataDepth = 16

private val sessionDisplayComparator = compareByDescending<OpenCodeSession> { it.updatedAt ?: 0L }
    .thenByDescending(OpenCodeSession::id)

class OpenCodeRepository(
    private val serverRepository: OpenCodeServerRepository,
    private val pathNormalizer: PathNormalizer,
    private val projectFilePathNormalizer: ProjectFilePathNormalizer,
    private val json: Json,
    private val redactor: Redactor,
    private val fileContentDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PromptGateway, SessionRevertGateway, ProjectSnapshotLoader, SessionListWindowLoader {
    private val promptCapabilityRevision = AtomicLong()
    private val projectFileUrlBuilder = ProjectFileUrlBuilder(pathNormalizer, projectFilePathNormalizer)

    suspend fun loadProject(
        serverId: String,
        directory: String,
        sessionWindow: SessionListWindowState,
        workspace: String? = null,
    ): Result<OpenCodeProjectSnapshot> = loadProjectSnapshot(
        serverId = serverId,
        directory = directory,
        workspace = workspace,
        sessionWindow = sessionWindow,
        activeSessionMessages = null,
    )
        .map(LoadedProjectSnapshot::snapshot)

    override suspend fun loadProjectSnapshot(
        serverId: String,
        directory: String,
        workspace: String?,
        sessionWindow: SessionListWindowState,
        activeSessionMessages: ActiveSessionMessagesRequest?,
    ): Result<LoadedProjectSnapshot> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val stable = withStableServerConnection(
            getConnection = { serverRepository.getConnection(serverId) },
            transportIdentity = { it.transportIdentity },
        ) { connection ->
            val api = connection.api
            coroutineScope {
                val path = async { api.getPath(normalized, workspace) }
                val project = async {
                    try {
                        api.getCurrentProject(normalized, workspace).toProjectRef(normalized)
                    } catch (http: HttpException) {
                        if (http.code() != 404) throw http
                        null
                    }
                }
                val sessions = async {
                    api.getSessions(
                        directory = normalized,
                        workspace = workspace,
                        roots = true,
                        limit = sessionWindow.requestedRawLimit,
                    )
                }
                val statuses = async { api.getSessionStatus(normalized, workspace) }
                val providers = async { api.getProviders(normalized, workspace) }
                val agents = async { api.getAgents(normalized, workspace) }
                val commands = async { api.getCommands(normalized, workspace) }
                val permissions = async { api.getPermissions(normalized, workspace) }
                val questions = async { api.getQuestions(normalized, workspace) }
                val mcps = async { api.getMcps(normalized, workspace) }
                val lsps = async { api.getLsps(normalized, workspace) }
                val config = async { api.getConfig(normalized, workspace) }
                val hiddenModels = async { serverRepository.getHiddenModelPreferences(serverId).toModelKeySet() }
                val messages = activeSessionMessages?.let { request ->
                    async {
                        connection.sessionMessagesTransport.load(request.sessionId, normalized, workspace)
                    }
                }
                val providerJson = providers.await()
                val agentJson = agents.await()
                val commandJson = commands.await()
                val configJson = config.await()
                val parsedModels = providerJson.extractModels(
                    config = configJson,
                    hiddenModels = hiddenModels.await(),
                )
                val parsedAgents = agentJson.extractAgents()
                val parsedCommands = commandJson.extractCommands()
                val promptCapabilities = PromptCapabilities(
                    models = parsedModels.toList(),
                    agents = parsedAgents.toList(),
                    commands = parsedCommands.toList(),
                    serverDefaultModels = providerJson.extractServerDefaultModels(parsedModels),
                    isLoaded = true,
                    revision = promptCapabilityRevision.incrementAndGet(),
                )
                val permissionRequests = permissions.await().map { it.toDomain() }
                val questionRequests = questions.await().map { it.toDomain() }
                val sessionDtos = sessions.await()
                val snapshot = OpenCodeProjectSnapshot(
                    serverId = serverId,
                    normalizedDirectory = normalized,
                    workspace = workspace,
                    project = project.await(),
                    pathInfo = path.await().toDomain(normalized),
                    sessions = sessionDtos.map { it.toDomain(normalized) }.sortedWith(sessionDisplayComparator),
                    sessionWindowRequestedRawLimit = sessionWindow.requestedRawLimit,
                    sessionWindowRawResultCount = sessionDtos.size,
                    sessionWindowRequestGeneration = sessionWindow.requestGeneration,
                    statuses = statuses.await().mapValues { OpenCodeSessionStatus(it.value.type ?: "idle") },
                    providerCount = providerJson.providerCount(),
                    models = promptCapabilities.models,
                    agents = promptCapabilities.agents,
                    commands = promptCapabilities.commands,
                    promptCapabilities = promptCapabilities,
                    mcps = mcps.await().extractMcps(redactor),
                    lsps = lsps.await().extractLsps(),
                    plugins = configJson.extractPlugins(),
                    commandCount = parsedCommands.size,
                    permissionCount = permissionRequests.size,
                    questionCount = questionRequests.size,
                    permissions = permissionRequests,
                    questions = questionRequests,
                )
                snapshot to messages?.await()
            }
        }
        LoadedProjectSnapshot(
            snapshot = stable.value.first,
            transportIdentity = stable.transportIdentity,
            activeSessionMessages = activeSessionMessages?.let { request ->
                LoadedActiveSessionMessages(
                    sessionId = request.sessionId,
                    requestGeneration = request.requestGeneration,
                    expectedRevision = request.expectedRevision,
                    bundle = stable.value.second?.items?.toBundle() ?: invalidResponse(),
                    nextCursor = stable.value.second?.nextCursor,
                )
            },
        )
    }

    override suspend fun loadSessionListWindow(
        serverId: String,
        directory: String,
        workspace: String?,
        request: SessionListWindowLoadRequest,
    ): Result<LoadedSessionListWindow> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val stable = withStableServerConnection(
            getConnection = { serverRepository.getConnection(serverId) },
            transportIdentity = { it.transportIdentity },
        ) { connection ->
            connection.api.getSessions(
                directory = normalized,
                workspace = workspace,
                roots = true,
                limit = request.requestedRawLimit,
            )
        }
        LoadedSessionListWindow(
            sessions = stable.value.map { it.toDomain(normalized) }.sortedWith(sessionDisplayComparator),
            requestedRawLimit = request.requestedRawLimit,
            rawResultCount = stable.value.size,
            requestGeneration = request.requestGeneration,
            transportIdentity = stable.transportIdentity,
        )
    }

    override suspend fun isSessionListTransportCurrent(
        serverId: String,
        transportIdentity: io.github.ycfeng.ocdeck.data.server.ServerTransportIdentity,
    ): Boolean = try {
        serverRepository.getConnection(serverId).transportIdentity == transportIdentity
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (fatal: Error) {
        throw fatal
    } catch (_: Exception) {
        false
    }

    suspend fun loadSessionMetadataChain(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ): Result<List<OpenCodeSession>> = catching {
        requireOperation(sessionId.isNotBlank())
        val normalized = pathNormalizer.normalize(directory)
        withStableServerConnection(
            getConnection = { serverRepository.getConnection(serverId) },
            transportIdentity = { it.transportIdentity },
        ) { connection ->
            val sessions = mutableListOf<OpenCodeSession>()
            val visited = mutableSetOf<String>()
            var nextSessionId: String? = sessionId
            repeat(MaxSessionMetadataDepth) {
                val currentId = nextSessionId?.takeIf(visited::add) ?: return@repeat
                val session = connection.api.getSession(
                    sessionId = currentId,
                    directory = normalized,
                    workspace = workspace,
                ).toDomain(normalized)
                sessions += session
                nextSessionId = session.parentId?.takeIf { it.isNotBlank() && it !in visited }
                if (nextSessionId == null) return@withStableServerConnection sessions
            }
            sessions
        }.value
    }

    suspend fun updateProjectName(
        serverId: String,
        directory: String,
        projectId: String,
        name: String,
        workspace: String? = null,
    ): Result<ProjectRef> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val trimmedName = name.trim()
        requireOperation(projectId.isNotBlank())
        requireOperation(trimmedName.isNotBlank())
        val api = serverRepository.getConnection(serverId).api
        api.updateProject(
            projectId = projectId,
            directory = normalized,
            workspace = workspace,
            body = UpdateProjectRequestDto(name = trimmedName),
        ).toProjectRef(normalized)
    }

    suspend fun loadMessages(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ): Result<OpenCodeMessageBundle> = catching {
        loadMessagePage(serverId, directory, sessionId, workspace).getOrThrow().bundle
    }

    suspend fun loadMessagePage(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
        before: String? = null,
    ): Result<OpenCodeMessagePage> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val page = serverRepository.getConnection(serverId).sessionMessagesTransport
            .load(sessionId, normalized, workspace, before)
        OpenCodeMessagePage(
            bundle = page.items.toBundle(),
            nextCursor = page.nextCursor,
        )
    }

    suspend fun loadSessionDiff(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ): Result<List<OpenCodeDiffFile>> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        api.getSessionDiff(sessionId, normalized, workspace).extractDiffFiles()
    }

    suspend fun loadVcsDiff(
        serverId: String,
        directory: String,
        mode: String = "git",
        workspace: String? = null,
    ): Result<List<OpenCodeDiffFile>> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        api.getVcsDiff(mode, normalized, workspace).extractDiffFiles()
    }

    suspend fun suggestProjectDirectories(
        serverId: String,
        input: String,
        searchRoot: String? = null,
        limit: Int = 20,
    ): Result<List<OpenCodeDirectorySuggestion>> = catching {
        val query = input.trim()
        if (query.isBlank()) return@catching emptyList()

        val api = serverRepository.getConnection(serverId).api
        val browseRequest = buildDirectoryBrowseRequest(query, pathNormalizer)
        if (browseRequest != null) {
            return@catching api.getFileEntries(directory = browseRequest.directory)
                .toBrowseSuggestions(browseRequest, limit)
        }

        if (query.length < MIN_SEARCH_QUERY_LENGTH) return@catching emptyList()
        val root = searchRoot
            ?.takeIf { it.isNotBlank() }
            ?.let(pathNormalizer::normalize)
            ?: api.getPath().home?.takeIf { it.isNotBlank() }?.let(pathNormalizer::normalize)
            ?: return@catching emptyList()

        api.findFiles(directory = root, query = query, type = "directory", limit = limit)
            .toSearchSuggestions(root, limit)
    }

    suspend fun listProjectFiles(
        serverId: String,
        directory: String,
        path: String = "",
    ): Result<List<OpenCodeFileEntry>> = catching {
        val normalizedDirectory = pathNormalizer.normalize(directory)
        val backslashIsSeparator = projectFilePathNormalizer.usesWindowsSeparators(normalizedDirectory)
        val normalizedPath = projectFilePathNormalizer.normalize(
            path = path,
            allowEmpty = true,
            backslashIsSeparator = backslashIsSeparator,
        )
        val api = serverRepository.getConnection(serverId).api
        api.getFileEntries(directory = normalizedDirectory, path = normalizedPath)
            .map { entry -> entry.toProjectFileEntry(backslashIsSeparator) ?: invalidResponse() }
            .onEach { entry ->
                if (projectFilePathNormalizer.parent(entry.path, backslashIsSeparator) != normalizedPath) {
                    invalidResponse()
                }
            }
            .distinctBy(OpenCodeFileEntry::path)
            .sortedWith(projectFileEntryComparator)
    }

    suspend fun searchProjectFiles(
        serverId: String,
        directory: String,
        query: String,
        limit: Int = 100,
    ): Result<List<OpenCodeFileEntry>> = catching {
        val normalizedDirectory = pathNormalizer.normalize(directory)
        val backslashIsSeparator = projectFilePathNormalizer.usesWindowsSeparators(normalizedDirectory)
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return@catching emptyList()

        val api = serverRepository.getConnection(serverId).api
        api.findFiles(
            directory = normalizedDirectory,
            query = normalizedQuery,
            type = "file",
            limit = limit.coerceIn(1, 200),
        ).mapNotNull { path ->
            runCatching {
                projectFilePathNormalizer.normalize(path, backslashIsSeparator = backslashIsSeparator)
            }
                .getOrNull()
                ?.let { normalizedPath ->
                    OpenCodeFileEntry(
                        name = projectFilePathNormalizer.name(normalizedPath, backslashIsSeparator),
                        path = normalizedPath,
                        type = OpenCodeFileType.File,
                        ignored = false,
                    )
                }
        }.distinctBy(OpenCodeFileEntry::path)
    }

    suspend fun loadProjectFile(
        serverId: String,
        directory: String,
        path: String,
    ): Result<OpenCodeFileContent> = catching {
        val normalizedDirectory = pathNormalizer.normalize(directory)
        val backslashIsSeparator = projectFilePathNormalizer.usesWindowsSeparators(normalizedDirectory)
        val normalizedPath = projectFilePathNormalizer.normalize(
            path,
            backslashIsSeparator = backslashIsSeparator,
        )
        val api = serverRepository.getConnection(serverId).api
        try {
            withContext(fileContentDispatcher) {
                api.getFileContent(directory = normalizedDirectory, path = normalizedPath)
                    .readFileContentDto(json)
                    .toDomain(normalizedPath)
            }
        } catch (_: InboundPayloadTooLargeException) {
            OpenCodeFileContent.TooLarge(normalizedPath)
        }
    }

    override suspend fun createSession(
        serverId: String,
        directory: String,
        agent: String,
        model: PromptModelSelection,
        workspace: String?,
    ): Result<OpenCodeSession> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        api.createSession(
            directory = normalized,
            workspace = workspace,
            body = CreateSessionRequestDto(
                agent = agent,
                model = CreateSessionModelDto(model.modelId, model.providerId, model.variant),
            ),
        ).toDomain(normalized)
    }

    override suspend fun sendPromptAsync(
        serverId: String,
        directory: String,
        sessionId: String,
        messageId: String,
        partId: String,
        text: String,
        attachments: List<PromptAttachment>,
        projectFileContexts: List<ProjectFileContext>,
        agent: String,
        model: PromptModelSelection,
        variant: String?,
        workspace: String?,
    ): Result<Unit> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        val response = api.sendPromptAsync(
            sessionId = sessionId,
            directory = OpenCodeHeaderEncoder.encodeDirectory(normalized),
            workspace = workspace,
            body = PromptRequestDto(
                messageID = messageId,
                parts = listOf(PromptPartDto(id = partId, type = "text", text = text)) +
                    projectFileContexts.map { it.toPromptPartDto(normalized, projectFileUrlBuilder) } +
                    attachments.map { it.toPromptPartDto() },
                agent = agent,
                model = PromptModelDto(model.providerId, model.modelId),
                variant = variant,
            ),
        )
        if (!response.isSuccessful) httpFailure(response.code())
    }

    override suspend fun sendCommandAsync(
        serverId: String,
        directory: String,
        sessionId: String,
        messageId: String,
        command: String,
        arguments: String?,
        attachments: List<PromptAttachment>,
        projectFileContexts: List<ProjectFileContext>,
        agent: String,
        model: PromptModelSelection,
        variant: String?,
        workspace: String?,
    ): Result<Unit> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        val modelValue = "${model.providerId}/${model.modelId}"
        val response = api.sendCommand(
            sessionId = sessionId,
            directory = normalized,
            workspace = workspace,
            body = CommandRequestDto(
                messageID = messageId,
                command = command.removePrefix("/"),
                arguments = arguments?.takeIf { it.isNotBlank() },
                parts = projectFileContexts.map { it.toPromptPartDto(normalized, projectFileUrlBuilder) } +
                    attachments.map { it.toPromptPartDto() },
                agent = agent,
                model = modelValue,
                variant = variant,
            ),
        )
        if (!response.isSuccessful) httpFailure(response.code())
    }

    override suspend fun abortSession(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String?,
    ): Result<Unit> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        if (!api.abortSession(sessionId, normalized, workspace)) operationRejected()
    }

    override suspend fun revertSession(
        serverId: String,
        directory: String,
        sessionId: String,
        messageId: String,
        partId: String?,
        workspace: String?,
    ): Result<OpenCodeSession> = catching {
        requireOperation(sessionId.isNotBlank())
        requireOperation(messageId.isNotBlank())
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        api.revertSession(
            sessionId = sessionId,
            directory = OpenCodeHeaderEncoder.encodeDirectory(normalized),
            workspace = workspace,
            body = RevertSessionRequestDto(messageID = messageId, partID = partId),
        ).toDomain(normalized)
    }

    override suspend fun unrevertSession(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String?,
    ): Result<OpenCodeSession> = catching {
        requireOperation(sessionId.isNotBlank())
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        api.unrevertSession(
            sessionId = sessionId,
            directory = OpenCodeHeaderEncoder.encodeDirectory(normalized),
            workspace = workspace,
        ).toDomain(normalized)
    }

    suspend fun replyQuestion(
        serverId: String,
        directory: String,
        requestId: String,
        answers: List<List<String>>,
        workspace: String? = null,
    ): Result<Unit> = catching {
        requireOperation(requestId.isNotBlank())
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        val replied = api.replyQuestion(
            requestId = requestId,
            directory = normalized,
            workspace = workspace,
            body = QuestionReplyRequestDto(answers = answers),
        )
        if (!replied) operationRejected()
    }

    suspend fun rejectQuestion(
        serverId: String,
        directory: String,
        requestId: String,
        workspace: String? = null,
    ): Result<Unit> = catching {
        requireOperation(requestId.isNotBlank())
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        val rejected = api.rejectQuestion(
            requestId = requestId,
            directory = normalized,
            workspace = workspace,
        )
        if (!rejected) operationRejected()
    }

    suspend fun respondPermission(
        serverId: String,
        directory: String,
        sessionId: String,
        permissionId: String,
        response: String,
        workspace: String? = null,
    ): Result<Unit> = catching {
        requireOperation(sessionId.isNotBlank())
        requireOperation(permissionId.isNotBlank())
        requireOperation(response in setOf("once", "always", "reject"))
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        val responded = api.respondPermission(
            sessionId = sessionId,
            permissionId = permissionId,
            directory = OpenCodeHeaderEncoder.encodeDirectory(normalized),
            workspace = workspace,
            body = PermissionRespondRequestDto(response = response),
        )
        if (!responded) operationRejected()
    }

    suspend fun connectMcp(
        serverId: String,
        directory: String,
        name: String,
        workspace: String? = null,
    ): Result<Unit> = catching {
        requireOperation(name.isNotBlank())
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        val connected = api.connectMcp(name, normalized, workspace)
        if (!connected) operationRejected()
    }

    suspend fun disconnectMcp(
        serverId: String,
        directory: String,
        name: String,
        workspace: String? = null,
    ): Result<Unit> = catching {
        requireOperation(name.isNotBlank())
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        val disconnected = api.disconnectMcp(name, normalized, workspace)
        if (!disconnected) operationRejected()
    }

    suspend fun renameSession(
        serverId: String,
        directory: String,
        sessionId: String,
        title: String,
        workspace: String? = null,
    ): Result<OpenCodeSession> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val trimmedTitle = title.trim()
        requireOperation(trimmedTitle.isNotBlank())
        val api = serverRepository.getConnection(serverId).api
        api.updateSession(
            sessionId = sessionId,
            directory = normalized,
            workspace = workspace,
            body = UpdateSessionRequestDto(title = trimmedTitle),
        ).toDomain(normalized)
    }

    suspend fun archiveSession(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ): Result<OpenCodeSession> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        api.updateSession(
            sessionId = sessionId,
            directory = normalized,
            workspace = workspace,
            body = UpdateSessionRequestDto(
                time = UpdateSessionTimeDto(archived = System.currentTimeMillis()),
            ),
        ).toDomain(normalized)
    }

    suspend fun deleteSession(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ): Result<Unit> = catching {
        val normalized = pathNormalizer.normalize(directory)
        val api = serverRepository.getConnection(serverId).api
        val response = api.deleteSession(sessionId, normalized, workspace)
        if (!response.isSuccessful) httpFailure(response.code())
    }

    suspend fun loadModelSettings(
        serverId: String,
    ): Result<List<OpenCodeModelSettingsGroup>> = catching {
        val api = serverRepository.getConnection(serverId).api
        val providers = api.getProviders()
        val hiddenModels = serverRepository.getHiddenModelPreferences(serverId).toModelKeySet()
        providers.extractModelSettings(hiddenModels = hiddenModels)
    }

    suspend fun setModelEnabled(
        serverId: String,
        providerId: String,
        providerConfigKey: String,
        modelId: String,
        enabled: Boolean,
    ): Result<List<OpenCodeModelSettingsGroup>> = catching {
        serverRepository.setModelHidden(serverId, providerId, modelId, hidden = !enabled)
        val api = serverRepository.getConnection(serverId).api
        val providers = api.getProviders()
        val hiddenModels = serverRepository.getHiddenModelPreferences(serverId).toModelKeySet()
        providers.extractModelSettings(hiddenModels = hiddenModels)
    }

    private suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (throwable: Throwable) {
        Result.failure(OpenCodeFailureClassifier.toRequestException(throwable))
    }

    private fun PathInfoDto.toDomain(defaultDirectory: String): OpenCodePathInfo = OpenCodePathInfo(
        home = home,
        state = state,
        config = config,
        worktree = worktree,
        directory = directory?.let(pathNormalizer::normalize) ?: defaultDirectory,
    )

    private fun ProjectDto.toProjectRef(defaultDirectory: String): ProjectRef {
        val normalizedWorktree = worktree
            .takeIf { it.isNotBlank() }
            ?.let(pathNormalizer::normalize)
            ?: defaultDirectory
        return ProjectRef(
            normalizedDirectory = normalizedWorktree,
            projectId = id.takeIf { it.isNotBlank() },
            displayName = name?.takeIf { it.isNotBlank() } ?: displayNameFromDirectory(normalizedWorktree),
            vcs = vcs,
            icon = icon.toDomain(),
        )
    }

    private fun ProjectIconDto?.toDomain(): ProjectIcon? = this?.let { icon ->
        ProjectIcon(
            color = icon.color,
            url = icon.url,
        )
    }

    private fun FileEntryDto.toProjectFileEntry(backslashIsSeparator: Boolean): OpenCodeFileEntry? {
        val fileType = when (type.lowercase()) {
            "file" -> OpenCodeFileType.File
            "directory" -> OpenCodeFileType.Directory
            else -> return null
        }
        val normalizedPath = runCatching {
            projectFilePathNormalizer.normalize(path, backslashIsSeparator = backslashIsSeparator)
        }.getOrNull() ?: return null
        val normalizedName = projectFilePathNormalizer.name(normalizedPath, backslashIsSeparator)
        if (name != normalizedName) return null
        return OpenCodeFileEntry(
            name = normalizedName,
            path = normalizedPath,
            type = fileType,
            ignored = ignored,
        )
    }

    private fun FileContentDto.toDomain(path: String): OpenCodeFileContent = when (type.lowercase()) {
        "text" -> OpenCodeFileContent.Text(path = path, text = content)
        "binary" -> {
            if (!encoding.equals("base64", ignoreCase = true)) invalidResponse()
            OpenCodeFileContent.Binary(
                path = path,
                base64 = content,
                mimeType = mimeType,
            )
        }
        else -> invalidResponse()
    }

    private fun requireOperation(condition: Boolean) {
        if (!condition) operationRejected()
    }

    private fun httpFailure(statusCode: Int): Nothing =
        throw OpenCodeRequestException(OpenCodeFailure.HttpStatus(statusCode))

    private fun operationRejected(): Nothing =
        throw OpenCodeRequestException(OpenCodeFailure.OperationRejected())

    private fun invalidResponse(): Nothing =
        throw OpenCodeRequestException(OpenCodeFailure.InvalidResponse)

    private fun SessionDto.toDomain(defaultDirectory: String): OpenCodeSession {
        val providerId = model?.providerID
        val modelId = model?.id ?: model?.modelID
        val variant = model?.variant
        val modelLabel = model?.let {
            listOfNotNull(providerId, modelId, variant).joinToString("/").ifBlank { null }
        }
        return OpenCodeSession(
            id = id,
            title = title ?: "Untitled",
            normalizedDirectory = directory?.let(pathNormalizer::normalize) ?: defaultDirectory,
            path = path,
            parentId = parentID,
            agent = agent,
            modelLabel = modelLabel,
            updatedAt = time?.updated ?: time?.created,
            archivedAt = time?.archived,
            modelProviderId = providerId,
            modelId = modelId,
            modelVariant = variant,
            createdAt = time?.created,
            cost = cost,
            tokens = tokens?.let {
                OpenCodeSessionTokens(
                    input = it.input ?: 0,
                    output = it.output ?: 0,
                    reasoning = it.reasoning ?: 0,
                    cacheRead = it.cache?.read ?: 0,
                    cacheWrite = it.cache?.write ?: 0,
                )
            },
            revert = revert?.let {
                OpenCodeSessionRevert(
                    messageId = it.messageID,
                    partId = it.partID,
                )
            },
        )
    }

    private fun QuestionRequestDto.toDomain(): OpenCodeQuestionRequest = OpenCodeQuestionRequest(
        id = id,
        sessionId = sessionID,
        questions = questions.mapNotNull { it.toDomain() },
        tool = tool.toDomain(),
    )

    private fun QuestionInfoDto.toDomain(): OpenCodeQuestionInfo? {
        val text = question?.takeIf { it.isNotBlank() } ?: return null
        return OpenCodeQuestionInfo(
            header = header?.takeIf { it.isNotBlank() },
            question = text,
            options = options.mapNotNull { it.toDomain() },
            multiple = multiple == true,
            custom = custom ?: true,
        )
    }

    private fun QuestionOptionDto.toDomain(): OpenCodeQuestionOption? {
        val optionLabel = label?.takeIf { it.isNotBlank() } ?: return null
        return OpenCodeQuestionOption(
            label = optionLabel,
            description = description?.takeIf { it.isNotBlank() },
        )
    }

    private fun QuestionToolDto?.toDomain(): OpenCodeQuestionToolRef? = this?.let { tool ->
        OpenCodeQuestionToolRef(
            messageId = tool.messageID,
            callId = tool.callID,
        )
    }

    private fun PermissionRequestDto.toDomain(): OpenCodePermissionRequest = OpenCodePermissionRequest(
        id = id,
        sessionId = sessionID,
        permission = permission?.takeIf { it.isNotBlank() } ?: "unknown",
        patterns = patterns.filter { it.isNotBlank() },
        always = always.filter { it.isNotBlank() },
        tool = tool.toDomain(),
    )

    private fun PermissionToolDto?.toDomain(): OpenCodePermissionToolRef? = this?.let { tool ->
        OpenCodePermissionToolRef(
            messageId = tool.messageID,
            callId = tool.callID,
        )
    }

    private fun List<MessageWithPartsDto>.toBundle(): OpenCodeMessageBundle {
        val parts = flatMap { message -> message.parts.map { it.toDomain() } }
        val messages = map { bundle ->
            val messageParts = bundle.parts.map { it.toDomain() }
            val info = bundle.info
            val providerId = info.providerID ?: info.model?.providerID
            val modelId = info.modelID ?: info.model?.modelID
            val variant = info.variant ?: info.model?.variant
            val modelLabel = listOfNotNull(providerId, modelId, variant).joinToString("/").ifBlank { null }
            OpenCodeMessage(
                id = info.id,
                sessionId = info.sessionID,
                role = info.role,
                text = messageParts.renderText(),
                parentId = info.parentID,
                agent = info.agent ?: info.mode,
                modelLabel = modelLabel,
                modelProviderId = providerId,
                modelId = modelId,
                modelVariant = variant,
                createdAt = info.time?.created,
                completedAt = info.time?.completed,
                tokens = info.tokens?.let {
                    OpenCodeSessionTokens(
                        input = it.input ?: 0,
                        output = it.output ?: 0,
                        reasoning = it.reasoning ?: 0,
                        cacheRead = it.cache?.read ?: 0,
                        cacheWrite = it.cache?.write ?: 0,
                    )
                },
                error = info.error.redactedError(redactor),
            )
        }
        return OpenCodeMessageBundle(messages = messages, parts = parts)
    }

    private fun MessagePartDto.toDomain(): OpenCodeMessagePart = OpenCodeMessagePart(
        id = id,
        messageId = messageID,
        sessionId = sessionID,
        type = type,
        text = text,
        synthetic = synthetic ?: false,
        filename = filename,
        mime = mime,
        url = url,
        tool = tool,
        stateStatus = state?.toolStateStatus(),
        toolInput = state?.toolStateInput().orEmpty(),
        toolInputJson = state?.toolStateInputJson(),
        toolMetadata = state?.toolStateMetadata().orEmpty(),
        toolMetadataJson = state?.toolStateMetadataJson(),
        toolOutput = state?.toolStateOutput(),
        toolError = state?.toolStateError(redactor),
        opencodeComment = metadata.toOpenCodeMessageComment(),
    )

    private fun List<FileEntryDto>.toBrowseSuggestions(
        request: DirectoryBrowseRequest,
        limit: Int,
    ): List<OpenCodeDirectorySuggestion> = asSequence()
        .filter { it.type.equals("directory", ignoreCase = true) && !it.ignored }
        .mapNotNull { entry ->
            val name = entry.directoryName() ?: return@mapNotNull null
            if (request.fragment.isNotBlank() && !name.contains(request.fragment, ignoreCase = true)) return@mapNotNull null
            val directory = entry.absolute
                .takeIf { it.isNotBlank() }
                ?.let(pathNormalizer::normalize)
                ?: joinDirectory(request.directory, name, pathNormalizer)
            OpenCodeDirectorySuggestion(
                directory = directory,
                name = name,
                parent = parentDirectoryOf(directory, pathNormalizer),
                source = OpenCodeDirectorySuggestionSource.Browse,
            )
        }
        .distinctBy { pathNormalizer.comparisonKey(it.directory) }
        .sortedWith(
            compareBy<OpenCodeDirectorySuggestion> { suggestion ->
                request.fragment.isNotBlank() && !suggestion.name.startsWith(request.fragment, ignoreCase = true)
            }.thenBy { it.name.lowercase() },
        )
        .take(limit)
        .toList()

    private fun List<String>.toSearchSuggestions(
        root: String,
        limit: Int,
    ): List<OpenCodeDirectorySuggestion> = asSequence()
        .map { it.replace('\\', '/').trim('/') }
        .filter { it.isNotBlank() }
        .map { relative ->
            val directory = joinDirectory(root, relative, pathNormalizer)
            OpenCodeDirectorySuggestion(
                directory = directory,
                name = displayNameFromDirectory(directory),
                parent = parentDirectoryOf(directory, pathNormalizer),
                source = OpenCodeDirectorySuggestionSource.Search,
            )
        }
        .distinctBy { pathNormalizer.comparisonKey(it.directory) }
        .take(limit)
        .toList()

    private fun FileEntryDto.directoryName(): String? = name
        .trimEnd('\\', '/')
        .takeIf { it.isNotBlank() }
        ?: path
            .replace('\\', '/')
            .trim('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }

    private companion object {
        const val MIN_SEARCH_QUERY_LENGTH = 2
    }
}

private fun PromptAttachment.toPromptPartDto(): PromptPartDto = PromptPartDto(
    id = id,
    type = "file",
    mime = mime,
    url = dataUrl,
    filename = filename,
)

private fun ProjectFileContext.toPromptPartDto(
    projectDirectory: String,
    urlBuilder: ProjectFileUrlBuilder,
): PromptPartDto = PromptPartDto(
    id = id,
    type = "file",
    mime = "text/plain",
    url = urlBuilder.build(projectDirectory, relativePath),
    filename = filename,
)

private fun List<OpenCodeMessagePart>.renderText(): String = filter { it.type == "text" && !it.synthetic }
    .mapNotNull { it.text }
    .joinToString("\n")

private fun JsonElement.itemCount(): Int = when (this) {
    is JsonArray -> size
    is JsonObject -> size
    else -> 0
}

internal fun JsonElement.extractAgents(): List<OpenCodeAgent> = when (this) {
    is JsonArray -> mapNotNull { element -> element.jsonObjectOrNull()?.toAgent() }
    is JsonObject -> when (val nested = this["agents"]) {
        is JsonArray -> nested.extractAgents()
        is JsonObject -> nested.entries.mapNotNull { (id, value) -> value.jsonObjectOrNull()?.toAgent(id) }
        else -> entries.mapNotNull { (id, value) -> value.jsonObjectOrNull()?.toAgent(id) }
    }
    else -> emptyList()
}

private fun JsonObject.toAgent(defaultId: String? = null): OpenCodeAgent? {
    val explicitId = string("id")?.trim()?.takeIf { it.isNotEmpty() }
    val fallbackId = defaultId?.trim()?.takeIf { it.isNotEmpty() }
    val name = string("name")?.trim()?.takeIf { it.isNotEmpty() }
    val id = explicitId ?: fallbackId ?: name ?: return null
    return OpenCodeAgent(
        id = id,
        name = name ?: id,
        description = string("description"),
    )
}

internal fun JsonElement.extractDiffFiles(): List<OpenCodeDiffFile> = collectDiffElements()
    .mapNotNull { element -> element.jsonObjectOrNull()?.toDiffFile() }

private fun JsonElement.collectDiffElements(): List<JsonElement> = when (this) {
    is JsonArray -> flatMap { element -> element.collectDiffElements() }
    is JsonObject -> {
        if (looksLikeDiffFile()) {
            listOf(this)
        } else {
            entries.flatMap { it.value.collectDiffElements() }
        }
    }
    else -> emptyList()
}

private fun JsonObject.looksLikeDiffFile(): Boolean = string("file") != null ||
    string("path") != null ||
    string("newPath") != null ||
    string("to") != null ||
    string("name") != null

private fun JsonObject.toDiffFile(): OpenCodeDiffFile? {
    val file = firstString("file", "path", "newPath", "to", "name")
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val additions = firstInt("additions", "added") ?: 0
    val deletions = firstInt("deletions", "deleted", "removed") ?: 0
    return OpenCodeDiffFile(
        file = file,
        oldFile = firstString("oldFile", "oldPath", "from")?.takeIf { it.isNotBlank() },
        status = firstString("status", "type") ?: inferredDiffStatus(),
        additions = additions,
        deletions = deletions,
        patch = firstString("patch", "diff")?.takeIf { it.isNotBlank() },
    )
}

private fun JsonObject.inferredDiffStatus(): String? = when {
    boolean("added") == true -> "added"
    boolean("deleted") == true || boolean("removed") == true -> "deleted"
    boolean("renamed") == true -> "renamed"
    else -> null
}

private fun JsonObject.firstString(vararg names: String): String? {
    for (name in names) {
        string(name)?.let { return it }
    }
    return null
}

private fun JsonObject.firstInt(vararg names: String): Int? {
    for (name in names) {
        this[name]?.stringPrimitiveOrNull()?.toIntOrNull()?.let { return it }
    }
    return null
}

private fun JsonObject.boolean(name: String): Boolean? = string(name)?.toBooleanStrictOrNull()

internal fun JsonElement.extractModels(
    config: JsonElement? = null,
    includeHidden: Boolean = false,
    hiddenModels: Set<Pair<String, String>> = emptySet(),
): List<OpenCodeModel> {
    if (this !is JsonObject) return emptyList()
    val connectedIds = connectedProviderIds()
    return providerEntries().flatMap { (providerKey, providerValue) ->
        val provider = providerValue.jsonObjectOrNull() ?: return@flatMap emptyList()
        val providerId = provider.string("id") ?: provider.string("providerID") ?: providerKey
        val providerName = provider.string("name") ?: providerId
        val isConnected = providerId in connectedIds || providerKey in connectedIds
        if (!isConnected) return@flatMap emptyList()
        val visibility = config.modelVisibility(providerId, providerKey)
        val models = provider["models"]?.jsonObjectOrNull()?.entries ?: provider["model"]?.jsonObjectOrNull()?.entries
        models.orEmpty().mapNotNull { (modelKey, modelValue) ->
            val model = modelValue.jsonObjectOrNull()
            val modelId = model?.string("id") ?: model?.string("modelID") ?: modelKey
            val name = model?.string("name") ?: modelId
            val isEnabled = visibility.isEnabled(modelId) && providerId to modelId !in hiddenModels
            if (!includeHidden && !isEnabled) return@mapNotNull null
            OpenCodeModel(
                providerId = providerId,
                providerConfigKey = providerKey,
                modelId = modelId,
                name = name,
                providerName = providerName,
                isFree = name.contains("free", ignoreCase = true),
                isConnected = isConnected,
                variants = model.variantNames(),
                isEnabled = isEnabled,
                contextLimit = model.longAt("limit", "context"),
            )
        }
    }
}

internal fun JsonElement.extractServerDefaultModels(models: List<OpenCodeModel>): List<PromptModelSelection> {
    val payload = this as? JsonObject ?: return emptyList()
    val defaults = payload["default"]?.jsonObjectOrNull() ?: return emptyList()
    val providers = payload.providerEntries().map { (providerKey, providerValue) ->
        val provider = providerValue.jsonObjectOrNull()
        ProviderLookup(
            providerKey = providerKey,
            providerId = provider?.string("id") ?: provider?.string("providerID") ?: providerKey,
            providerName = provider?.string("name") ?: providerKey,
        )
    }
    return defaults.entries.mapNotNull { (defaultProviderKey, defaultModelValue) ->
        val modelId = defaultModelValue.stringPrimitiveOrNull()
            ?: defaultModelValue.jsonObjectOrNull()?.let { model -> model.string("id") ?: model.string("modelID") }
            ?: return@mapNotNull null
        val providerId = providers.firstOrNull {
            it.providerKey == defaultProviderKey || it.providerId == defaultProviderKey
        }?.providerId ?: defaultProviderKey
        models.firstOrNull { model ->
            model.providerId == providerId &&
                model.modelId == modelId &&
                model.providerId.isNotBlank() &&
                model.modelId.isNotBlank() &&
                model.isConnected &&
                model.isEnabled
        }?.let { model ->
            PromptModelSelection(providerId = model.providerId, modelId = model.modelId)
        }
    }.distinctBy { it.providerId to it.modelId }
}

internal fun JsonElement.extractModelSettings(
    config: JsonElement? = null,
    hiddenModels: Set<Pair<String, String>> = emptySet(),
): List<OpenCodeModelSettingsGroup> {
    if (this !is JsonObject) return emptyList()
    val baseModels = extractModels(config = config, includeHidden = true, hiddenModels = hiddenModels)
    val blacklistRows = config.blacklistedModelRows(this, baseModels)
    return (baseModels + blacklistRows)
        .distinctBy { "${it.providerId}/${it.modelId}" }
        .groupBy { it.providerId to it.providerName }
        .map { (provider, models) ->
            OpenCodeModelSettingsGroup(
                providerId = provider.first,
                providerName = provider.second,
                models = models.sortedBy { it.name.lowercase() },
            )
        }
        .sortedBy { it.providerName.lowercase() }
}

private fun List<ServerHiddenModelPreference>.toModelKeySet(): Set<Pair<String, String>> = map { it.providerId to it.modelId }.toSet()

private fun JsonObject?.variantNames(): List<String> = this
    ?.get("variants")
    ?.jsonObjectOrNull()
    ?.keys
    ?.filter { it.isNotBlank() }
    ?.distinct()
    .orEmpty()

private fun JsonElement?.modelVisibility(providerId: String, providerKey: String? = null): ModelVisibility {
    val providerConfig = this?.jsonObjectOrNull()
        ?.get("provider")
        ?.jsonObjectOrNull()
    val providerByKey = providerKey?.let { providerConfig?.get(it) }?.jsonObjectOrNull()
    val providerById = providerConfig?.get(providerId)?.jsonObjectOrNull()
    return ModelVisibility(
        whitelist = (providerByKey?.stringArrayOrNull("whitelist") ?: providerById?.stringArrayOrNull("whitelist"))?.toSet(),
        blacklist = (providerByKey.stringArray("blacklist") + providerById.stringArray("blacklist")).toSet(),
    )
}

private fun JsonElement?.blacklistedModelRows(
    providersPayload: JsonObject,
    existingModels: List<OpenCodeModel>,
): List<OpenCodeModel> {
    val existing = existingModels.map { it.providerId to it.modelId }.toSet()
    val connectedIds = providersPayload.connectedProviderIds()
    val providers = providersPayload.providerEntries().map { (providerKey, providerValue) ->
        val provider = providerValue.jsonObjectOrNull()
        val providerId = provider?.string("id") ?: provider?.string("providerID") ?: providerKey
        ProviderLookup(
            providerKey = providerKey,
            providerId = providerId,
            providerName = provider?.string("name") ?: providerId,
        )
    }
    return this?.jsonObjectOrNull()
        ?.get("provider")
        ?.jsonObjectOrNull()
        ?.entries
        ?.flatMap { (providerConfigKey, providerConfig) ->
            val provider = providers.firstOrNull { it.providerKey == providerConfigKey || it.providerId == providerConfigKey }
                ?: ProviderLookup(providerConfigKey, providerConfigKey, providerConfigKey)
            if (connectedIds.isNotEmpty() && provider.providerId !in connectedIds && provider.providerKey !in connectedIds) return@flatMap emptyList()
            providerConfig.jsonObjectOrNull()
                ?.stringArray("blacklist")
                ?.filter { modelId -> provider.providerId to modelId !in existing }
                ?.map { modelId ->
                    OpenCodeModel(
                        providerId = provider.providerId,
                        providerConfigKey = provider.providerKey,
                        modelId = modelId,
                        name = modelId,
                        providerName = provider.providerName,
                        isConnected = connectedIds.isEmpty() || provider.providerId in connectedIds || provider.providerKey in connectedIds,
                        isEnabled = false,
                    )
                }
                .orEmpty()
        }
        .orEmpty()
}

private data class ProviderLookup(
    val providerKey: String,
    val providerId: String,
    val providerName: String,
) {
    override fun toString(): String =
        "ProviderLookup(providerKey=<redacted>, providerId=<redacted>, providerName=<redacted>)"
}

private data class ModelVisibility(
    val whitelist: Set<String>?,
    val blacklist: Set<String>,
) {
    fun isEnabled(modelId: String): Boolean = (whitelist == null || modelId in whitelist) && modelId !in blacklist

    override fun toString(): String =
        "ModelVisibility(whitelistCount=${whitelist?.size}, blacklistCount=${blacklist.size})"
}

private fun JsonObject?.stringArray(name: String): List<String> = this?.stringArrayOrNull(name).orEmpty()

private fun JsonObject.stringArrayOrNull(name: String): List<String>? {
    val element = this[name] ?: return null
    return (element as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
        ?: emptyList()
}

private fun JsonElement.extractCommands(): List<OpenCodeCommand> = when (this) {
    is JsonArray -> mapIndexedNotNull { index, element -> element.jsonObjectOrNull()?.toCommand(index.toString()) }
    is JsonObject -> commandEntries().mapNotNull { (id, value) -> value.jsonObjectOrNull()?.toCommand(id) }
    else -> emptyList()
}.distinctBy { it.name }
    .sortedWith(compareBy<OpenCodeCommand> { it.source ?: "" }.thenBy { it.name })

private fun JsonObject.toCommand(defaultId: String): OpenCodeCommand? {
    val rawName = string("name") ?: string("id") ?: defaultId
    val name = rawName.removePrefix("/")
    if (name.isBlank()) return null
    return OpenCodeCommand(
        id = string("id") ?: defaultId,
        name = name,
        description = string("description") ?: string("summary"),
        source = string("source") ?: string("type"),
    )
}

internal fun JsonElement.extractMcps(redactor: Redactor = Redactor()): List<OpenCodeMcp> = when (this) {
    is JsonArray -> mapIndexedNotNull { index, element -> element.toMcp(index.toString(), redactor = redactor) }
    is JsonObject -> entries.mapNotNull { (name, element) ->
        element.toMcp(name, defaultNameIsIdentity = true, redactor = redactor)
    }
    else -> emptyList()
}.distinctBy { it.name }
    .sortedBy { it.name.lowercase() }

private fun JsonElement.toMcp(
    defaultName: String,
    defaultNameIsIdentity: Boolean = false,
    redactor: Redactor,
): OpenCodeMcp? {
    val objectValue = jsonObjectOrNull()
    val primitive = stringPrimitiveOrNull()
    val name = objectValue?.string("name")
        ?: objectValue?.string("id")
        ?: if (defaultNameIsIdentity) defaultName else primitive
        ?: defaultName
    if (name.isBlank()) return null
    return OpenCodeMcp(
        name = name,
        status = objectValue?.string("status") ?: primitive?.takeIf { defaultNameIsIdentity },
        error = objectValue?.get("error").redactedError(redactor),
    )
}

internal fun JsonElement.extractLsps(): List<OpenCodeLsp> = when (this) {
    is JsonArray -> mapIndexedNotNull { index, element -> element.toLsp(index.toString()) }
    is JsonObject -> entries.mapNotNull { (id, element) -> element.toLsp(id, defaultIdIsIdentity = true) }
    else -> emptyList()
}.distinctBy { it.id }
    .sortedBy { (it.name ?: it.id).lowercase() }

private fun JsonElement.toLsp(defaultId: String, defaultIdIsIdentity: Boolean = false): OpenCodeLsp? {
    val objectValue = jsonObjectOrNull()
    val primitive = stringPrimitiveOrNull()
    val id = objectValue?.string("id")
        ?: objectValue?.string("name")
        ?: objectValue?.string("language")
        ?: if (defaultIdIsIdentity) defaultId else primitive
        ?: defaultId
    if (id.isBlank()) return null
    return OpenCodeLsp(
        id = id,
        name = objectValue?.string("name") ?: primitive?.takeUnless { defaultIdIsIdentity },
        status = objectValue?.string("status") ?: primitive?.takeIf { defaultIdIsIdentity },
        root = objectValue?.string("root"),
    )
}

internal fun JsonElement.extractPlugins(): List<OpenCodePlugin> {
    val plugin = jsonObjectOrNull()?.get("plugin") ?: return emptyList()
    return when (plugin) {
        is JsonArray -> plugin.mapNotNull { it.pluginNameOrNull() }
        is JsonObject -> plugin.entries.mapNotNull { (name, element) -> element.pluginNameOrNull() ?: name.takeIf { it.isNotBlank() } }
        else -> listOfNotNull(plugin.pluginNameOrNull())
    }.distinct()
        .map { OpenCodePlugin(it) }
}

private fun JsonElement.pluginNameOrNull(): String? {
    stringPrimitiveOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    (this as? JsonArray)
        ?.firstOrNull()
        ?.stringPrimitiveOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return jsonObjectOrNull()?.let { plugin ->
        plugin.string("name") ?: plugin.string("id")
    }?.takeIf { it.isNotBlank() }
}

private fun JsonElement.providerCount(): Int = (this as? JsonObject)?.providerEntries()?.size ?: itemCount()

private fun JsonObject.providerEntries(): List<Pair<String, JsonElement>> = this["all"]
    ?.providerPairs()
    ?: this["providers"]?.providerPairs()
    ?: entries
        .filterNot { it.key == "default" || it.key == "connected" }
        .filter { it.value.jsonObjectOrNull()?.let { provider -> provider["models"] != null || provider["model"] != null } == true }
        .map { it.toPair() }

private fun JsonElement.providerPairs(): List<Pair<String, JsonElement>>? = when (this) {
    is JsonArray -> mapIndexedNotNull { index, element ->
        val provider = element.jsonObjectOrNull() ?: return@mapIndexedNotNull null
        (provider.string("id") ?: index.toString()) to element
    }
    is JsonObject -> entries.map { it.toPair() }
    else -> null
}

private fun JsonObject.commandEntries(): List<Pair<String, JsonElement>> = this["commands"]
    ?.jsonObjectOrNull()
    ?.entries
    ?.map { it.toPair() }
    ?: entries.map { it.toPair() }

private fun JsonObject.connectedProviderIds(): Set<String> {
    val connected = this["connected"] ?: return emptySet()
    return when (connected) {
        is JsonArray -> connected.mapNotNull { element ->
            element.jsonObjectOrNull()?.let { it.string("id") ?: it.string("providerID") }
                ?: runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        }.toSet()
        is JsonObject -> connected.keys
        else -> emptySet()
    }
}

private fun JsonObject.valuesOrNestedList(name: String): List<Pair<String, JsonElement>> = this[name]
    ?.jsonObjectOrNull()
    ?.entries
    ?.map { it.toPair() }
    ?: entries.map { it.toPair() }

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name]?.jsonObjectOrNull()

private fun JsonElement.toolStateStatus(): String? = jsonObjectOrNull()?.string("status")

private fun JsonElement.toolStateInput(): Map<String, String> = jsonObjectOrNull()
    ?.objectOrNull("input")
    ?.toStringMap()
    .orEmpty()

private fun JsonElement.toolStateInputJson(): String? = jsonObjectOrNull()
    ?.objectOrNull("input")
    ?.toString()

private fun JsonElement.toolStateMetadata(): Map<String, String> = jsonObjectOrNull()
    ?.objectOrNull("metadata")
    ?.toStringMap()
    .orEmpty()

private fun JsonElement.toolStateMetadataJson(): String? = jsonObjectOrNull()
    ?.objectOrNull("metadata")
    ?.toString()

private fun JsonElement.toolStateOutput(): String? = jsonObjectOrNull()
    ?.get("output")
    ?.stringOrJson()

private fun JsonElement.toolStateError(redactor: Redactor): String? = jsonObjectOrNull()
    ?.get("error")
    .redactedError(redactor)

private fun JsonElement?.redactedError(redactor: Redactor): String? = this
    ?.let(redactor::redact)
    ?.stringOrJson()
    ?.let(redactor::redact)

private fun JsonObject.toStringMap(): Map<String, String> = entries.mapNotNull { (key, value) ->
    val text = value.stringOrJson()
    text?.let { key to it }
}.toMap()

private fun JsonObject.string(name: String): String? = this[name]?.stringPrimitiveOrNull()

private fun JsonObject.long(name: String): Long? = this[name]?.stringPrimitiveOrNull()?.toLongOrNull()

private fun JsonObject?.longAt(parent: String, child: String): Long? = this
    ?.get(parent)
    ?.jsonObjectOrNull()
    ?.long(child)

private fun JsonElement.stringPrimitiveOrNull(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()

private fun JsonElement.stringOrJson(): String? = stringPrimitiveOrNull() ?: toString().takeIf { it.isNotBlank() }
