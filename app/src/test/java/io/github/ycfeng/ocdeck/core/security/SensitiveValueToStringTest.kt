package io.github.ycfeng.ocdeck.core.security

import io.github.ycfeng.ocdeck.core.network.ActiveSessionMessagesRequest
import io.github.ycfeng.ocdeck.core.network.BoundedSseEvent
import io.github.ycfeng.ocdeck.core.network.CommandRequestDto
import io.github.ycfeng.ocdeck.core.network.FileContentDto
import io.github.ycfeng.ocdeck.core.network.FileEntryDto
import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorGeneration
import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorReadyResult
import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorTunnel
import io.github.ycfeng.ocdeck.core.network.LoadedActiveSessionMessages
import io.github.ycfeng.ocdeck.core.network.LoadedProjectSnapshot
import io.github.ycfeng.ocdeck.core.network.MessageInfoDto
import io.github.ycfeng.ocdeck.core.network.MessagePartDto
import io.github.ycfeng.ocdeck.core.network.MessageWithPartsDto
import io.github.ycfeng.ocdeck.core.network.OpenCodeApi
import io.github.ycfeng.ocdeck.core.network.PathInfoDto
import io.github.ycfeng.ocdeck.core.network.ProjectDto
import io.github.ycfeng.ocdeck.core.network.ProjectIconDto
import io.github.ycfeng.ocdeck.core.network.ProjectSnapshotOutcome
import io.github.ycfeng.ocdeck.core.network.ProjectSnapshotSource
import io.github.ycfeng.ocdeck.core.network.ProjectSnapshotToken
import io.github.ycfeng.ocdeck.core.network.PromptPartDto
import io.github.ycfeng.ocdeck.core.network.PromptRequestDto
import io.github.ycfeng.ocdeck.core.network.QuestionInfoDto
import io.github.ycfeng.ocdeck.core.network.QuestionOptionDto
import io.github.ycfeng.ocdeck.core.network.QuestionReplyRequestDto
import io.github.ycfeng.ocdeck.core.network.ServerHealthDto
import io.github.ycfeng.ocdeck.core.network.SessionMessagesPage
import io.github.ycfeng.ocdeck.core.network.SseConnection
import io.github.ycfeng.ocdeck.core.network.SshRemoteEndpoint
import io.github.ycfeng.ocdeck.core.network.SshTunnel
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.core.store.OpenCodeRuntimeState
import io.github.ycfeng.ocdeck.core.store.MessageHistoryState
import io.github.ycfeng.ocdeck.core.store.ProjectKey
import io.github.ycfeng.ocdeck.core.store.SseConnectionStatus
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.server.FrpcWireProtocol
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerConnection
import io.github.ycfeng.ocdeck.data.server.ServerFrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.data.server.ServerSshTunnelConfig
import io.github.ycfeng.ocdeck.data.server.ServerTransportIdentity
import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommentOrigin
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommentSelection
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommand
import io.github.ycfeng.ocdeck.domain.model.OpenCodeDiffFile
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileContent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileEntry
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileType
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageBundle
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageComment
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodePathInfo
import io.github.ycfeng.ocdeck.domain.model.OpenCodePermissionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProjectSnapshot
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionInfo
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionOption
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionRevert
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.domain.model.ProjectIcon
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachment
import io.github.ycfeng.ocdeck.domain.prompt.PromptOperationKey
import io.github.ycfeng.ocdeck.domain.prompt.PromptSendAction
import io.github.ycfeng.ocdeck.domain.prompt.PromptSendInput
import io.github.ycfeng.ocdeck.domain.prompt.PromptSendResult
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContext
import io.github.ycfeng.ocdeck.feature.session.PatchFileDisplay
import io.github.ycfeng.ocdeck.feature.session.ReviewMode
import io.github.ycfeng.ocdeck.feature.session.SessionContextMessage
import io.github.ycfeng.ocdeck.feature.session.SessionContextUsage
import io.github.ycfeng.ocdeck.feature.session.SessionDetailTab
import io.github.ycfeng.ocdeck.feature.session.SessionDetailUiState
import io.github.ycfeng.ocdeck.feature.session.SessionMessageProjection
import io.github.ycfeng.ocdeck.feature.session.SessionRevertDraft
import io.github.ycfeng.ocdeck.feature.session.ShellToolDisplay
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveValueToStringTest {
    @Test
    fun sensitiveAndLargeValuesNeverAppearInSummaries() {
        val sshConfig = ServerSshTunnelConfig(
            host = SYNTHETIC_HOST,
            username = SYNTHETIC_SERVER_TEXT,
            passwordKey = SYNTHETIC_CREDENTIAL_ALIAS,
            privateKeyKey = SYNTHETIC_CREDENTIAL_ALIAS,
            privateKeyFileName = SYNTHETIC_FILENAME,
            passphraseKey = SYNTHETIC_CREDENTIAL_ALIAS,
            hostFingerprintKey = SYNTHETIC_CREDENTIAL_ALIAS,
        )
        val stcpConfig = ServerFrpcStcpVisitorConfig(
            serverAddr = SYNTHETIC_HOST,
            authTokenKey = SYNTHETIC_CREDENTIAL_ALIAS,
            user = SYNTHETIC_SERVER_TEXT,
            serverUser = SYNTHETIC_SERVER_TEXT,
            serverName = SYNTHETIC_SERVER_TEXT,
            secretKeyKey = SYNTHETIC_CREDENTIAL_ALIAS,
            wireProtocol = FrpcWireProtocol.V2,
        )
        val server = ServerConfig(
            id = SYNTHETIC_SERVER_ID,
            name = SYNTHETIC_SERVER_TEXT,
            baseUrl = SYNTHETIC_URL,
            username = SYNTHETIC_SERVER_TEXT,
            passwordKey = SYNTHETIC_CREDENTIAL_ALIAS,
            sshTunnel = sshConfig,
            frpcStcpVisitor = stcpConfig,
        )
        val attachment = PromptAttachment(
            id = SYNTHETIC_PART_ID,
            filename = SYNTHETIC_FILENAME,
            mime = "application/octet-stream",
            dataUrl = SYNTHETIC_DATA_URL,
            sizeBytes = 24,
        )
        val projectFileContext = ProjectFileContext(
            id = SYNTHETIC_PART_ID,
            relativePath = SYNTHETIC_PATH,
        )
        val comment = OpenCodeMessageComment(
            path = SYNTHETIC_PATH,
            selection = OpenCodeCommentSelection(1, 0, 2, 4),
            comment = SYNTHETIC_PROMPT,
            preview = SYNTHETIC_SERVER_TEXT,
            origin = OpenCodeCommentOrigin.Review,
        )
        val part = OpenCodeMessagePart(
            id = SYNTHETIC_PART_ID,
            messageId = SYNTHETIC_MESSAGE_ID,
            sessionId = SYNTHETIC_SESSION_ID,
            type = "tool",
            text = SYNTHETIC_PROMPT,
            synthetic = false,
            filename = SYNTHETIC_FILENAME,
            mime = "application/octet-stream",
            url = SYNTHETIC_DATA_URL,
            tool = SYNTHETIC_SERVER_TEXT,
            stateStatus = "completed",
            toolInput = mapOf("path" to SYNTHETIC_PATH, "prompt" to SYNTHETIC_PROMPT),
            toolInputJson = "{\"prompt\":\"$SYNTHETIC_PROMPT\"}",
            toolMetadata = mapOf("filename" to SYNTHETIC_FILENAME),
            toolMetadataJson = "{\"path\":\"$SYNTHETIC_PATH\"}",
            toolOutput = SYNTHETIC_TOOL_OUTPUT,
            toolError = SYNTHETIC_SERVER_TEXT,
            opencodeComment = comment,
        )
        val message = OpenCodeMessage(
            id = SYNTHETIC_MESSAGE_ID,
            sessionId = SYNTHETIC_SESSION_ID,
            role = "user",
            text = SYNTHETIC_PROMPT,
            parts = listOf(part),
            parentId = SYNTHETIC_MESSAGE_ID,
            agent = SYNTHETIC_SERVER_TEXT,
            modelLabel = SYNTHETIC_SERVER_TEXT,
            modelProviderId = SYNTHETIC_SERVER_TEXT,
            modelId = SYNTHETIC_SERVER_TEXT,
            modelVariant = SYNTHETIC_SERVER_TEXT,
            error = SYNTHETIC_SERVER_TEXT,
        )
        val diff = OpenCodeDiffFile(
            file = SYNTHETIC_PATH,
            oldFile = "$SYNTHETIC_PATH.old",
            status = "modified",
            additions = 3,
            deletions = 1,
            patch = SYNTHETIC_PATCH,
        )
        val questionInfo = OpenCodeQuestionInfo(
            header = SYNTHETIC_SERVER_TEXT,
            question = SYNTHETIC_PROMPT,
            options = listOf(OpenCodeQuestionOption(SYNTHETIC_SERVER_TEXT, SYNTHETIC_TOOL_OUTPUT)),
            multiple = true,
        )
        val question = OpenCodeQuestionRequest(
            id = SYNTHETIC_REQUEST_ID,
            sessionId = SYNTHETIC_SESSION_ID,
            questions = listOf(questionInfo),
        )
        val permission = OpenCodePermissionRequest(
            id = SYNTHETIC_REQUEST_ID,
            sessionId = SYNTHETIC_SESSION_ID,
            permission = "bash",
            patterns = listOf(SYNTHETIC_PATH),
            always = listOf(SYNTHETIC_PROMPT),
        )
        val projectRef = ProjectRef(
            normalizedDirectory = SYNTHETIC_PATH,
            projectId = SYNTHETIC_PROJECT_ID,
            displayName = SYNTHETIC_SERVER_TEXT,
            vcs = "git",
            icon = ProjectIcon(color = "blue", url = SYNTHETIC_URL),
        )
        val session = OpenCodeSession(
            id = SYNTHETIC_SESSION_ID,
            title = SYNTHETIC_SERVER_TEXT,
            normalizedDirectory = SYNTHETIC_PATH,
            path = SYNTHETIC_PATH,
            parentId = SYNTHETIC_SESSION_ID,
            agent = SYNTHETIC_SERVER_TEXT,
            modelLabel = SYNTHETIC_SERVER_TEXT,
            updatedAt = null,
            archivedAt = null,
            modelProviderId = SYNTHETIC_SERVER_TEXT,
            modelId = SYNTHETIC_SERVER_TEXT,
            modelVariant = SYNTHETIC_SERVER_TEXT,
            revert = OpenCodeSessionRevert(SYNTHETIC_MESSAGE_ID, SYNTHETIC_PART_ID),
        )
        val model = OpenCodeModel(
            providerId = SYNTHETIC_SERVER_TEXT,
            providerConfigKey = SYNTHETIC_CREDENTIAL_ALIAS,
            modelId = SYNTHETIC_SERVER_TEXT,
            name = SYNTHETIC_SERVER_TEXT,
            providerName = SYNTHETIC_SERVER_TEXT,
            variants = listOf(SYNTHETIC_SERVER_TEXT),
            isConnected = true,
        )
        val agent = OpenCodeAgent(SYNTHETIC_SERVER_TEXT, SYNTHETIC_SERVER_TEXT, SYNTHETIC_SERVER_TEXT)
        val command = OpenCodeCommand(
            id = SYNTHETIC_SERVER_TEXT,
            name = SYNTHETIC_SERVER_TEXT,
            description = SYNTHETIC_PROMPT,
            source = SYNTHETIC_PATH,
        )
        val capabilities = PromptCapabilities(
            models = listOf(model),
            agents = listOf(agent),
            commands = listOf(command),
            serverDefaultModels = listOf(
                PromptModelSelection(SYNTHETIC_SERVER_TEXT, SYNTHETIC_SERVER_TEXT, SYNTHETIC_SERVER_TEXT),
            ),
            isLoaded = true,
            revision = 17,
        )
        val snapshot = OpenCodeProjectSnapshot(
            serverId = SYNTHETIC_SERVER_ID,
            normalizedDirectory = SYNTHETIC_PATH,
            workspace = SYNTHETIC_PATH,
            project = projectRef,
            pathInfo = OpenCodePathInfo(
                home = SYNTHETIC_PATH,
                state = SYNTHETIC_PATH,
                config = SYNTHETIC_PATH,
                worktree = SYNTHETIC_PATH,
                directory = SYNTHETIC_PATH,
            ),
            sessions = listOf(session),
            statuses = emptyMap(),
            providerCount = 1,
            models = listOf(model),
            agents = listOf(agent),
            commands = listOf(command),
            promptCapabilities = capabilities,
            mcps = emptyList(),
            lsps = emptyList(),
            plugins = emptyList(),
            commandCount = 1,
            permissionCount = 1,
            questionCount = 1,
            permissions = listOf(permission),
            questions = listOf(question),
        )
        val projectState = OpenCodeProjectState(
            serverId = SYNTHETIC_SERVER_ID,
            normalizedDirectory = SYNTHETIC_PATH,
            workspace = SYNTHETIC_PATH,
            project = projectRef,
            pathInfo = snapshot.pathInfo,
            sessions = listOf(session),
            messagesBySession = mapOf(SYNTHETIC_SESSION_ID to listOf(message)),
            partsByMessage = mapOf(SYNTHETIC_MESSAGE_ID to listOf(part)),
            messageHistoryBySession = mapOf(
                SYNTHETIC_SESSION_ID to MessageHistoryState(
                    SYNTHETIC_MESSAGE_ID,
                    consumedCursors = setOf(SYNTHETIC_SECRET),
                    latestPageMessageIds = setOf(SYNTHETIC_SECRET),
                ),
            ),
            messageFirstPageRequestGenerations = mapOf(SYNTHETIC_SECRET to 31L),
            permissionsBySession = mapOf(SYNTHETIC_SESSION_ID to listOf(permission)),
            questionsBySession = mapOf(SYNTHETIC_SESSION_ID to listOf(question)),
            models = listOf(model),
            agents = listOf(agent),
            commands = listOf(command),
            promptCapabilities = capabilities,
            activeSessionId = SYNTHETIC_SESSION_ID,
            projectDataRevision = 23,
            messageDataRevision = 29,
        )
        val projectKey = ProjectKey.create(
            serverId = SYNTHETIC_SERVER_ID,
            directory = SYNTHETIC_PATH,
            workspace = SYNTHETIC_PATH,
            pathNormalizer = PathNormalizer(),
        )
        val generation = FrpcStcpVisitorGeneration(SYNTHETIC_SERVER_ID, configEpoch = 31, ordinal = 37)
        val transportIdentity = ServerTransportIdentity(
            configEpoch = 31,
            stcpGeneration = generation,
            stcpControlEpoch = 41,
        )
        val tunnel = FrpcStcpVisitorTunnel(
            sessionId = SYNTHETIC_SESSION_ID,
            visitorName = SYNTHETIC_VISITOR_ID,
            localPort = 4096,
            desiredRevision = 43,
            controlEpoch = 41,
            effectiveBaseUrl = SYNTHETIC_URL,
        )
        val messageBundle = OpenCodeMessageBundle(listOf(message), listOf(part))
        val messagePage = OpenCodeMessagePage(messageBundle, SYNTHETIC_MESSAGE_ID)
        val transportMessagePage = SessionMessagesPage(
            listOf(
                MessageWithPartsDto(
                    MessageInfoDto(
                        id = SYNTHETIC_MESSAGE_ID,
                        sessionID = SYNTHETIC_SESSION_ID,
                        role = "assistant",
                    ),
                    emptyList(),
                ),
            ),
            SYNTHETIC_MESSAGE_ID,
        )
        val loadedMessages = LoadedActiveSessionMessages(
            sessionId = SYNTHETIC_SESSION_ID,
            requestGeneration = 23,
            expectedRevision = 29,
            bundle = messageBundle,
            nextCursor = SYNTHETIC_MESSAGE_ID,
        )
        val loadedSnapshot = LoadedProjectSnapshot(snapshot, transportIdentity, loadedMessages)
        val snapshotToken = ProjectSnapshotToken(
            key = projectKey,
            source = ProjectSnapshotSource.Project,
            generation = 47,
            recoveryId = 53,
            sourceId = 59,
            calibrationId = 61,
            transportIdentity = transportIdentity,
        )
        val rawJson = Json.parseToJsonElement("{\"payload\":\"$SYNTHETIC_TOOL_OUTPUT\"}")
        val promptPartDto = PromptPartDto(
            id = SYNTHETIC_PART_ID,
            type = "file",
            text = SYNTHETIC_PROMPT,
            mime = "application/octet-stream",
            url = SYNTHETIC_DATA_URL,
            filename = SYNTHETIC_FILENAME,
        )
        val messagePartDto = MessagePartDto(
            id = SYNTHETIC_PART_ID,
            messageID = SYNTHETIC_MESSAGE_ID,
            sessionID = SYNTHETIC_SESSION_ID,
            type = "tool",
            text = SYNTHETIC_PROMPT,
            synthetic = false,
            filename = SYNTHETIC_FILENAME,
            mime = "application/octet-stream",
            url = SYNTHETIC_DATA_URL,
            tool = SYNTHETIC_SERVER_TEXT,
            state = rawJson,
            metadata = rawJson,
        )
        val messageInfoDto = MessageInfoDto(
            id = SYNTHETIC_MESSAGE_ID,
            sessionID = SYNTHETIC_SESSION_ID,
            role = "assistant",
            parentID = SYNTHETIC_MESSAGE_ID,
            agent = SYNTHETIC_SERVER_TEXT,
            mode = "build",
            modelID = SYNTHETIC_SERVER_TEXT,
            providerID = SYNTHETIC_SERVER_TEXT,
            variant = SYNTHETIC_SERVER_TEXT,
            finish = "stop",
            error = rawJson,
        )
        val contextUsage = SessionContextUsage(
            sessionTitle = SYNTHETIC_SERVER_TEXT,
            messageCount = 1,
            providerId = SYNTHETIC_SERVER_TEXT,
            modelName = SYNTHETIC_SERVER_TEXT,
            contextLimit = 128_000,
            currentTurnTokens = 13,
            totalTokens = 21,
            usagePercent = 1,
            usageRatio = 0.01f,
            inputTokens = 8,
            outputTokens = 5,
            reasoningTokens = 3,
            cacheReadTokens = 2,
            cacheWriteTokens = 1,
            userMessageCount = 1,
            assistantMessageCount = 0,
            cost = 0.01,
            createdAt = 67,
            updatedAt = 71,
            rawMessages = listOf(SessionContextMessage("user", SYNTHETIC_MESSAGE_ID, 67)),
        )
        val uiState = SessionDetailUiState(
            serverId = SYNTHETIC_SERVER_ID,
            directory = SYNTHETIC_PATH,
            project = projectState,
            serverConfig = server,
            serverVersion = SYNTHETIC_SERVER_TEXT,
            mcpActionName = SYNTHETIC_SERVER_TEXT,
            currentSessionId = SYNTHETIC_SESSION_ID,
            activeTab = SessionDetailTab.Changes,
            title = SYNTHETIC_SERVER_TEXT,
            isChildSession = true,
            parentSessionId = SYNTHETIC_SESSION_ID,
            parentTitle = SYNTHETIC_SERVER_TEXT,
            childTitle = SYNTHETIC_SERVER_TEXT,
            messages = listOf(message),
            visibleMessages = listOf(message),
            revertedUserMessages = listOf(message),
            revert = session.revert,
            agents = listOf(agent),
            models = listOf(model),
            commands = listOf(command),
            composer = SYNTHETIC_PROMPT,
            attachments = listOf(attachment),
            projectFileContexts = listOf(projectFileContext),
            selectedAgent = SYNTHETIC_SERVER_TEXT,
            selectedModel = PromptModelSelection(
                SYNTHETIC_SERVER_TEXT,
                SYNTHETIC_SERVER_TEXT,
                SYNTHETIC_SERVER_TEXT,
            ),
            selectedModelLabel = SYNTHETIC_SERVER_TEXT,
            selectedVariant = SYNTHETIC_SERVER_TEXT,
            contextUsage = contextUsage,
            pendingQuestion = question,
            pendingPermission = permission,
            reviewMode = ReviewMode.Git,
            availableReviewModes = listOf(ReviewMode.Git, ReviewMode.Turn),
            reviewDiffs = listOf(diff),
            isReviewLoading = false,
            reviewError = null,
            isGitProject = true,
            status = "working",
            isWorking = true,
            isLoading = false,
            hasOlderMessages = true,
            isLoadingOlderMessages = true,
            isSending = true,
            isReadingAttachments = false,
            isQuestionActionInProgress = false,
            isPermissionActionInProgress = false,
            isSessionActionInProgress = false,
            isRevertActionInProgress = false,
            isRevertBranchCommitting = true,
            isUpdatingProjectName = false,
            projectNameError = null,
            submitAction = PromptSendAction.SendPrompt,
            error = null,
        )
        val serverConnection = ServerConnection(
            server = server,
            password = SYNTHETIC_SECRET,
            effectiveBaseUrl = SYNTHETIC_URL,
            api = fakeApi(),
            stcpGeneration = generation,
            stcpControlEpoch = 41,
            transportIdentity = transportIdentity,
            readinessHealth = ServerHealthDto(healthy = true, version = SYNTHETIC_SERVER_TEXT),
        )

        val values = listOf<Any>(
            server,
            sshConfig,
            stcpConfig,
            serverConnection,
            attachment,
            projectFileContext,
            OpenCodeFileEntry(SYNTHETIC_FILENAME, SYNTHETIC_PATH, OpenCodeFileType.File, ignored = false),
            OpenCodeFileContent.Text(SYNTHETIC_PATH, SYNTHETIC_SERVER_TEXT),
            OpenCodeFileContent.Binary(SYNTHETIC_PATH, SYNTHETIC_BASE64, "application/octet-stream"),
            projectRef,
            projectRef.icon!!,
            session,
            session.revert!!,
            message,
            comment,
            part,
            messageBundle,
            messagePage,
            transportMessagePage,
            diff,
            permission,
            question,
            questionInfo,
            model,
            agent,
            command,
            capabilities,
            snapshot,
            projectKey,
            projectState,
            OpenCodeRuntimeState(
                projects = mapOf(projectKey to projectState),
                globalConnectionStatuses = mapOf(SYNTHETIC_SERVER_ID to SseConnectionStatus.Open),
            ),
            BoundedSseEvent(SYNTHETIC_MESSAGE_ID, SYNTHETIC_SERVER_TEXT, SYNTHETIC_SSE_DATA),
            ActiveSessionMessagesRequest(SYNTHETIC_SESSION_ID, 23, 29),
            loadedMessages,
            loadedSnapshot,
            snapshotToken,
            ProjectSnapshotOutcome.Success(loadedSnapshot),
            PathInfoDto(SYNTHETIC_PATH, SYNTHETIC_PATH, SYNTHETIC_PATH, SYNTHETIC_PATH, SYNTHETIC_PATH),
            FileEntryDto(SYNTHETIC_FILENAME, SYNTHETIC_PATH, SYNTHETIC_PATH, "file", false),
            FileContentDto("binary", SYNTHETIC_BASE64, "base64", "application/octet-stream"),
            ProjectDto(
                SYNTHETIC_PROJECT_ID,
                SYNTHETIC_PATH,
                "git",
                SYNTHETIC_SERVER_TEXT,
                ProjectIconDto("blue", SYNTHETIC_URL),
            ),
            PromptRequestDto(
                messageID = SYNTHETIC_MESSAGE_ID,
                parts = listOf(promptPartDto),
                agent = SYNTHETIC_SERVER_TEXT,
                variant = SYNTHETIC_SERVER_TEXT,
            ),
            promptPartDto,
            CommandRequestDto(
                messageID = SYNTHETIC_MESSAGE_ID,
                command = SYNTHETIC_PROMPT,
                arguments = SYNTHETIC_TOOL_OUTPUT,
                parts = listOf(promptPartDto),
                agent = SYNTHETIC_SERVER_TEXT,
                model = SYNTHETIC_SERVER_TEXT,
                variant = SYNTHETIC_SERVER_TEXT,
            ),
            QuestionInfoDto(
                header = SYNTHETIC_SERVER_TEXT,
                question = SYNTHETIC_PROMPT,
                options = listOf(QuestionOptionDto(SYNTHETIC_SERVER_TEXT, SYNTHETIC_TOOL_OUTPUT)),
                multiple = true,
                custom = true,
            ),
            QuestionReplyRequestDto(listOf(listOf(SYNTHETIC_PROMPT, SYNTHETIC_TOOL_OUTPUT))),
            messageInfoDto,
            messagePartDto,
            MessageWithPartsDto(messageInfoDto, listOf(messagePartDto)),
            generation,
            tunnel,
            FrpcStcpVisitorReadyResult(tunnel, generation, ServerHealthDto(true, SYNTHETIC_SERVER_TEXT)),
            transportIdentity,
            SshTunnel(SYNTHETIC_URL, SYNTHETIC_SECRET),
            SshRemoteEndpoint(SYNTHETIC_HOST, 22),
            SseConnection(server, SYNTHETIC_SECRET, SYNTHETIC_URL, transportIdentity),
            PromptOperationKey(SYNTHETIC_SERVER_ID, SYNTHETIC_PATH, SYNTHETIC_PATH, SYNTHETIC_SESSION_ID),
            PromptSendInput(SYNTHETIC_PROMPT, hasAttachments = true, hasContext = true, isWorking = true),
            PromptSendResult(SYNTHETIC_SESSION_ID, SYNTHETIC_MESSAGE_ID, materializedNewSession = true),
            SessionMessageProjection(listOf(message), listOf(message)),
            SessionRevertDraft(SYNTHETIC_PROMPT, listOf(attachment), listOf(projectFileContext)),
            ShellToolDisplay(SYNTHETIC_PROMPT, SYNTHETIC_TOOL_OUTPUT, SYNTHETIC_SERVER_TEXT, pending = false),
            PatchFileDisplay(
                filePath = SYNTHETIC_PATH,
                relativePath = SYNTHETIC_FILENAME,
                type = "update",
                additions = 3,
                deletions = 1,
                movePath = SYNTHETIC_PATH,
                diff = SYNTHETIC_PATCH,
            ),
            contextUsage,
            contextUsage.rawMessages.single(),
            uiState,
        )
        val summaries = values.map { it.toString() }
        val combined = summaries.joinToString("\n")

        FORBIDDEN_VALUES.forEach { forbidden ->
            assertFalse("Summary leaked synthetic value: $forbidden\n$combined", combined.contains(forbidden))
        }
        assertTrue(combined.contains(Redactor.REDACTED))
        assertTrue(server.toString().contains("sshTunnelPresent=true"))
        assertTrue(projectState.toString().contains("projectDataRevision=23"))
        assertTrue(BoundedSseEvent(null, null, SYNTHETIC_SSE_DATA).toString().contains("dataLength="))
    }

    private fun fakeApi(): OpenCodeApi {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "toString" -> "FakeOpenCodeApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        return Proxy.newProxyInstance(
            OpenCodeApi::class.java.classLoader,
            arrayOf(OpenCodeApi::class.java),
            handler,
        ) as OpenCodeApi
    }

    private companion object {
        const val SYNTHETIC_SECRET = "fixture-secret-value-7fa3"
        const val SYNTHETIC_URL = "https://fixture-user:fixture-password@example.test/private?token=fixture-token"
        const val SYNTHETIC_HOST = "private-host.example.test"
        const val SYNTHETIC_PATH = "X:/private-workspace/fixture-project/private-file.txt"
        const val SYNTHETIC_FILENAME = "private-fixture-name.bin"
        const val SYNTHETIC_PROMPT = "fixture prompt text that must never appear"
        const val SYNTHETIC_BASE64 = "U1lOVEhFVElDX0JBU0U2NF9QQVlMT0FE"
        const val SYNTHETIC_DATA_URL = "data:application/octet-stream;base64,$SYNTHETIC_BASE64"
        const val SYNTHETIC_TOOL_OUTPUT = "fixture tool stdout that must never appear"
        const val SYNTHETIC_PATCH = "@@ fixture-private-patch @@"
        const val SYNTHETIC_SSE_DATA = "{\"payload\":\"fixture private SSE data\"}"
        const val SYNTHETIC_SERVER_TEXT = "fixture-private-server-text"
        const val SYNTHETIC_SERVER_ID = "server-private-id"
        const val SYNTHETIC_PROJECT_ID = "project-private-id"
        const val SYNTHETIC_SESSION_ID = "session-private-id"
        const val SYNTHETIC_MESSAGE_ID = "message-private-id"
        const val SYNTHETIC_PART_ID = "part-private-id"
        const val SYNTHETIC_REQUEST_ID = "request-private-id"
        const val SYNTHETIC_VISITOR_ID = "visitor-private-id"
        const val SYNTHETIC_CREDENTIAL_ALIAS = "credential-alias-private-id"

        val FORBIDDEN_VALUES = listOf(
            SYNTHETIC_SECRET,
            SYNTHETIC_URL,
            SYNTHETIC_HOST,
            SYNTHETIC_PATH,
            SYNTHETIC_FILENAME,
            SYNTHETIC_PROMPT,
            SYNTHETIC_BASE64,
            SYNTHETIC_DATA_URL,
            SYNTHETIC_TOOL_OUTPUT,
            SYNTHETIC_PATCH,
            SYNTHETIC_SSE_DATA,
            SYNTHETIC_SERVER_TEXT,
            SYNTHETIC_SERVER_ID,
            SYNTHETIC_PROJECT_ID,
            SYNTHETIC_SESSION_ID,
            SYNTHETIC_MESSAGE_ID,
            SYNTHETIC_PART_ID,
            SYNTHETIC_REQUEST_ID,
            SYNTHETIC_VISITOR_ID,
            SYNTHETIC_CREDENTIAL_ALIAS,
        )
    }
}
