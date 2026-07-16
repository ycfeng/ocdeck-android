package io.github.ycfeng.ocdeck.domain.prompt

import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommand
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProjectSnapshot
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodePromptSenderTest {
    @Test
    fun validationFailuresHaveNoStoreCreateOrSendSideEffects() = runTest {
        val fixture = fixture()

        assertFailure<PromptCapabilitiesNotLoadedException>(
            fixture.send(capabilities = PromptCapabilities()),
        )
        assertFailure<PromptModelSelectionRequiredException>(
            fixture.send(model = null),
        )
        assertFailure<PromptModelUnavailableException>(
            fixture.send(model = PromptModelSelection("", "gpt-5.5")),
        )
        assertFailure<PromptModelUnavailableException>(
            fixture.send(model = PromptModelSelection("missing", "missing")),
        )
        assertFailure<PromptAgentUnavailableException>(
            fixture.send(agent = null),
        )
        assertFailure<PromptAgentUnavailableException>(
            fixture.send(agent = "general"),
        )
        assertFailure<PromptVariantUnavailableException>(
            fixture.send(variant = "xhigh"),
        )

        fixture.assertNoGatewayCalls()
        assertTrue(fixture.project().messagesBySession.values.flatten().isEmpty())
    }

    @Test
    fun disconnectedAndDisabledModelsAreRejectedWhileLegalSelectionIsSent() = runTest {
        val fixture = fixture()
        val disconnected = validModel.copy(providerId = "offline", modelId = "offline", isConnected = false)
        val disabled = validModel.copy(providerId = "hidden", modelId = "hidden", isEnabled = false)
        val capabilities = capabilities(models = listOf(validModel, disconnected, disabled))

        assertFailure<PromptModelUnavailableException>(
            fixture.send(model = PromptModelSelection("offline", "offline"), capabilities = capabilities),
        )
        assertFailure<PromptModelUnavailableException>(
            fixture.send(model = PromptModelSelection("hidden", "hidden"), capabilities = capabilities),
        )

        val success = fixture.send(model = validSelection, variant = "high", capabilities = capabilities)

        assertTrue(success.isSuccess)
        assertEquals(validSelection.copy(variant = "high"), fixture.gateway.promptCalls.single().model)
    }

    @Test
    fun agentMustBeExactLoadedBuildOrPlan() = runTest {
        val fixture = fixture()

        assertFailure<PromptAgentUnavailableException>(fixture.send(agent = "Build"))
        assertFailure<PromptAgentUnavailableException>(
            fixture.send(
                agent = "plan",
                capabilities = capabilities(agents = listOf(OpenCodeAgent("build", "Build", null))),
            ),
        )

        val success = fixture.send(
            sessionId = "ses_plan",
            agent = "plan",
            capabilities = capabilities(),
        )

        assertTrue(success.isSuccess)
        assertEquals("plan", fixture.gateway.promptCalls.single().agent)
    }

    @Test
    fun nullAndSupportedVariantsSendButUnsupportedVariantDoesNot() = runTest {
        val fixture = fixture()

        assertFailure<PromptVariantUnavailableException>(fixture.send(variant = "max"))
        assertTrue(fixture.send(sessionId = "ses_default", variant = null).isSuccess)
        assertTrue(fixture.send(sessionId = "ses_high", variant = "high").isSuccess)

        assertEquals(listOf(null, "high"), fixture.gateway.promptCalls.map { it.variant })
    }

    @Test
    fun slashCommandRoutingUsesExactLoadedFirstTokenAndAnyWhitespace() = runTest {
        val fixture = fixture()

        assertTrue(fixture.send(sessionId = "ses_known", text = "  /review\t  alpha beta  ").isSuccess)
        assertTrue(fixture.send(sessionId = "ses_unknown", text = "/unknown keep all").isSuccess)
        assertTrue(fixture.send(sessionId = "ses_double", text = "//review keep all").isSuccess)
        assertTrue(fixture.send(sessionId = "ses_case", text = "/Review keep all").isSuccess)
        assertTrue(
            fixture.send(
                sessionId = "ses_empty_commands",
                text = "/review keep all",
                capabilities = capabilities(commands = emptyList()),
            ).isSuccess,
        )

        val command = fixture.gateway.commandCalls.single()
        assertEquals("review", command.command)
        assertEquals("alpha beta", command.arguments)
        assertEquals(
            listOf("/unknown keep all", "//review keep all", "/Review keep all", "/review keep all"),
            fixture.gateway.promptCalls.map { it.text },
        )
    }

    @Test
    fun contextOnlyPromptNormalizesAndPublishesProjectFilePart() = runTest {
        val fixture = fixture()
        val context = ProjectFileContext("prt_context", "src\\Main.kt")

        val result = fixture.send(
            sessionId = "ses_context",
            text = "",
            projectFileContexts = listOf(context),
        )

        assertTrue(result.isSuccess)
        val normalizedContext = context.copy(relativePath = "src/Main.kt")
        assertEquals(listOf(normalizedContext), fixture.gateway.promptCalls.single().projectFileContexts)
        val part = fixture.project().partsByMessage.getValue(result.getOrThrow().messageId).single()
        assertEquals("prt_context", part.id)
        assertEquals("ses_context", part.sessionId)
        assertEquals("file", part.type)
        assertEquals("text/plain", part.mime)
        assertEquals("Main.kt", part.filename)
        assertEquals("file:///E:/repo/src/Main.kt", part.url)
    }

    @Test
    fun knownCommandCarriesProjectFileContexts() = runTest {
        val fixture = fixture()
        val context = ProjectFileContext("prt_context", "src/Main.kt")

        val result = fixture.send(
            sessionId = "ses_command_context",
            text = "/review inspect",
            projectFileContexts = listOf(context),
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf(context), fixture.gateway.commandCalls.single().projectFileContexts)
    }

    @Test
    fun invalidDuplicateAndExcessProjectContextsHaveNoSideEffects() = runTest {
        val fixture = fixture()

        assertFailure<PromptProjectContextInvalidException>(
            fixture.send(projectFileContexts = listOf(ProjectFileContext("prt_invalid", "../secret.txt"))),
        )
        assertFailure<PromptProjectContextInvalidException>(
            fixture.send(
                projectFileContexts = listOf(
                    ProjectFileContext("prt_first", "src/Main.kt"),
                    ProjectFileContext("prt_second", "SRC\\MAIN.KT"),
                ),
            ),
        )
        assertFailure<PromptProjectContextCountLimitException>(
            fixture.send(
                projectFileContexts = List(ProjectFileContextLimits.MAX_CONTEXT_COUNT + 1) { index ->
                    ProjectFileContext("prt_$index", "src/File$index.kt")
                },
            ),
        )

        fixture.assertNoGatewayCalls()
        assertTrue(fixture.project().messagesBySession.values.flatten().isEmpty())
    }

    @Test
    fun existingSessionSkipsCreateAndMarksOptimisticMessageAccepted() = runTest {
        val fixture = fixture()

        val result = fixture.send(sessionId = "ses_existing", text = "hello")

        assertTrue(result.isSuccess)
        assertTrue(fixture.gateway.createCalls.isEmpty())
        assertEquals("ses_existing", fixture.gateway.promptCalls.single().sessionId)
        val message = fixture.project().messagesBySession.getValue("ses_existing").single()
        assertEquals(result.getOrThrow().messageId, message.id)
        assertFalse(message.isOptimistic)
    }

    @Test
    fun newSessionSuccessMaterializesAndMarksMovedMessageAccepted() = runTest {
        val fixture = fixture()
        fixture.gateway.createHandler = { Result.success(session("ses_created")) }
        var materializedSessionId: String? = null
        val context = ProjectFileContext("prt_context", "src/Main.kt")

        val result = fixture.send(
            sessionId = null,
            projectFileContexts = listOf(context),
            onSessionMaterialized = { materializedSessionId = it },
        )

        assertTrue(result.isSuccess)
        assertEquals("ses_created", materializedSessionId)
        assertEquals("ses_created", fixture.gateway.promptCalls.single().sessionId)
        val project = fixture.project()
        assertTrue(project.sessions.any { it.id == "ses_created" })
        assertTrue(project.messagesBySession[OpenCodePromptSender.NEW_SESSION_ID].orEmpty().isEmpty())
        assertFalse(project.messagesBySession.getValue("ses_created").single().isOptimistic)
        val movedPart = project.partsByMessage.getValue(result.getOrThrow().messageId).single()
        assertEquals("ses_created", movedPart.sessionId)
        assertEquals("file:///E:/repo/src/Main.kt", movedPart.url)
    }

    @Test
    fun createFailureRollsBackOptimisticMessageWithoutMaterializing() = runTest {
        val fixture = fixture()
        val createFailure = IllegalStateException("create failed")
        fixture.gateway.createHandler = { Result.failure(createFailure) }
        var materializedSessionId: String? = null

        val result = fixture.send(
            sessionId = null,
            onSessionMaterialized = { materializedSessionId = it },
        )

        assertSame(createFailure, result.exceptionOrNull())
        assertNull(materializedSessionId)
        assertTrue(fixture.gateway.promptCalls.isEmpty())
        assertTrue(fixture.gateway.commandCalls.isEmpty())
        val project = fixture.project()
        assertTrue(project.sessions.isEmpty())
        assertTrue(project.messagesBySession.values.flatten().isEmpty())
    }

    @Test
    fun createdSessionMaterializesBeforeSendResultAndFailedSendOnlyRollsBackItsMessage() = runTest {
        val fixture = fixture()
        val sendStarted = CompletableDeferred<Unit>()
        val finishSend = CompletableDeferred<Unit>()
        val sendFailure = IllegalStateException("send failed")
        fixture.gateway.createHandler = { Result.success(session("ses_created")) }
        fixture.gateway.promptHandler = {
            sendStarted.complete(Unit)
            finishSend.await()
            Result.failure(sendFailure)
        }
        fixture.store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage(
                id = "msg_other",
                sessionId = OpenCodePromptSender.NEW_SESSION_ID,
                role = "user",
                text = "other draft",
                isOptimistic = true,
            ),
        )
        val attachment = PromptAttachment(
            id = "att_1",
            filename = "note.txt",
            mime = "text/plain",
            dataUrl = "data:text/plain;base64,SGk=",
            sizeBytes = 2,
        )
        var materializedSessionId: String? = null

        val pending = async {
            fixture.send(
                sessionId = null,
                attachments = listOf(attachment),
                onSessionMaterialized = { materializedSessionId = it },
            )
        }
        sendStarted.await()

        assertEquals("ses_created", materializedSessionId)
        val promptCall = fixture.gateway.promptCalls.single()
        val duringSend = fixture.project()
        assertTrue(duringSend.sessions.any { it.id == "ses_created" })
        assertEquals(listOf("msg_other"), duringSend.messagesBySession.getValue(OpenCodePromptSender.NEW_SESSION_ID).map { it.id })
        val movedMessage = duringSend.messagesBySession.getValue("ses_created").single()
        assertEquals(promptCall.messageId, movedMessage.id)
        assertTrue(movedMessage.isOptimistic)
        assertEquals("ses_created", duringSend.partsByMessage.getValue(movedMessage.id).single().sessionId)

        finishSend.complete(Unit)
        val result = pending.await()

        assertSame(sendFailure, result.exceptionOrNull())
        val afterFailure = fixture.project()
        assertTrue(afterFailure.sessions.any { it.id == "ses_created" })
        assertTrue(afterFailure.messagesBySession["ses_created"].orEmpty().isEmpty())
        assertEquals(listOf("msg_other"), afterFailure.messagesBySession.getValue(OpenCodePromptSender.NEW_SESSION_ID).map { it.id })
    }

    @Test
    fun failedExistingSendRollsBackOnlyItsOptimisticMessage() = runTest {
        val fixture = fixture()
        fixture.gateway.promptHandler = { Result.failure(IllegalStateException("failed")) }

        val result = fixture.send(sessionId = "ses_existing")

        assertTrue(result.isFailure)
        assertTrue(fixture.project().messagesBySession["ses_existing"].orEmpty().isEmpty())
    }

    @Test
    fun workspaceSendOnlyMutatesNormalizedWorkspaceBranch() = runTest {
        val fixture = fixture()

        val result = fixture.send(sessionId = "ses_workspace", workspace = " D:\\Workspace\\Main\\ ")

        assertTrue(result.isSuccess)
        assertEquals("D:/Workspace/Main", fixture.gateway.promptCalls.single().workspace)
        assertFalse(
            fixture.project(workspace = "d:/workspace/main")
                .messagesBySession.getValue("ses_workspace").single().isOptimistic,
        )
        assertTrue(fixture.project().messagesBySession["ses_workspace"].orEmpty().isEmpty())
    }

    @Test
    fun workspaceFailureRollsBackOnlyWorkspaceBranch() = runTest {
        val fixture = fixture()
        fixture.gateway.promptHandler = { Result.failure(IllegalStateException("failed")) }
        fixture.store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage("default-message", "ses_workspace", "user", "default"),
        )

        val result = fixture.send(sessionId = "ses_workspace", workspace = "D:/Workspace/Main")

        assertTrue(result.isFailure)
        assertTrue(fixture.project(workspace = "D:/Workspace/Main").messagesBySession["ses_workspace"].orEmpty().isEmpty())
        assertEquals("default-message", fixture.project().messagesBySession.getValue("ses_workspace").single().id)
    }

    @Test
    fun workspaceNewSessionMaterializesSessionAndMessageInSameBranch() = runTest {
        val fixture = fixture()
        fixture.gateway.createHandler = { Result.success(session("ses_workspace_created")) }

        val result = fixture.send(sessionId = null, workspace = "D:\\Workspace\\Main\\")

        assertTrue(result.isSuccess)
        assertEquals("D:/Workspace/Main", fixture.gateway.createCalls.single().workspace)
        assertEquals("D:/Workspace/Main", fixture.gateway.promptCalls.single().workspace)
        val workspaceProject = fixture.project(workspace = "d:/workspace/main")
        assertTrue(workspaceProject.sessions.any { it.id == "ses_workspace_created" })
        assertFalse(workspaceProject.messagesBySession.getValue("ses_workspace_created").single().isOptimistic)
        assertTrue(fixture.project().sessions.none { it.id == "ses_workspace_created" })
        assertTrue(fixture.project().messagesBySession["ses_workspace_created"].orEmpty().isEmpty())
    }

    @Test
    fun secondSendForSameKeyFailsFastWithoutQueueing() = runTest {
        val fixture = fixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.gateway.promptHandler = {
            started.complete(Unit)
            release.await()
            Result.success(Unit)
        }

        val first = async { fixture.send(sessionId = "ses_same") }
        started.await()
        val second = fixture.send(sessionId = "ses_same")

        assertFailure<PromptOperationInProgressException>(second)
        assertEquals(1, fixture.gateway.promptCalls.size)
        release.complete(Unit)
        assertTrue(first.await().isSuccess)
    }

    @Test
    fun sendAndAbortShareTheSameFailFastGate() = runTest {
        val fixture = fixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.gateway.promptHandler = {
            started.complete(Unit)
            release.await()
            Result.success(Unit)
        }

        val send = async { fixture.send(sessionId = "ses_same") }
        started.await()
        val abort = fixture.sender.stop(serverId, directory, "ses_same")

        assertFailure<PromptOperationInProgressException>(abort)
        assertTrue(fixture.gateway.abortCalls.isEmpty())
        release.complete(Unit)
        assertTrue(send.await().isSuccess)
    }

    @Test
    fun differentSessionsProjectsAndWorkspacesCanRunConcurrently() = runTest {
        val fixture = fixture()
        val allStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.gateway.promptHandler = {
            if (fixture.gateway.promptCalls.size == 4) allStarted.complete(Unit)
            release.await()
            Result.success(Unit)
        }

        val sends = listOf(
            async { fixture.send(sessionId = "ses_1") },
            async { fixture.send(sessionId = "ses_2") },
            async { fixture.send(sessionId = "ses_1", directory = "/other") },
            async { fixture.send(sessionId = "ses_1", workspace = "workspace-a") },
        )
        allStarted.await()

        assertEquals(4, fixture.gateway.promptCalls.size)
        release.complete(Unit)
        assertTrue(sends.awaitAll().all { it.isSuccess })
    }

    @Test
    fun cancellationRollsBackOptimisticMessageAndReleasesGate() = runTest {
        val fixture = fixture()
        val firstStarted = CompletableDeferred<Unit>()
        fixture.gateway.promptHandler = {
            if (fixture.gateway.promptCalls.size == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
            Result.success(Unit)
        }

        val first = async { fixture.send(sessionId = "ses_cancel") }
        firstStarted.await()
        first.cancelAndJoin()

        assertTrue(fixture.project().messagesBySession["ses_cancel"].orEmpty().isEmpty())
        val retry = fixture.send(sessionId = "ses_cancel")

        assertTrue(retry.isSuccess)
        assertEquals(2, fixture.gateway.promptCalls.size)
    }

    @Test
    fun staleUiCapabilitiesAreRejectedBeforeOptimisticInsert() = runTest {
        val fixture = fixture()
        val current = capabilities().copy(revision = 2)
        fixture.installCapabilities(current)

        val result = fixture.send(
            capabilities = capabilities().copy(revision = 1),
            installStoreCapabilities = false,
        )

        assertFailure<PromptCapabilitiesStaleException>(result)
        fixture.assertNoGatewayCalls()
        assertTrue(fixture.project().messagesBySession.values.flatten().isEmpty())
    }

    @Test
    fun storeCapabilitiesRemainAuthoritativeWhenUiPayloadDiffersAtSameRevision() = runTest {
        val fixture = fixture()
        val authoritative = capabilities()
        fixture.installCapabilities(authoritative)
        val uiPayload = authoritative.copy(models = emptyList(), agents = emptyList(), commands = emptyList())

        val result = fixture.send(
            sessionId = "ses_authoritative",
            text = "/review inspect",
            capabilities = uiPayload,
            installStoreCapabilities = false,
        )

        assertTrue(result.isSuccess)
        assertEquals("review", fixture.gateway.commandCalls.single().command)
    }

    @Test
    fun malformedAttachmentIsRejectedBeforeStoreOrGatewayMutation() = runTest {
        val fixture = fixture()
        val attachment = PromptAttachment(
            id = "att_invalid",
            filename = "invalid.txt",
            mime = "text/plain",
            dataUrl = "data:text/plain;base64,%%%",
            sizeBytes = 2,
        )

        val result = fixture.send(attachments = listOf(attachment))

        assertFailure<PromptAttachmentMalformedException>(result)
        fixture.assertNoGatewayCalls()
        assertTrue(fixture.project().messagesBySession.values.flatten().isEmpty())
    }

    @Test
    fun attachmentSizeMetadataMustMatchInspectedPayload() = runTest {
        val fixture = fixture()
        val attachment = PromptAttachment(
            id = "att_mismatch",
            filename = "note.txt",
            mime = "text/plain",
            dataUrl = "data:text/plain;base64,SGk=",
            sizeBytes = 1,
        )

        val result = fixture.send(attachments = listOf(attachment))

        assertFailure<PromptAttachmentMetadataInvalidException>(result)
        fixture.assertNoGatewayCalls()
    }

    @Test
    fun confirmedMessageSurvivesFailedHttpResultAndReportsUncertainOutcome() = runTest {
        val fixture = fixture()
        val failure = IllegalStateException("connection closed")
        fixture.gateway.promptHandler = { call ->
            fixture.store.upsertMessage(
                serverId,
                directory,
                OpenCodeMessage(
                    id = call.messageId,
                    sessionId = call.sessionId,
                    role = "user",
                    text = call.text,
                    isOptimistic = false,
                ),
            )
            Result.failure(failure)
        }

        val result = fixture.send(sessionId = "ses_confirmed")

        assertFailure<PromptSendOutcomeUncertainException>(result)
        val message = fixture.project().messagesBySession.getValue("ses_confirmed").single()
        assertFalse(message.isOptimistic)
    }

    @Test
    fun partEventConfirmationSurvivesFailedHttpResultAndReportsUncertainOutcome() = runTest {
        val fixture = fixture()
        fixture.gateway.promptHandler = { call ->
            fixture.store.reduceProjectEvent(
                serverId,
                directory,
                Json.parseToJsonElement(
                    """{"type":"message.part.updated","properties":{"part":{"id":"server-part","messageID":"${call.messageId}","sessionID":"${call.sessionId}","type":"text","text":"accepted"}}}""",
                ).jsonObject,
            )
            Result.failure(IllegalStateException("connection closed"))
        }

        val result = fixture.send(sessionId = "ses_part_confirmed")

        assertFailure<PromptSendOutcomeUncertainException>(result)
        val message = fixture.project().messagesBySession.getValue("ses_part_confirmed").single()
        assertFalse(message.isOptimistic)
        assertEquals("accepted", message.text)
    }

    @Test
    fun newSessionMoveDoesNotOverwriteMessageAlreadyConfirmedBySse() = runTest {
        val fixture = fixture()
        fixture.gateway.createHandler = { Result.success(session("ses_created")) }

        val result = fixture.send(
            sessionId = null,
            onOptimisticMessageCreated = { messageId ->
                fixture.store.upsertMessage(
                    serverId,
                    directory,
                    OpenCodeMessage(
                        id = messageId,
                        sessionId = "ses_created",
                        role = "user",
                        text = "confirmed",
                        isOptimistic = false,
                    ),
                )
            },
        )

        assertTrue(result.isSuccess)
        val project = fixture.project()
        assertTrue(project.messagesBySession[OpenCodePromptSender.NEW_SESSION_ID].orEmpty().isEmpty())
        val confirmed = project.messagesBySession.getValue("ses_created").single()
        assertEquals("confirmed", confirmed.text)
        assertFalse(confirmed.isOptimistic)
    }

    @Test
    fun materializedSessionAliasBlocksOperationsUntilNewSessionSendFinishes() = runTest {
        val fixture = fixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.gateway.createHandler = { Result.success(session("ses_created")) }
        fixture.gateway.promptHandler = {
            started.complete(Unit)
            release.await()
            Result.success(Unit)
        }

        val first = async { fixture.send(sessionId = null) }
        started.await()
        val second = fixture.send(sessionId = "ses_created")

        assertFailure<PromptOperationInProgressException>(second)
        release.complete(Unit)
        assertTrue(first.await().isSuccess)
    }

    private fun fixture(): Fixture {
        val pathNormalizer = PathNormalizer()
        val store = InMemoryOpenCodeStore(pathNormalizer)
        val gateway = FakePromptGateway()
        return Fixture(
            gateway = gateway,
            store = store,
            sender = OpenCodePromptSender(gateway, store, pathNormalizer),
            pathNormalizer = pathNormalizer,
        )
    }

    private suspend fun Fixture.send(
        sessionId: String? = "ses_1",
        directory: String = OpenCodePromptSenderTest.directory,
        text: String = "hello",
        attachments: List<PromptAttachment> = emptyList(),
        projectFileContexts: List<ProjectFileContext> = emptyList(),
        agent: String? = "build",
        model: PromptModelSelection? = validSelection,
        variant: String? = model?.variant,
        workspace: String? = null,
        capabilities: PromptCapabilities = capabilities(),
        installStoreCapabilities: Boolean = true,
        onOptimisticMessageCreated: (String) -> Unit = {},
        onSessionMaterialized: (String) -> Unit = {},
    ): Result<PromptSendResult> {
        if (installStoreCapabilities) installCapabilities(capabilities, directory, workspace)
        return sender.sendPrompt(
            serverId = serverId,
            directory = directory,
            sessionId = sessionId,
            text = text,
            attachments = attachments,
            projectFileContexts = projectFileContexts,
            agent = agent,
            model = model,
            variant = variant,
            workspace = workspace,
            capabilities = capabilities,
            onOptimisticMessageCreated = onOptimisticMessageCreated,
            onSessionMaterialized = onSessionMaterialized,
        )
    }

    private fun Fixture.installCapabilities(
        capabilities: PromptCapabilities,
        directory: String = OpenCodePromptSenderTest.directory,
        workspace: String? = null,
    ) {
        store.applyProjectSnapshot(
            OpenCodeProjectSnapshot(
                serverId = serverId,
                normalizedDirectory = pathNormalizer.normalize(directory),
                workspace = workspace,
                pathInfo = null,
                sessions = emptyList(),
                statuses = emptyMap(),
                providerCount = 0,
                models = capabilities.models,
                agents = capabilities.agents,
                commands = capabilities.commands,
                promptCapabilities = capabilities,
                mcps = emptyList(),
                lsps = emptyList(),
                plugins = emptyList(),
                commandCount = capabilities.commands.size,
                permissionCount = 0,
                questionCount = 0,
            ),
        )
    }

    private suspend fun Fixture.project(
        directory: String = OpenCodePromptSenderTest.directory,
        workspace: String? = null,
    ) = store.observeProject(serverId, directory, workspace).first()

    private fun Fixture.assertNoGatewayCalls() {
        assertTrue(gateway.createCalls.isEmpty())
        assertTrue(gateway.promptCalls.isEmpty())
        assertTrue(gateway.commandCalls.isEmpty())
        assertTrue(gateway.abortCalls.isEmpty())
    }

    private inline fun <reified T : Throwable> assertFailure(result: Result<*>) {
        assertTrue("Expected failure but was $result", result.isFailure)
        assertTrue(
            "Expected ${T::class.java.simpleName} but was ${result.exceptionOrNull()?.javaClass?.simpleName}",
            result.exceptionOrNull() is T,
        )
    }

    private data class Fixture(
        val gateway: FakePromptGateway,
        val store: InMemoryOpenCodeStore,
        val sender: OpenCodePromptSender,
        val pathNormalizer: PathNormalizer,
    )

    private data class CreateCall(
        val serverId: String,
        val directory: String,
        val agent: String,
        val model: PromptModelSelection,
        val workspace: String?,
    )

    private data class PromptCall(
        val serverId: String,
        val directory: String,
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val text: String,
        val attachments: List<PromptAttachment>,
        val projectFileContexts: List<ProjectFileContext>,
        val agent: String,
        val model: PromptModelSelection,
        val variant: String?,
        val workspace: String?,
    )

    private data class CommandCall(
        val serverId: String,
        val directory: String,
        val sessionId: String,
        val messageId: String,
        val command: String,
        val arguments: String?,
        val attachments: List<PromptAttachment>,
        val projectFileContexts: List<ProjectFileContext>,
        val agent: String,
        val model: PromptModelSelection,
        val variant: String?,
        val workspace: String?,
    )

    private data class AbortCall(
        val serverId: String,
        val directory: String,
        val sessionId: String,
        val workspace: String?,
    )

    private class FakePromptGateway : PromptGateway {
        val createCalls = mutableListOf<CreateCall>()
        val promptCalls = mutableListOf<PromptCall>()
        val commandCalls = mutableListOf<CommandCall>()
        val abortCalls = mutableListOf<AbortCall>()

        var createHandler: suspend (CreateCall) -> Result<OpenCodeSession> = {
            Result.success(session("ses_created"))
        }
        var promptHandler: suspend (PromptCall) -> Result<Unit> = { Result.success(Unit) }
        var commandHandler: suspend (CommandCall) -> Result<Unit> = { Result.success(Unit) }
        var abortHandler: suspend (AbortCall) -> Result<Unit> = { Result.success(Unit) }

        override suspend fun createSession(
            serverId: String,
            directory: String,
            agent: String,
            model: PromptModelSelection,
            workspace: String?,
        ): Result<OpenCodeSession> {
            val call = CreateCall(serverId, directory, agent, model, workspace)
            createCalls += call
            return createHandler(call)
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
        ): Result<Unit> {
            val call = PromptCall(
                serverId,
                directory,
                sessionId,
                messageId,
                partId,
                text,
                attachments,
                projectFileContexts,
                agent,
                model,
                variant,
                workspace,
            )
            promptCalls += call
            return promptHandler(call)
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
        ): Result<Unit> {
            val call = CommandCall(
                serverId,
                directory,
                sessionId,
                messageId,
                command,
                arguments,
                attachments,
                projectFileContexts,
                agent,
                model,
                variant,
                workspace,
            )
            commandCalls += call
            return commandHandler(call)
        }

        override suspend fun abortSession(
            serverId: String,
            directory: String,
            sessionId: String,
            workspace: String?,
        ): Result<Unit> {
            val call = AbortCall(serverId, directory, sessionId, workspace)
            abortCalls += call
            return abortHandler(call)
        }
    }

    private companion object {
        const val serverId = "server"
        const val directory = "E:/repo/"

        val validModel = OpenCodeModel(
            providerId = "openai",
            modelId = "gpt-5.5",
            name = "GPT-5.5",
            providerName = "OpenAI",
            isConnected = true,
            variants = listOf("medium", "high"),
            isEnabled = true,
        )
        val validSelection = PromptModelSelection("openai", "gpt-5.5")

        fun capabilities(
            models: List<OpenCodeModel> = listOf(validModel),
            agents: List<OpenCodeAgent> = listOf(
                OpenCodeAgent("build", "Build", null),
                OpenCodeAgent("plan", "Plan", null),
            ),
            commands: List<OpenCodeCommand> = listOf(
                OpenCodeCommand("review", "review", "Review", "server"),
            ),
        ) = PromptCapabilities(
            models = models,
            agents = agents,
            commands = commands,
            isLoaded = true,
            revision = 1,
        )

        fun session(id: String) = OpenCodeSession(
            id = id,
            title = "Session",
            normalizedDirectory = "E:/repo",
            path = null,
            parentId = null,
            agent = "build",
            modelLabel = "openai/gpt-5.5",
            updatedAt = null,
            archivedAt = null,
            modelProviderId = "openai",
            modelId = "gpt-5.5",
        )
    }
}
