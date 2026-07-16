package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.core.store.SseConnectionStatus
import io.github.ycfeng.ocdeck.core.store.SseFailureReason
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeApiMismatchException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeEventClientLifecycleTest {
    private val serverId = "server"
    private val directory = "E:/work/app"

    @Test
    fun projectCloseWhileConnectionIsInFlightRejectsLateResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = MutableSseConnectionProvider().apply {
            handler = { requestedServerId, _ ->
                withContext(NonCancellable) { gate.await() }
                testSseConnection(requestedServerId, identity)
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory)

        environment.client.connectProject(serverId, directory)
        runCurrent()
        assertEquals(1, provider.calls)

        environment.client.closeProjectNow(serverId, directory)
        gate.complete(Unit)
        runCurrent()

        assertTrue(factory.sources.isEmpty())
        assertEquals(SseConnectionStatus.Closed, environment.store.currentProject(serverId, directory).connectionStatus)
        assertFalse(environment.client.projectLifecycleState(serverId, directory)!!.desiredOpen)
    }

    @Test
    fun globalCloseWhileConnectionIsInFlightRejectsLateResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = MutableSseConnectionProvider().apply {
            handler = { requestedServerId, _ ->
                withContext(NonCancellable) { gate.await() }
                testSseConnection(requestedServerId, identity)
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory)

        environment.client.connectGlobal(serverId)
        runCurrent()
        environment.client.closeGlobalNow(serverId)
        gate.complete(Unit)
        runCurrent()

        assertTrue(factory.sources.isEmpty())
        assertEquals(SseConnectionStatus.Closed, environment.store.currentGlobalConnectionStatus(serverId))
        assertFalse(environment.client.globalLifecycleState(serverId)!!.desiredOpen)
    }

    @Test
    fun olderProjectOpenAttemptRetriesCurrentTransportAndOpens() = runTest {
        val older = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val current = testTransport(configEpoch = 1L, controlEpoch = 2L)
        val provider = MutableSseConnectionProvider(current).apply {
            handler = { requestedServerId, call ->
                testSseConnection(requestedServerId, if (call == 1) older else current)
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory)
        environment.client.onForegroundConnectionReady(serverId, current)

        environment.client.connectProject(serverId, directory)
        runCurrent()

        assertTrue(environment.store.currentProject(serverId, directory).connectionStatus is SseConnectionStatus.Retrying)
        assertTrue(factory.sources.isEmpty())

        advanceTimeBy(1_000L)
        runCurrent()
        factory.sources.single().open()
        runCurrent()

        assertEquals(2, provider.calls)
        assertEquals(current, environment.client.projectLifecycleState(serverId, directory)!!.transportIdentity)
        assertEquals(SseConnectionStatus.Open, environment.store.currentProject(serverId, directory).connectionStatus)
    }

    @Test
    fun olderGlobalOpenAttemptRetriesCurrentTransportAndOpens() = runTest {
        val older = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val current = testTransport(configEpoch = 1L, controlEpoch = 2L)
        val provider = MutableSseConnectionProvider(current).apply {
            handler = { requestedServerId, call ->
                testSseConnection(requestedServerId, if (call == 1) older else current)
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory)
        environment.client.onForegroundConnectionReady(serverId, current)

        environment.client.connectGlobal(serverId)
        runCurrent()

        assertTrue(environment.store.currentGlobalConnectionStatus(serverId) is SseConnectionStatus.Retrying)
        assertTrue(factory.sources.isEmpty())

        advanceTimeBy(1_000L)
        runCurrent()
        factory.sources.single().open()

        assertEquals(2, provider.calls)
        assertEquals(current, environment.client.globalLifecycleState(serverId)!!.transportIdentity)
        assertEquals(SseConnectionStatus.Open, environment.store.currentGlobalConnectionStatus(serverId))
    }

    @Test
    fun closedOlderProjectOpenAttemptDoesNotRetry() = runTest {
        val gate = CompletableDeferred<Unit>()
        val older = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val current = testTransport(configEpoch = 1L, controlEpoch = 2L)
        val provider = MutableSseConnectionProvider(current).apply {
            handler = { requestedServerId, _ ->
                withContext(NonCancellable) { gate.await() }
                testSseConnection(requestedServerId, older)
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory)
        environment.client.onForegroundConnectionReady(serverId, current)
        environment.client.connectProject(serverId, directory)
        runCurrent()

        environment.client.closeProjectNow(serverId, directory)
        gate.complete(Unit)
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, provider.calls)
        assertTrue(factory.sources.isEmpty())
        assertEquals(SseConnectionStatus.Closed, environment.store.currentProject(serverId, directory).connectionStatus)
    }

    @Test
    fun closedOlderGlobalOpenAttemptDoesNotRetry() = runTest {
        val gate = CompletableDeferred<Unit>()
        val older = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val current = testTransport(configEpoch = 1L, controlEpoch = 2L)
        val provider = MutableSseConnectionProvider(current).apply {
            handler = { requestedServerId, _ ->
                withContext(NonCancellable) { gate.await() }
                testSseConnection(requestedServerId, older)
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory)
        environment.client.onForegroundConnectionReady(serverId, current)
        environment.client.connectGlobal(serverId)
        runCurrent()

        environment.client.closeGlobalNow(serverId)
        gate.complete(Unit)
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, provider.calls)
        assertTrue(factory.sources.isEmpty())
        assertEquals(SseConnectionStatus.Closed, environment.store.currentGlobalConnectionStatus(serverId))
    }

    @Test
    fun retryDelayCloseAndNewGenerationDoNotReuseOldRetry() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        val oldLease = environment.client.connectProject(serverId, directory)
        runCurrent()
        val oldSource = factory.sources.single()
        oldSource.open()
        runCurrent()

        oldSource.fail(ConnectException("connection reset"))
        assertTrue(environment.store.currentProject(serverId, directory).connectionStatus is SseConnectionStatus.Retrying)

        environment.client.closeProjectNow(serverId, directory)
        val newLease = environment.client.connectProject(serverId, directory)
        runCurrent()
        assertEquals(2, factory.sources.size)

        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(2, factory.sources.size)

        oldLease.release()
        assertEquals(1, environment.client.projectLifecycleState(serverId, directory)!!.ownerCount)
        newLease.release()
        newLease.release()
        assertEquals(SseConnectionStatus.Closed, environment.store.currentProject(serverId, directory).connectionStatus)
    }

    @Test
    fun staleSourceCallbacksCannotOpenMutateOrDeleteReplacement() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        val lease = environment.client.connectProject(serverId, directory)
        runCurrent()
        val staleSource = factory.sources.single()
        staleSource.open()
        runCurrent()

        environment.client.reconnectProject(serverId, directory)
        runCurrent()
        val replacement = factory.sources.last()
        replacement.open()
        runCurrent()
        val replacementSourceId = environment.client.projectLifecycleState(serverId, directory)!!.sourceId

        staleSource.open()
        staleSource.event(sessionUpdatedEvent("stale", "stale"))
        staleSource.fail(ConnectException("late failure"))
        staleSource.close()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(2, factory.sources.size)
        assertEquals(replacementSourceId, environment.client.projectLifecycleState(serverId, directory)!!.sourceId)
        assertEquals(SseConnectionStatus.Open, environment.store.currentProject(serverId, directory).connectionStatus)
        assertNull(environment.store.currentProject(serverId, directory).sessions.firstOrNull { it.id == "stale" })

        lease.release()
    }

    @Test
    fun closeRacingWithOnOpenAlwaysEndsClosed() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        val source = factory.sources.single()

        environment.client.closeProjectNow(serverId, directory)
        source.open()

        assertEquals(SseConnectionStatus.Closed, environment.store.currentProject(serverId, directory).connectionStatus)
        assertFalse(environment.client.projectLifecycleState(serverId, directory)!!.desiredOpen)
    }

    @Test
    fun projectShellAndDirectSessionGlobalOwnersShareOneSource() = runTest {
        val factory = RecordingSseEventSourceFactory().apply {
            closeOnCancel = true
            beforeReturn = SseEventListener::onOpen
        }
        val environment = eventClientEnvironment(backgroundScope, factory = factory)

        val first = environment.client.connectGlobal(serverId)
        val second = environment.client.connectGlobal(serverId)
        runCurrent()

        assertEquals(1, factory.sources.size)
        assertEquals(2, environment.client.globalLifecycleState(serverId)!!.ownerCount)
        assertEquals(SseConnectionStatus.Open, environment.store.currentGlobalConnectionStatus(serverId))

        first.release()
        first.release()
        assertEquals(1, environment.client.globalLifecycleState(serverId)!!.ownerCount)
        assertEquals(SseConnectionStatus.Open, environment.store.currentGlobalConnectionStatus(serverId))

        second.release()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, factory.sources.size)
        assertEquals(SseConnectionStatus.Closed, environment.store.currentGlobalConnectionStatus(serverId))
    }

    @Test
    fun activeClosedRetriesButExplicitCancelDoesNot() = runTest {
        val factory = RecordingSseEventSourceFactory().apply { closeOnCancel = true }
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        factory.sources.single().open()
        runCurrent()

        factory.sources.single().close()
        assertTrue(environment.store.currentProject(serverId, directory).connectionStatus is SseConnectionStatus.Retrying)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, factory.sources.size)

        environment.client.closeProjectNow(serverId, directory)
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(2, factory.sources.size)
        assertEquals(SseConnectionStatus.Closed, environment.store.currentProject(serverId, directory).connectionStatus)
    }

    @Test
    fun permanentHttpFailureStoresOnlyTypedReasonAndDoesNotRetry() = runTest {
        val secret = "unique-sse-secret"
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        environment.client.connectProject(serverId, directory)
        runCurrent()

        factory.sources.single().fail(
            responseCode = 401,
            responseMessage = "Authorization: Bearer $secret",
        )
        advanceTimeBy(10_000L)
        runCurrent()

        val status = environment.store.currentProject(serverId, directory).connectionStatus as SseConnectionStatus.Failed
        assertEquals(SseFailureReason.HttpStatus(401), status.reason)
        assertFalse(status.toString().contains(secret))
        assertEquals(1, factory.sources.size)
    }

    @Test
    fun allNonOkStatusesExceptExplicitTransientAndServerFailuresArePermanent() = runTest {
        val permanentCodes = listOf(201, 204, 301, 307, 400, 401, 403, 404, 405, 410, 422)

        permanentCodes.forEach { code ->
            val factory = RecordingSseEventSourceFactory()
            val environment = eventClientEnvironment(backgroundScope, factory = factory)
            val currentDirectory = "$directory/$code"
            environment.client.connectProject(serverId, currentDirectory)
            runCurrent()

            factory.sources.single().fail(
                throwable = SseUnexpectedHttpStatusException(code),
                responseCode = code,
            )

            assertTrue(
                environment.store.currentProject(serverId, currentDirectory).connectionStatus is SseConnectionStatus.Failed,
            )
            assertEquals(1, factory.sources.size)
        }
    }

    @Test
    fun transientClientAndServerStatusesRemainRetryable() = runTest {
        val retryableCodes = listOf(408, 409, 425, 429, 500, 503)

        retryableCodes.forEach { code ->
            val factory = RecordingSseEventSourceFactory()
            val environment = eventClientEnvironment(backgroundScope, factory = factory)
            val currentDirectory = "$directory/$code"
            environment.client.connectProject(serverId, currentDirectory)
            runCurrent()

            factory.sources.single().fail(
                throwable = SseUnexpectedHttpStatusException(code),
                responseCode = code,
            )

            assertTrue(
                environment.store.currentProject(serverId, currentDirectory).connectionStatus is SseConnectionStatus.Retrying,
            )
        }
    }

    @Test
    fun invalidSseContentTypeIsPermanent() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        environment.client.connectProject(serverId, directory)
        runCurrent()

        factory.sources.single().fail(
            throwable = SseContentTypeException(),
            responseCode = 200,
        )

        val status = environment.store.currentProject(serverId, directory).connectionStatus
        assertTrue(status is SseConnectionStatus.Failed)
        assertEquals(SseFailureReason.InvalidResponse, (status as SseConnectionStatus.Failed).reason)
    }

    @Test
    fun inboundPayloadLimitFailureIsPermanentAndDoesNotExposeTechnicalPayload() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        environment.client.connectProject(serverId, directory)
        runCurrent()

        factory.sources.single().fail(throwable = SseLineTooLargeException())
        advanceTimeBy(10_000L)
        runCurrent()

        val status = environment.store.currentProject(serverId, directory).connectionStatus as SseConnectionStatus.Failed
        assertEquals(SseFailureReason.ResponseTooLarge, status.reason)
        assertEquals(1, factory.sources.size)
    }

    @Test
    fun malformedSseJsonIsIgnoredWithoutClosingOpenSource() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        val source = factory.sources.single()
        source.open()
        runCurrent()

        source.event("{")

        assertEquals(SseConnectionStatus.Open, environment.store.currentProject(serverId, directory).connectionStatus)
        assertEquals(1, factory.sources.size)
    }

    @Test
    fun typeMismatchedSseEventIsIgnoredWithoutClosingOpenSource() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        val source = factory.sources.single()
        source.open()
        runCurrent()

        source.event(
            """{"type":"session.updated","properties":{"info":{"id":{"unexpected":true}}}}""",
        )

        assertEquals(SseConnectionStatus.Open, environment.store.currentProject(serverId, directory).connectionStatus)
        assertEquals(1, factory.sources.size)
    }

    @Test
    fun missingServerSetupFailureIsPermanent() = runTest {
        val provider = MutableSseConnectionProvider().apply {
            handler = { _, _ -> throw IllegalArgumentException("Server not found: token=missing-secret") }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory)

        environment.client.connectGlobal("missing")
        runCurrent()

        val status = environment.store.currentGlobalConnectionStatus("missing") as SseConnectionStatus.Failed
        assertEquals(SseFailureReason.InvalidResponse, status.reason)
        assertFalse(status.toString().contains("missing-secret"))
        assertTrue(factory.sources.isEmpty())
    }

    @Test
    fun permanentStcpComponentSetupFailuresDoNotRetry() = runTest {
        val cases = listOf(
            GoMobileBridgeUnavailableException() to
                SseFailureReason.OperationRejected(OpenCodeOperationRejectionReason.StcpComponentUnavailable),
            GoMobileBridgeApiMismatchException() to SseFailureReason.InvalidResponse,
        )

        cases.forEachIndexed { index, (componentFailure, expectedReason) ->
            val caseServerId = "stcp-$index"
            val provider = MutableSseConnectionProvider().apply {
                handler = { _, _ -> throw IllegalStateException("wrapper", componentFailure) }
            }
            val factory = RecordingSseEventSourceFactory()
            val environment = eventClientEnvironment(backgroundScope, provider, factory)

            environment.client.connectGlobal(caseServerId)
            runCurrent()
            advanceTimeBy(10_000L)
            runCurrent()

            val status = environment.store.currentGlobalConnectionStatus(caseServerId) as SseConnectionStatus.Failed
            assertEquals(expectedReason, status.reason)
            assertEquals(1, provider.calls)
            assertTrue(factory.sources.isEmpty())
        }
    }

    @Test
    fun projectOpenMakesProjectStreamAuthoritativeOverGlobalDuplicate() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        environment.client.connectProject(serverId, directory)
        environment.client.connectGlobal(serverId)
        runCurrent()
        val projectSource = factory.sources.single { "/event?" in it.request.url }
        val globalSource = factory.sources.single { "/global/event" in it.request.url }
        projectSource.open()
        globalSource.open()
        runCurrent()
        environment.store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage(
                id = "message",
                sessionId = "session",
                role = "assistant",
                text = "base",
                parts = listOf(textPart("part", "message", "session", "base")),
            ),
        )
        val delta = partDeltaEvent("message", "part", "+delta")

        projectSource.event(delta)
        globalSource.event(globalSyncEvent(directory, delta))

        val state = environment.store.currentProject(serverId, directory)
        assertEquals("base+delta", state.partsByMessage.getValue("message").single().text)
        assertEquals("base+delta", state.messagesBySession.getValue("session").single().text)
    }

    @Test
    fun globalProjectEventFallsBackWhileProjectSourceIsNotOpen() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        environment.client.connectProject(serverId, directory)
        environment.client.connectGlobal(serverId)
        runCurrent()
        val globalSource = factory.sources.single { "/global/event" in it.request.url }
        globalSource.open()
        environment.store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage(
                id = "message",
                sessionId = "session",
                role = "assistant",
                text = "base",
                parts = listOf(textPart("part", "message", "session", "base")),
            ),
        )

        globalSource.event(globalSyncEvent(directory, partDeltaEvent("message", "part", "+fallback")))

        assertEquals(
            "base+fallback",
            environment.store.currentProject(serverId, directory).partsByMessage.getValue("message").single().text,
        )
    }

    @Test
    fun windowsDirectoryAndWorkspaceCaseShareOneProjectSlot() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)

        val first = environment.client.connectProject(serverId, "E:/Work/App", "D:/Workspaces/Main")
        val second = environment.client.connectProject(serverId, "e:\\work\\app", "d:\\workspaces\\main")
        runCurrent()

        assertEquals(1, factory.sources.size)
        assertEquals(2, environment.client.projectLifecycleState(serverId, "E:/work/app", "D:/workspaces/main")!!.ownerCount)
        val globalLease = environment.client.connectGlobal(serverId)
        runCurrent()
        val globalSource = factory.sources.single { "/global/event" in it.request.url }
        globalSource.open()
        environment.store.upsertMessage(
            serverId,
            "E:/Work/App",
            OpenCodeMessage(
                id = "case-message",
                sessionId = "case-session",
                role = "assistant",
                text = "base",
                parts = listOf(textPart("case-part", "case-message", "case-session", "base")),
            ),
            workspace = "D:/Workspaces/Main",
        )
        globalSource.event(
            globalSyncEvent(
                directory = "e:/work/app",
                workspace = "d:/workspaces/main",
                event = partDeltaEvent("case-message", "case-part", "+case"),
            ),
        )

        assertEquals(
            "base+case",
            environment.store.currentProject(serverId, "E:/WORK/APP", "D:/WORKSPACES/MAIN")
                .partsByMessage.getValue("case-message").single().text,
        )
        first.release()
        second.release()
        globalLease.release()
    }

    @Test
    fun lateOlderForegroundIdentityCannotRollbackCurrentTransport() = runTest {
        val older = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val current = testTransport(configEpoch = 1L, controlEpoch = 2L)
        val provider = MutableSseConnectionProvider(current)
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory)
        environment.client.connectProject(serverId, directory)
        environment.client.connectGlobal(serverId)
        runCurrent()
        factory.sources.forEach(RecordingSseEventSource::open)
        runCurrent()
        val projectGeneration = environment.client.projectLifecycleState(serverId, directory)!!.generation
        val globalGeneration = environment.client.globalLifecycleState(serverId)!!.generation

        environment.client.onForegroundConnectionReady(serverId, older)
        runCurrent()

        assertEquals(2, factory.sources.size)
        assertEquals(projectGeneration, environment.client.projectLifecycleState(serverId, directory)!!.generation)
        assertEquals(globalGeneration, environment.client.globalLifecycleState(serverId)!!.generation)
        assertEquals(current, environment.client.projectLifecycleState(serverId, directory)!!.transportIdentity)
        assertEquals(current, environment.client.globalLifecycleState(serverId)!!.transportIdentity)
    }

    @Test
    fun transportChangeObservedByOneOpenRebuildsEveryDesiredSlot() = runTest {
        val oldIdentity = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val newIdentity = testTransport(configEpoch = 1L, controlEpoch = 2L)
        val provider = MutableSseConnectionProvider(oldIdentity).apply {
            handler = { requestedServerId, call ->
                testSseConnection(requestedServerId, if (call == 1) oldIdentity else newIdentity)
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory)

        environment.client.connectProject(serverId, directory)
        environment.client.connectGlobal(serverId)
        runCurrent()

        assertEquals(3, factory.sources.size)
        assertEquals(1, factory.sources.first().cancelCount)
        assertEquals(newIdentity, environment.client.projectLifecycleState(serverId, directory)!!.transportIdentity)
        assertEquals(newIdentity, environment.client.globalLifecycleState(serverId)!!.transportIdentity)
        assertEquals(4, provider.calls)
    }
}

private fun textPart(id: String, messageId: String, sessionId: String, text: String) = OpenCodeMessagePart(
    id = id,
    messageId = messageId,
    sessionId = sessionId,
    type = "text",
    text = text,
    synthetic = false,
)
