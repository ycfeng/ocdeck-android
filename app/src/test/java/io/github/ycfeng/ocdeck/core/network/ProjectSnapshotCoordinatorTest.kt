package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.store.SseConnectionStatus
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageBundle
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.ui.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectSnapshotCoordinatorTest {
    private val serverId = "server"
    private val directory = "E:/work/app"

    @Test
    fun calibrationUsesCurrentRequestedSessionWindow() = runTest {
        val loader = ScriptedProjectSnapshotLoader { requestedServerId, requestedDirectory, workspace, _ ->
            Result.success(loadedSnapshot(requestedServerId, requestedDirectory, workspace))
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.store.applyProjectSnapshot(
            projectSnapshot(
                serverId = serverId,
                directory = directory,
                sessions = (1..25).map { testSession("session-$it", updatedAt = it.toLong()) },
            ).copy(sessionWindowRawResultCount = 70),
        )
        environment.store.requestMoreSessions(serverId, directory)
        environment.client.connectProject(serverId, directory)
        runCurrent()

        factory.sources.single().open()
        runCurrent()

        val window = loader.sessionWindowRequests.single()
        assertEquals(40, window.visibleRootLimit)
        assertEquals(90, window.requestedRawLimit)
        assertEquals(1L, window.requestGeneration)
    }

    @Test
    fun failedSnapshotKeepsSseOpenAndReplaysBufferedEvents() = runTest {
        val gate = CompletableDeferred<Result<LoadedProjectSnapshot>>()
        val loader = ScriptedProjectSnapshotLoader { _, _, _, _ -> gate.await() }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        val source = factory.sources.single()

        source.open()
        runCurrent()
        source.event(sessionUpdatedEvent("event-session", "from-event"))
        assertNull(environment.store.currentProject(serverId, directory).sessions.firstOrNull())

        gate.complete(Result.failure(IllegalStateException("Authorization: Bearer snapshot-secret")))
        runCurrent()

        val state = environment.store.currentProject(serverId, directory)
        assertEquals(SseConnectionStatus.Open, state.connectionStatus)
        assertEquals("from-event", state.sessions.single().title)
        assertEquals(UiText.Resource(R.string.project_snapshot_load_failed), state.error)
        assertEquals(1, loader.calls)
    }

    @Test
    fun successfulSnapshotAppliesBeforeBufferedEventsInArrivalOrder() = runTest {
        val gate = CompletableDeferred<Result<LoadedProjectSnapshot>>()
        val loader = ScriptedProjectSnapshotLoader { _, _, _, _ -> gate.await() }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        val source = factory.sources.single()
        source.open()
        runCurrent()

        source.event(sessionUpdatedEvent("session", "first-event", updatedAt = 2L))
        source.event(sessionUpdatedEvent("session", "second-event", updatedAt = 3L))
        gate.complete(
            Result.success(
                loadedSnapshot(
                    serverId = serverId,
                    directory = directory,
                    sessions = listOf(testSession("session", title = "snapshot", updatedAt = 1L)),
                ),
            ),
        )
        runCurrent()

        assertEquals("second-event", environment.store.currentProject(serverId, directory).sessions.single().title)
        assertEquals(2, environment.reductions.size)
        assertEquals("first-event", environment.reductions[0].after.sessions.single().title)
        assertEquals("second-event", environment.reductions[1].after.sessions.single().title)
    }

    @Test
    fun recoverySnapshotRestoresActiveSessionMessagesBeforeBufferedDelta() = runTest {
        val gate = CompletableDeferred<Result<LoadedProjectSnapshot>>()
        val loader = ScriptedProjectSnapshotLoader { _, _, _, _ -> gate.await() }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        val sessionId = "active-session"
        val messageId = "active-message"
        val partId = "active-part"
        environment.store.setActiveSession(serverId, directory, sessionId)
        environment.store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage(
                id = messageId,
                sessionId = sessionId,
                role = "assistant",
                text = "before-disconnect",
                parts = listOf(OpenCodeMessagePart(partId, messageId, sessionId, "text", "before-disconnect", false)),
            ),
        )
        val expectedRevision = environment.store.captureMessageDataRevision(serverId, directory)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        val source = factory.sources.single()
        source.open()
        runCurrent()

        val request = loader.activeSessionRequests.single()!!
        assertEquals(sessionId, request.sessionId)
        assertEquals(expectedRevision, request.expectedRevision)
        assertTrue(request.requestGeneration > 0L)
        source.event(
            """{"type":"message.part.delta","properties":{"messageID":"$messageId","partID":"$partId","field":"text","delta":"+live"}}""",
        )
        gate.complete(
            Result.success(
                loadedSnapshot(
                    serverId = serverId,
                    directory = directory,
                    activeSessionMessages = LoadedActiveSessionMessages(
                        sessionId = sessionId,
                        requestGeneration = request.requestGeneration,
                        expectedRevision = expectedRevision,
                        bundle = OpenCodeMessageBundle(
                            messages = listOf(OpenCodeMessage(messageId, sessionId, "assistant", "missed")),
                            parts = listOf(OpenCodeMessagePart(partId, messageId, sessionId, "text", "missed", false)),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()

        val state = environment.store.currentProject(serverId, directory)
        assertEquals("missed+live", state.messagesBySession.getValue(sessionId).single().text)
        assertEquals("missed+live", state.partsByMessage.getValue(messageId).single().text)
        assertEquals(SseConnectionStatus.Open, state.connectionStatus)
    }

    @Test
    fun recoverySnapshotDoesNotApplyMessagesAfterActiveSessionChanges() = runTest {
        val gate = CompletableDeferred<Result<LoadedProjectSnapshot>>()
        val loader = ScriptedProjectSnapshotLoader { _, _, _, _ -> gate.await() }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.store.setActiveSession(serverId, directory, "old-session")
        environment.client.connectProject(serverId, directory)
        runCurrent()
        factory.sources.single().open()
        runCurrent()
        val request = loader.activeSessionRequests.single()!!

        environment.store.setActiveSession(serverId, directory, "new-session")
        gate.complete(
            Result.success(
                loadedSnapshot(
                    serverId = serverId,
                    directory = directory,
                    activeSessionMessages = LoadedActiveSessionMessages(
                        sessionId = request.sessionId,
                        requestGeneration = request.requestGeneration,
                        expectedRevision = request.expectedRevision,
                        bundle = OpenCodeMessageBundle(
                            messages = listOf(OpenCodeMessage("stale-message", request.sessionId, "assistant", "stale")),
                            parts = emptyList(),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()

        val state = environment.store.currentProject(serverId, directory)
        assertEquals("new-session", state.activeSessionId)
        assertTrue(state.messagesBySession["old-session"].orEmpty().isEmpty())
    }

    @Test
    fun globalFallbackAndProjectHandoffDeduplicateOnlyQueuedOccurrences() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        val sessionId = "session"
        val messageId = "message"
        val partId = "part"
        environment.store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage(
                id = messageId,
                sessionId = sessionId,
                role = "assistant",
                text = "",
                parts = listOf(OpenCodeMessagePart(partId, messageId, sessionId, "text", "", false)),
            ),
        )
        environment.client.connectProject(serverId, directory)
        environment.client.connectGlobal(serverId)
        runCurrent()
        val projectSource = factory.sources.single { "/event?" in it.request.url }
        val globalSource = factory.sources.single { "/global/event" in it.request.url }
        globalSource.open()
        val delta = """{"type":"message.part.delta","properties":{"messageID":"$messageId","partID":"$partId","field":"text","delta":"x"}}"""

        globalSource.event(globalSyncEvent(directory, delta))
        assertEquals("x", environment.store.currentProject(serverId, directory).partsByMessage.getValue(messageId).single().text)
        projectSource.open()
        runCurrent()
        projectSource.event(delta)
        assertEquals("x", environment.store.currentProject(serverId, directory).partsByMessage.getValue(messageId).single().text)

        projectSource.event(delta)
        assertEquals("xx", environment.store.currentProject(serverId, directory).partsByMessage.getValue(messageId).single().text)
    }

    @Test
    fun globalProjectHandoffPrefersMatchingSseId() = runTest {
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory)
        val sessionId = "session"
        val messageId = "message"
        val partId = "part"
        environment.store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage(
                id = messageId,
                sessionId = sessionId,
                role = "assistant",
                text = "",
                parts = listOf(OpenCodeMessagePart(partId, messageId, sessionId, "text", "", false)),
            ),
        )
        environment.client.connectProject(serverId, directory)
        environment.client.connectGlobal(serverId)
        runCurrent()
        val projectSource = factory.sources.single { "/event?" in it.request.url }
        val globalSource = factory.sources.single { "/global/event" in it.request.url }
        globalSource.open()
        val globalDelta = partDeltaEvent(messageId, partId, "x")
        val differentlySerializedProjectDelta = partDeltaEvent(messageId, partId, "not-the-same-payload")

        globalSource.event(globalSyncEvent(directory, globalDelta), id = "shared-event")
        projectSource.open()
        runCurrent()
        projectSource.event(differentlySerializedProjectDelta, id = "shared-event")

        assertEquals("x", environment.store.currentProject(serverId, directory).partsByMessage.getValue(messageId).single().text)
    }

    @Test
    fun globalProjectEventIsIgnoredWhileOpenProjectSourceCalibrates() = runTest {
        val gate = CompletableDeferred<Result<LoadedProjectSnapshot>>()
        val loader = ScriptedProjectSnapshotLoader { _, _, _, _ -> gate.await() }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.client.connectProject(serverId, directory)
        environment.client.connectGlobal(serverId)
        runCurrent()
        val projectSource = factory.sources.single { "/event?" in it.request.url }
        val globalSource = factory.sources.single { "/global/event" in it.request.url }
        projectSource.open()
        globalSource.open()
        runCurrent()

        globalSource.event(
            globalSyncEvent(
                directory,
                sessionUpdatedEvent("session", "from-global", updatedAt = 2L),
            ),
        )
        assertTrue(environment.store.currentProject(serverId, directory).sessions.isEmpty())

        gate.complete(
            Result.success(
                loadedSnapshot(
                    serverId,
                    directory,
                    sessions = listOf(testSession("session", title = "snapshot", updatedAt = 1L)),
                ),
            ),
        )
        runCurrent()

        assertEquals("snapshot", environment.store.currentProject(serverId, directory).sessions.single().title)
        assertTrue(environment.reductions.isEmpty())
    }

    @Test
    fun globalCapabilityEventCalibratesProjectWithoutProjectSource() = runTest {
        val loader = ScriptedProjectSnapshotLoader { requestedServerId, requestedDirectory, workspace, _ ->
            Result.success(
                loadedSnapshot(
                    requestedServerId,
                    requestedDirectory,
                    workspace,
                    sessions = listOf(testSession("global-session", title = "from-global-calibration")),
                ),
            )
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.client.connectGlobal(serverId)
        runCurrent()
        val globalSource = factory.sources.single()
        globalSource.open()

        globalSource.event(globalSyncEvent(directory, capabilityEvent("lsp.updated")))
        runCurrent()

        assertEquals(1, loader.calls)
        assertEquals(
            "from-global-calibration",
            environment.store.currentProject(serverId, directory).sessions.single().title,
        )
    }

    @Test
    fun globalCapabilityEventCalibratesWhileProjectSourceIsFailed() = runTest {
        val loader = ScriptedProjectSnapshotLoader { requestedServerId, requestedDirectory, workspace, call ->
            Result.success(
                loadedSnapshot(
                    requestedServerId,
                    requestedDirectory,
                    workspace,
                    sessions = if (call == 1) {
                        emptyList()
                    } else {
                        listOf(testSession("fallback-session", title = "failed-project-fallback"))
                    },
                ),
            )
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.client.connectProject(serverId, directory)
        environment.client.connectGlobal(serverId)
        runCurrent()
        val projectSource = factory.sources.single { "/event?" in it.request.url }
        val globalSource = factory.sources.single { "/global/event" in it.request.url }
        projectSource.open()
        globalSource.open()
        runCurrent()

        projectSource.fail(responseCode = 401, responseMessage = "unauthorized")
        assertTrue(environment.store.currentProject(serverId, directory).connectionStatus is SseConnectionStatus.Failed)
        globalSource.event(globalSyncEvent(directory, capabilityEvent("mcp.tools.changed")))
        runCurrent()

        assertEquals(2, loader.calls)
        assertEquals(
            "failed-project-fallback",
            environment.store.currentProject(serverId, directory).sessions.single().title,
        )
    }

    @Test
    fun projectOpeningInvalidatesInFlightGlobalCalibration() = runTest {
        val globalGate = CompletableDeferred<Unit>()
        val loader = ScriptedProjectSnapshotLoader { requestedServerId, requestedDirectory, workspace, call ->
            if (call == 1) {
                withContext(NonCancellable) { globalGate.await() }
                Result.success(
                    loadedSnapshot(
                        requestedServerId,
                        requestedDirectory,
                        workspace,
                        sessions = listOf(testSession("session", title = "stale-global")),
                    ),
                )
            } else {
                Result.success(
                    loadedSnapshot(
                        requestedServerId,
                        requestedDirectory,
                        workspace,
                        sessions = listOf(testSession("session", title = "project-authoritative")),
                    ),
                )
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.client.connectGlobal(serverId)
        runCurrent()
        val globalSource = factory.sources.single()
        globalSource.open()
        globalSource.event(globalSyncEvent(directory, capabilityEvent("lsp.updated")))
        runCurrent()
        assertEquals(1, loader.calls)

        environment.client.connectProject(serverId, directory)
        runCurrent()
        val projectSource = factory.sources.single { "/event?" in it.request.url }
        projectSource.open()
        runCurrent()

        assertEquals(2, loader.calls)
        assertEquals(
            "project-authoritative",
            environment.store.currentProject(serverId, directory).sessions.single().title,
        )

        globalGate.complete(Unit)
        runCurrent()

        assertEquals(
            "project-authoritative",
            environment.store.currentProject(serverId, directory).sessions.single().title,
        )
    }

    @Test
    fun capabilityTriggersDuringCalibrationMergeIntoOneFollowUpFlight() = runTest {
        val gate = CompletableDeferred<Result<LoadedProjectSnapshot>>()
        val loader = ScriptedProjectSnapshotLoader { _, _, _, _ -> gate.await() }
        val provider = MutableSseConnectionProvider()
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory, loader)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        val source = factory.sources.single()

        source.open()
        source.open()
        runCurrent()
        source.event(capabilityEvent("lsp.updated"))
        source.event(capabilityEvent("mcp.tools.changed"))
        environment.client.onForegroundConnectionReady(serverId, provider.identity)
        runCurrent()

        assertEquals(1, loader.calls)
        assertEquals(1, environment.snapshotCoordinator.activeFlightCount())

        gate.complete(Result.success(loadedSnapshot(serverId, directory, identity = provider.identity)))
        runCurrent()
        assertEquals(2, loader.calls)
        assertEquals(SseConnectionStatus.Open, environment.store.currentProject(serverId, directory).connectionStatus)
    }

    @Test
    fun failedDirtyCalibrationRunsOneFollowUpAndKeepsSseOpen() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val loader = ScriptedProjectSnapshotLoader { requestedServerId, requestedDirectory, workspace, call ->
            if (call == 1) {
                firstGate.await()
                Result.failure(IllegalStateException("first failed"))
            } else {
                Result.success(loadedSnapshot(requestedServerId, requestedDirectory, workspace))
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        val source = factory.sources.single()
        source.open()
        runCurrent()
        source.event(capabilityEvent("lsp.updated"))
        source.event(capabilityEvent("mcp.tools.changed"))

        firstGate.complete(Unit)
        runCurrent()

        assertEquals(2, loader.calls)
        assertEquals(0, environment.snapshotCoordinator.activeFlightCount())
        assertEquals(SseConnectionStatus.Open, environment.store.currentProject(serverId, directory).connectionStatus)
    }

    @Test
    fun dirtyBufferOverflowCancelsCurrentSnapshotAndRunsOneFollowUp() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val loader = ScriptedProjectSnapshotLoader { requestedServerId, requestedDirectory, workspace, call ->
            if (call == 1) {
                firstGate.await()
                error("cancelled snapshot unexpectedly completed")
            } else {
                Result.success(loadedSnapshot(requestedServerId, requestedDirectory, workspace))
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(
            scope = backgroundScope,
            factory = factory,
            loader = loader,
            maxBufferedEvents = 1,
        )
        environment.client.connectProject(serverId, directory)
        runCurrent()
        val source = factory.sources.single()
        source.open()
        runCurrent()

        source.event(capabilityEvent("lsp.updated"))
        source.event(capabilityEvent("mcp.tools.changed"))
        runCurrent()

        assertEquals(2, loader.calls)
        assertEquals(0, environment.snapshotCoordinator.activeFlightCount())
        assertEquals(SseConnectionStatus.Open, environment.store.currentProject(serverId, directory).connectionStatus)
    }

    @Test
    fun lateSnapshotFromClosedGenerationCannotOverwriteReplacement() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        val loader = ScriptedProjectSnapshotLoader { requestedServerId, requestedDirectory, workspace, call ->
            if (call == 1) {
                withContext(NonCancellable) { oldGate.await() }
                Result.success(
                    loadedSnapshot(
                        requestedServerId,
                        requestedDirectory,
                        workspace,
                        sessions = listOf(testSession("session", title = "old")),
                    ),
                )
            } else {
                Result.success(
                    loadedSnapshot(
                        requestedServerId,
                        requestedDirectory,
                        workspace,
                        sessions = listOf(testSession("session", title = "replacement")),
                    ),
                )
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        factory.sources.single().open()
        runCurrent()
        val oldGeneration = environment.client.projectLifecycleState(serverId, directory)!!.generation

        environment.client.reconnectProject(serverId, directory)
        runCurrent()
        factory.sources.last().open()
        runCurrent()
        assertEquals("replacement", environment.store.currentProject(serverId, directory).sessions.single().title)

        oldGate.complete(Unit)
        runCurrent()

        val state = environment.store.currentProject(serverId, directory)
        assertEquals("replacement", state.sessions.single().title)
        assertTrue(environment.client.projectLifecycleState(serverId, directory)!!.generation > oldGeneration)
        assertEquals(2, loader.calls)
    }

    @Test
    fun lateSnapshotAfterForceCloseIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val loader = ScriptedProjectSnapshotLoader { requestedServerId, requestedDirectory, workspace, _ ->
            withContext(NonCancellable) { gate.await() }
            Result.success(
                loadedSnapshot(
                    requestedServerId,
                    requestedDirectory,
                    workspace,
                    sessions = listOf(testSession("late", title = "late")),
                ),
            )
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, factory = factory, loader = loader)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        factory.sources.single().open()
        runCurrent()

        environment.client.closeProjectNow(serverId, directory)
        gate.complete(Unit)
        runCurrent()

        val state = environment.store.currentProject(serverId, directory)
        assertEquals(SseConnectionStatus.Closed, state.connectionStatus)
        assertTrue(state.sessions.isEmpty())
    }

    @Test
    fun thrownAndResultSnapshotFailuresNeverCloseSse() = runTest {
        val loader = ScriptedProjectSnapshotLoader { _, _, _, call ->
            if (call == 1) {
                throw IllegalStateException("password=throw-secret")
            } else {
                Result.failure(IllegalStateException("token=result-secret"))
            }
        }
        val provider = MutableSseConnectionProvider()
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory, loader)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        factory.sources.single().open()
        runCurrent()

        assertEquals(SseConnectionStatus.Open, environment.store.currentProject(serverId, directory).connectionStatus)
        environment.client.onForegroundConnectionReady(serverId, provider.identity)
        runCurrent()

        val state = environment.store.currentProject(serverId, directory)
        assertEquals(SseConnectionStatus.Open, state.connectionStatus)
        assertEquals(2, loader.calls)
        assertEquals(UiText.Resource(R.string.project_snapshot_load_failed), state.error)
    }

    @Test
    fun calibrationBufferIsBoundedAndOverflowReplaysWithoutLoss() = runTest {
        val gate = CompletableDeferred<Result<LoadedProjectSnapshot>>()
        val loader = ScriptedProjectSnapshotLoader { _, _, _, _ -> gate.await() }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(
            scope = backgroundScope,
            factory = factory,
            loader = loader,
            maxBufferedEvents = 2,
            maxBufferedBytes = 100_000,
        )
        environment.client.connectProject(serverId, directory)
        runCurrent()
        val source = factory.sources.single()
        source.open()
        runCurrent()

        source.event(sessionUpdatedEvent("one", "one", updatedAt = 1L))
        source.event(sessionUpdatedEvent("two", "two", updatedAt = 2L))
        source.event(sessionUpdatedEvent("three", "three", updatedAt = 3L))
        runCurrent()

        val state = environment.store.currentProject(serverId, directory)
        assertEquals(setOf("one", "two", "three"), state.sessions.map { it.id }.toSet())
        assertEquals(3, environment.reductions.size)
        assertEquals(0, environment.snapshotCoordinator.activeFlightCount())
        assertEquals(SseConnectionStatus.Open, state.connectionStatus)
    }

    @Test
    fun snapshotUsingNewTransportCannotApplyToOldSourceAndForcesRebuild() = runTest {
        val oldIdentity = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val newIdentity = testTransport(configEpoch = 1L, controlEpoch = 2L)
        val firstGate = CompletableDeferred<Unit>()
        val provider = MutableSseConnectionProvider(oldIdentity)
        val loader = ScriptedProjectSnapshotLoader { requestedServerId, requestedDirectory, workspace, call ->
            if (call == 1) {
                firstGate.await()
                Result.success(
                    loadedSnapshot(
                        requestedServerId,
                        requestedDirectory,
                        workspace,
                        identity = newIdentity,
                        sessions = listOf(testSession("session", title = "stale-transport")),
                    ),
                )
            } else {
                Result.success(
                    loadedSnapshot(
                        requestedServerId,
                        requestedDirectory,
                        workspace,
                        identity = newIdentity,
                        sessions = listOf(testSession("session", title = "new-transport")),
                    ),
                )
            }
        }
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory, loader)
        environment.client.connectProject(serverId, directory)
        runCurrent()
        factory.sources.single().open()
        runCurrent()
        val oldGeneration = environment.client.projectLifecycleState(serverId, directory)!!.generation

        provider.identity = newIdentity
        firstGate.complete(Unit)
        runCurrent()
        assertEquals(2, factory.sources.size)
        factory.sources.last().open()
        runCurrent()

        val state = environment.store.currentProject(serverId, directory)
        assertEquals("new-transport", state.sessions.single().title)
        assertFalse(state.sessions.any { it.title == "stale-transport" })
        assertEquals(newIdentity, environment.client.projectLifecycleState(serverId, directory)!!.transportIdentity)
        assertTrue(environment.client.projectLifecycleState(serverId, directory)!!.generation > oldGeneration)
    }
}
