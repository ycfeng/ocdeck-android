package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageBundle
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProjectSnapshot
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryOpenCodeStoreRevisionTest {
    private val store = InMemoryOpenCodeStore(PathNormalizer())

    @Test
    fun staleVmSnapshotCannotOverwriteSseOrRecoverySnapshot() {
        val initialRevision = store.captureProjectDataRevision(serverId, directory)
        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject(
                """{"type":"session.updated","properties":{"info":{"id":"session","title":"from-sse","directory":"$directory","time":{"updated":"2"}}}}""",
            ),
        )

        assertFalse(
            store.applyProjectSnapshotIfRevision(
                snapshot(sessions = listOf(session("session", "stale-vm", 1L))),
                initialRevision,
            ),
        )
        assertEquals("from-sse", store.currentProject(serverId, directory).sessions.single().title)

        val revisionBeforeRecovery = store.captureProjectDataRevision(serverId, directory)
        store.applyProjectSnapshot(snapshot(sessions = listOf(session("session", "recovery", 3L))))

        assertFalse(
            store.applyProjectSnapshotIfRevision(
                snapshot(sessions = listOf(session("session", "late-vm", 2L))),
                revisionBeforeRecovery,
            ),
        )
        assertEquals("recovery", store.currentProject(serverId, directory).sessions.single().title)
    }

    @Test
    fun connectionStatusDoesNotInvalidateInitialSnapshotRevision() {
        val revision = store.captureProjectDataRevision(serverId, directory)
        store.setProjectConnectionStatus(serverId, directory, SseConnectionStatus.Open)

        assertTrue(store.applyProjectSnapshotIfRevision(snapshot(), revision))
        assertEquals(1L, store.captureProjectDataRevision(serverId, directory))
    }

    @Test
    fun messageLoadMergesHistoryWithoutLosingLiveMessageOrDelta() {
        val livePart = part("part-live", "message-live", "current")
        store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage(
                id = "message-live",
                sessionId = sessionId,
                role = "assistant",
                text = "current",
                parts = listOf(livePart),
                createdAt = 2L,
            ),
        )
        val loadRevision = store.captureMessageDataRevision(serverId, directory)
        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject(
                """{"type":"message.part.delta","properties":{"messageID":"message-live","partID":"part-live","field":"text","delta":"+delta"}}""",
            ),
        )
        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject(
                """{"type":"message.updated","properties":{"info":{"id":"message-new","sessionID":"$sessionId","role":"assistant","time":{"created":"3"}}}}""",
            ),
        )

        store.putMessages(
            serverId = serverId,
            directory = directory,
            sessionId = sessionId,
            expectedRevision = loadRevision,
            bundle = OpenCodeMessageBundle(
                messages = listOf(
                    OpenCodeMessage("message-history", sessionId, "user", "history", createdAt = 1L),
                    OpenCodeMessage("message-live", sessionId, "assistant", "old", createdAt = 2L),
                ),
                parts = listOf(
                    part("part-history", "message-history", "history"),
                    part("part-live", "message-live", "old"),
                ),
            ),
        )

        val state = store.currentProject(serverId, directory)
        assertEquals(setOf("message-history", "message-live", "message-new"), state.messagesBySession.getValue(sessionId).map { it.id }.toSet())
        assertEquals("current+delta", state.messagesBySession.getValue(sessionId).first { it.id == "message-live" }.text)
        assertEquals("current+delta", state.partsByMessage.getValue("message-live").single().text)
        assertTrue(store.captureMessageDataRevision(serverId, directory) > loadRevision)
    }

    @Test
    fun olderMessagePagesPrependAndAdvanceCursorWithoutReplacingNewerMessages() {
        putFirstMessagePage(
            page = OpenCodeMessagePage(
                bundle = OpenCodeMessageBundle(
                    messages = listOf(
                        OpenCodeMessage("message-3", sessionId, "user", "three", createdAt = 3L),
                        OpenCodeMessage("message-4", sessionId, "assistant", "four", createdAt = 4L),
                    ),
                    parts = emptyList(),
                ),
                nextCursor = "cursor-1",
            ),
        )

        val revision = store.captureMessageDataRevision(serverId, directory)
        store.putMessagePage(
            serverId = serverId,
            directory = directory,
            sessionId = sessionId,
            page = OpenCodeMessagePage(
                bundle = OpenCodeMessageBundle(
                    messages = listOf(
                        OpenCodeMessage("message-1", sessionId, "user", "one", createdAt = 1L),
                        OpenCodeMessage("message-2", sessionId, "assistant", "two", createdAt = 2L),
                    ),
                    parts = emptyList(),
                ),
                nextCursor = "cursor-2",
            ),
            before = "cursor-1",
            expectedRevision = revision,
        )

        val state = store.currentProject(serverId, directory)
        assertEquals(
            listOf("message-1", "message-2", "message-3", "message-4"),
            state.messagesBySession.getValue(sessionId).map(OpenCodeMessage::id),
        )
        assertEquals("cursor-2", state.messageHistoryBySession.getValue(sessionId).nextCursor)
    }

    @Test
    fun staleOlderPageCannotRollbackCursorOrMergeMessages() {
        putFirstMessagePage(
            OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(OpenCodeMessage("message-3", sessionId, "assistant", "three", createdAt = 3L)),
                    parts = emptyList(),
                ),
                nextCursor = "cursor-1",
            ),
        )
        val revision = store.captureMessageDataRevision(serverId, directory)
        assertEquals(
            MessagePageApplyResult.Applied,
            store.putMessagePage(
                serverId,
                directory,
                sessionId,
                OpenCodeMessagePage(
                    OpenCodeMessageBundle(
                        messages = listOf(OpenCodeMessage("message-2", sessionId, "user", "two", createdAt = 2L)),
                        parts = emptyList(),
                    ),
                    nextCursor = "cursor-2",
                ),
                before = "cursor-1",
                expectedRevision = revision,
            ),
        )

        assertEquals(
            MessagePageApplyResult.Stale,
            store.putMessagePage(
                serverId,
                directory,
                sessionId,
                OpenCodeMessagePage(
                    OpenCodeMessageBundle(
                        messages = listOf(OpenCodeMessage("stale-message", sessionId, "user", "stale", createdAt = 1L)),
                        parts = emptyList(),
                    ),
                    nextCursor = "stale-cursor",
                ),
                before = "cursor-1",
                expectedRevision = revision,
            ),
        )

        val state = store.currentProject(serverId, directory)
        assertEquals("cursor-2", state.messageHistoryBySession.getValue(sessionId).nextCursor)
        assertFalse(state.messagesBySession.getValue(sessionId).any { it.id == "stale-message" })
    }

    @Test
    fun consumedCursorCycleIsRejectedWithoutMutatingMessages() {
        putFirstMessagePage(
            OpenCodeMessagePage(OpenCodeMessageBundle(emptyList(), emptyList()), nextCursor = "cursor-a"),
        )
        var revision = store.captureMessageDataRevision(serverId, directory)
        store.putMessagePage(
            serverId,
            directory,
            sessionId,
            OpenCodeMessagePage(OpenCodeMessageBundle(emptyList(), emptyList()), nextCursor = "cursor-b"),
            before = "cursor-a",
            expectedRevision = revision,
        )
        revision = store.captureMessageDataRevision(serverId, directory)

        assertEquals(
            MessagePageApplyResult.CursorCycle,
            store.putMessagePage(
                serverId,
                directory,
                sessionId,
                OpenCodeMessagePage(
                    OpenCodeMessageBundle(
                        messages = listOf(OpenCodeMessage("cycle-message", sessionId, "user", "cycle")),
                        parts = emptyList(),
                    ),
                    nextCursor = "cursor-a",
                ),
                before = "cursor-b",
                expectedRevision = revision,
            ),
        )

        val state = store.currentProject(serverId, directory)
        assertEquals("cursor-b", state.messageHistoryBySession.getValue(sessionId).nextCursor)
        assertFalse(state.messagesBySession[sessionId].orEmpty().any { it.id == "cycle-message" })
    }

    @Test
    fun overlappingInitialRefreshPreservesLoadedHistoryAndOldestCursor() {
        putFirstMessagePage(
            page = OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(OpenCodeMessage("message-2", sessionId, "assistant", "two", createdAt = 2L)),
                    parts = emptyList(),
                ),
                nextCursor = "cursor-1",
            ),
        )
        var revision = store.captureMessageDataRevision(serverId, directory)
        store.putMessagePage(
            serverId = serverId,
            directory = directory,
            sessionId = sessionId,
            page = OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(OpenCodeMessage("message-1", sessionId, "user", "one", createdAt = 1L)),
                    parts = emptyList(),
                ),
                nextCursor = "cursor-2",
            ),
            before = "cursor-1",
            expectedRevision = revision,
        )

        putFirstMessagePage(
            page = OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(
                        OpenCodeMessage("message-2", sessionId, "assistant", "two", createdAt = 2L),
                        OpenCodeMessage("message-3", sessionId, "assistant", "three", createdAt = 3L),
                    ),
                    parts = emptyList(),
                ),
                nextCursor = "newest-page-cursor",
            ),
        )

        val state = store.currentProject(serverId, directory)
        assertEquals(
            listOf("message-1", "message-2", "message-3"),
            state.messagesBySession.getValue(sessionId).map(OpenCodeMessage::id),
        )
        assertEquals("cursor-2", state.messageHistoryBySession.getValue(sessionId).nextCursor)
    }

    @Test
    fun overlappingFirstPageRefreshUsesServerIdTieBreakAcrossPages() {
        putFirstMessagePage(
            OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(
                        OpenCodeMessage("message-b", sessionId, "user", "b", createdAt = 1L),
                        OpenCodeMessage("message-c", sessionId, "assistant", "c", createdAt = 1L),
                    ),
                    parts = emptyList(),
                ),
                nextCursor = "cursor-1",
            ),
        )
        store.putMessagePage(
            serverId = serverId,
            directory = directory,
            sessionId = sessionId,
            page = OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(OpenCodeMessage("message-a", sessionId, "assistant", "a", createdAt = 1L)),
                    parts = emptyList(),
                ),
                nextCursor = null,
            ),
            before = "cursor-1",
            expectedRevision = store.captureMessageDataRevision(serverId, directory),
        )

        putFirstMessagePage(
            OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(
                        OpenCodeMessage("message-b", sessionId, "user", "b", createdAt = 1L),
                        OpenCodeMessage("message-c", sessionId, "assistant", "c", createdAt = 1L),
                        OpenCodeMessage("message-d", sessionId, "assistant", "d", createdAt = 1L),
                    ),
                    parts = emptyList(),
                ),
                nextCursor = "refreshed-cursor",
            ),
        )

        assertEquals(
            listOf("message-a", "message-b", "message-c", "message-d"),
            store.currentProject(serverId, directory).messagesBySession.getValue(sessionId).map(OpenCodeMessage::id),
        )
    }

    @Test
    fun overlappingFirstPageRefreshReconcilesMissingCoveredMessagesAndParts() {
        putFirstMessagePage(
            OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(
                        OpenCodeMessage("message-b", sessionId, "user", "b", createdAt = 2L),
                        OpenCodeMessage("message-c", sessionId, "assistant", "c", createdAt = 3L),
                        OpenCodeMessage("message-d", sessionId, "assistant", "d", createdAt = 4L),
                    ),
                    parts = listOf(
                        part("part-b-old", "message-b", "b-old"),
                        part("part-c", "message-c", "c"),
                        part("part-d", "message-d", "d"),
                    ),
                ),
                nextCursor = "cursor-1",
            ),
        )
        store.putMessagePage(
            serverId = serverId,
            directory = directory,
            sessionId = sessionId,
            page = OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(OpenCodeMessage("message-a", sessionId, "assistant", "a", createdAt = 1L)),
                    parts = listOf(part("part-a", "message-a", "a")),
                ),
                nextCursor = null,
            ),
            before = "cursor-1",
            expectedRevision = store.captureMessageDataRevision(serverId, directory),
        )

        putFirstMessagePage(
            OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(
                        OpenCodeMessage("message-b", sessionId, "user", "b", createdAt = 2L),
                        OpenCodeMessage("message-d", sessionId, "assistant", "d", createdAt = 4L),
                        OpenCodeMessage("message-e", sessionId, "assistant", "e", createdAt = 5L),
                    ),
                    parts = listOf(part("part-b-new", "message-b", "b-new")),
                ),
                nextCursor = "refreshed-cursor",
            ),
        )

        val state = store.currentProject(serverId, directory)
        assertEquals(
            listOf("message-a", "message-b", "message-d", "message-e"),
            state.messagesBySession.getValue(sessionId).map(OpenCodeMessage::id),
        )
        assertEquals(listOf("part-a"), state.partsByMessage.getValue("message-a").map(OpenCodeMessagePart::id))
        assertEquals(listOf("part-b-new"), state.partsByMessage.getValue("message-b").map(OpenCodeMessagePart::id))
        assertFalse(state.partsByMessage.containsKey("message-c"))
        assertFalse(state.partsByMessage.containsKey("message-d"))
        assertEquals(null, state.messageHistoryBySession.getValue(sessionId).nextCursor)
    }

    @Test
    fun disjointInitialRefreshRestartsCursorChainToBridgeMissingWindow() {
        putFirstMessagePage(
            OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(
                        OpenCodeMessage("message-3", sessionId, "user", "three", createdAt = 3L),
                        OpenCodeMessage("message-4", sessionId, "assistant", "four", createdAt = 4L),
                    ),
                    parts = emptyList(),
                ),
                nextCursor = "cursor-a",
            ),
        )
        var revision = store.captureMessageDataRevision(serverId, directory)
        store.putMessagePage(
            serverId,
            directory,
            sessionId,
            OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(
                        OpenCodeMessage("message-1", sessionId, "user", "one", createdAt = 1L),
                        OpenCodeMessage("message-2", sessionId, "assistant", "two", createdAt = 2L),
                    ),
                    parts = emptyList(),
                ),
                nextCursor = "cursor-b",
            ),
            before = "cursor-a",
            expectedRevision = revision,
        )

        putFirstMessagePage(
            OpenCodeMessagePage(
                OpenCodeMessageBundle(
                    messages = listOf(
                        OpenCodeMessage("message-5", sessionId, "user", "five", createdAt = 5L),
                        OpenCodeMessage("message-6", sessionId, "assistant", "six", createdAt = 6L),
                    ),
                    parts = emptyList(),
                ),
                nextCursor = "cursor-c",
            ),
        )

        var history = store.currentProject(serverId, directory).messageHistoryBySession.getValue(sessionId)
        assertEquals("cursor-c", history.nextCursor)
        assertTrue(history.consumedCursors.isEmpty())

        revision = store.captureMessageDataRevision(serverId, directory)
        assertEquals(
            MessagePageApplyResult.Applied,
            store.putMessagePage(
                serverId,
                directory,
                sessionId,
                OpenCodeMessagePage(
                    OpenCodeMessageBundle(
                        messages = listOf(
                            OpenCodeMessage("message-3", sessionId, "user", "three", createdAt = 3L),
                            OpenCodeMessage("message-4", sessionId, "assistant", "four", createdAt = 4L),
                        ),
                        parts = emptyList(),
                    ),
                    nextCursor = "cursor-a",
                ),
                before = "cursor-c",
                expectedRevision = revision,
            ),
        )
        history = store.currentProject(serverId, directory).messageHistoryBySession.getValue(sessionId)
        assertEquals("cursor-a", history.nextCursor)
        assertEquals(setOf("cursor-c"), history.consumedCursors)

        revision = store.captureMessageDataRevision(serverId, directory)
        assertEquals(
            MessagePageApplyResult.Applied,
            store.putMessagePage(
                serverId,
                directory,
                sessionId,
                OpenCodeMessagePage(
                    OpenCodeMessageBundle(
                        messages = listOf(
                            OpenCodeMessage("message-1", sessionId, "user", "one", createdAt = 1L),
                            OpenCodeMessage("message-2", sessionId, "assistant", "two", createdAt = 2L),
                        ),
                        parts = emptyList(),
                    ),
                    nextCursor = "cursor-b",
                ),
                before = "cursor-a",
                expectedRevision = revision,
            ),
        )
        history = store.currentProject(serverId, directory).messageHistoryBySession.getValue(sessionId)
        assertEquals("cursor-b", history.nextCursor)
        assertEquals(setOf("cursor-c", "cursor-a"), history.consumedCursors)
        assertEquals(
            listOf("message-1", "message-2", "message-3", "message-4", "message-5", "message-6"),
            store.currentProject(serverId, directory).messagesBySession.getValue(sessionId).map(OpenCodeMessage::id),
        )
    }

    @Test
    fun staleFirstPageCannotOverwriteNewerMessagesOrCursor() {
        val staleRequest = store.beginMessageFirstPageRequest(serverId, directory, sessionId)
        val currentRequest = store.beginMessageFirstPageRequest(serverId, directory, sessionId)
        val currentPage = OpenCodeMessagePage(
            OpenCodeMessageBundle(
                messages = listOf(OpenCodeMessage("current-message", sessionId, "assistant", "current")),
                parts = emptyList(),
            ),
            nextCursor = "current-cursor",
        )

        assertEquals(
            MessagePageApplyResult.Applied,
            store.putMessagePage(
                serverId = serverId,
                directory = directory,
                sessionId = sessionId,
                page = currentPage,
                before = null,
                expectedRevision = currentRequest.expectedRevision,
                firstPageRequestGeneration = currentRequest.generation,
            ),
        )
        assertEquals(
            MessagePageApplyResult.Stale,
            store.putMessagePage(
                serverId = serverId,
                directory = directory,
                sessionId = sessionId,
                page = OpenCodeMessagePage(
                    OpenCodeMessageBundle(
                        messages = listOf(OpenCodeMessage("stale-message", sessionId, "user", "stale")),
                        parts = emptyList(),
                    ),
                    nextCursor = "stale-cursor",
                ),
                before = null,
                expectedRevision = staleRequest.expectedRevision,
                firstPageRequestGeneration = staleRequest.generation,
            ),
        )

        val state = store.currentProject(serverId, directory)
        assertEquals(listOf("current-message"), state.messagesBySession.getValue(sessionId).map(OpenCodeMessage::id))
        assertEquals("current-cursor", state.messageHistoryBySession.getValue(sessionId).nextCursor)
        assertEquals(setOf("current-message"), state.messageHistoryBySession.getValue(sessionId).latestPageMessageIds)
    }

    @Test
    fun equalRevisionReplacementKeepsMissingOptimisticMessageAndPartsAcceptable() {
        val optimisticPart = part("optimistic-part", "optimistic-message", "pending")
        store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage(
                id = "optimistic-message",
                sessionId = sessionId,
                role = "user",
                text = "pending",
                parts = listOf(optimisticPart),
                isOptimistic = true,
            ),
        )
        val revision = store.captureMessageDataRevision(serverId, directory)

        store.putMessages(
            serverId,
            directory,
            sessionId,
            OpenCodeMessageBundle(messages = emptyList(), parts = emptyList()),
            revision,
        )

        var message = store.currentProject(serverId, directory).messagesBySession.getValue(sessionId).single()
        assertTrue(message.isOptimistic)
        assertEquals(listOf(optimisticPart), store.currentProject(serverId, directory).partsByMessage[message.id])

        store.markMessageAccepted(serverId, directory, sessionId, message.id)

        message = store.currentProject(serverId, directory).messagesBySession.getValue(sessionId).single()
        assertFalse(message.isOptimistic)
    }

    @Test
    fun concurrentMetadataMessageRebuildsEmptyTextFromMergedHistoricalParts() {
        val revision = store.captureMessageDataRevision(serverId, directory)
        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject(
                """{"type":"message.updated","properties":{"info":{"id":"metadata-message","sessionID":"$sessionId","role":"assistant"}}}""",
            ),
        )

        store.putMessages(
            serverId,
            directory,
            sessionId,
            OpenCodeMessageBundle(
                messages = listOf(OpenCodeMessage("metadata-message", sessionId, "assistant", "historical text")),
                parts = listOf(part("historical-part", "metadata-message", "historical text")),
            ),
            revision,
        )

        val state = store.currentProject(serverId, directory)
        assertEquals("historical text", state.messagesBySession.getValue(sessionId).single().text)
        assertEquals("historical text", state.partsByMessage.getValue("metadata-message").single().text)
    }

    @Test
    fun staleMessageLoadDoesNotResurrectRealtimeRemovals() {
        val missingMessageRevision = store.captureMessageDataRevision(serverId, directory)
        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject("""{"type":"message.removed","properties":{"messageID":"removed-message"}}"""),
        )
        store.putMessages(
            serverId,
            directory,
            sessionId,
            OpenCodeMessageBundle(
                messages = listOf(OpenCodeMessage("removed-message", sessionId, "assistant", "stale")),
                parts = emptyList(),
            ),
            missingMessageRevision,
        )
        assertTrue(store.currentProject(serverId, directory).messagesBySession[sessionId].orEmpty().isEmpty())

        val livePart = part("removed-part", "live-message", "current")
        store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage("live-message", sessionId, "assistant", "current", parts = listOf(livePart)),
        )
        val partRevision = store.captureMessageDataRevision(serverId, directory)
        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject(
                """{"type":"message.part.removed","properties":{"messageID":"live-message","partID":"removed-part"}}""",
            ),
        )
        store.putMessages(
            serverId,
            directory,
            sessionId,
            OpenCodeMessageBundle(
                messages = listOf(OpenCodeMessage("live-message", sessionId, "assistant", "stale")),
                parts = listOf(part("removed-part", "live-message", "stale")),
            ),
            partRevision,
        )

        val state = store.currentProject(serverId, directory)
        assertTrue(state.partsByMessage.getValue("live-message").isEmpty())
        assertEquals("", state.messagesBySession.getValue(sessionId).single().text)
    }

    @Test
    fun removingActiveSessionClearsActiveSessionId() {
        store.setActiveSession(serverId, directory, sessionId)

        store.removeSession(serverId, directory, sessionId)

        assertEquals(null, store.currentProject(serverId, directory).activeSessionId)
    }

    @Test
    fun removingDifferentSessionKeepsActiveSessionId() {
        store.setActiveSession(serverId, directory, sessionId)

        store.removeSession(serverId, directory, "other-session")

        assertEquals(sessionId, store.currentProject(serverId, directory).activeSessionId)
    }

    @Test
    fun sessionDeletionTombstonesAllMessagesAndPartsAgainstStaleLoad() {
        val existingPart = part("session-part", "session-message", "current")
        store.upsertMessage(
            serverId,
            directory,
            OpenCodeMessage("session-message", sessionId, "assistant", "current", parts = listOf(existingPart)),
        )
        val revision = store.captureMessageDataRevision(serverId, directory)
        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject("""{"type":"session.deleted","properties":{"sessionID":"$sessionId"}}"""),
        )

        store.putMessages(
            serverId,
            directory,
            sessionId,
            OpenCodeMessageBundle(
                messages = listOf(OpenCodeMessage("session-message", sessionId, "assistant", "stale")),
                parts = listOf(part("session-part", "session-message", "stale")),
            ),
            revision,
        )

        val state = store.currentProject(serverId, directory)
        assertTrue(state.messagesBySession[sessionId].orEmpty().isEmpty())
        assertFalse(state.partsByMessage.containsKey("session-message"))
    }

    @Test
    fun sessionDeletionWithoutLocalMessagesRejectsEarlierMessageLoad() {
        val revision = store.captureMessageDataRevision(serverId, directory)

        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject("""{"type":"session.deleted","properties":{"sessionID":"$sessionId"}}"""),
        )

        val deletedState = store.currentProject(serverId, directory)
        assertTrue(deletedState.messageDataRevision > revision)
        assertEquals(deletedState.messageDataRevision, deletedState.removedSessionRevisions[sessionId])

        store.putMessages(
            serverId,
            directory,
            sessionId,
            OpenCodeMessageBundle(
                messages = listOf(OpenCodeMessage("historical-message", sessionId, "assistant", "stale")),
                parts = listOf(part("historical-part", "historical-message", "stale")),
            ),
            revision,
        )

        val state = store.currentProject(serverId, directory)
        assertTrue(state.messagesBySession[sessionId].orEmpty().isEmpty())
        assertFalse(state.partsByMessage.containsKey("historical-message"))
    }

    @Test
    fun sessionDeletionClearsHistoryAndRejectsItsStalePage() {
        putFirstMessagePage(
            OpenCodeMessagePage(OpenCodeMessageBundle(emptyList(), emptyList()), nextCursor = "cursor-1"),
        )
        val pageRevision = store.captureMessageDataRevision(serverId, directory)
        val pendingRequest = store.beginMessageFirstPageRequest(serverId, directory, sessionId)
        assertEquals(
            pendingRequest.generation,
            store.currentProject(serverId, directory).messageFirstPageRequestGenerations[sessionId],
        )

        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject("""{"type":"session.deleted","properties":{"sessionID":"$sessionId"}}"""),
        )

        assertFalse(store.currentProject(serverId, directory).messageHistoryBySession.containsKey(sessionId))
        assertFalse(store.currentProject(serverId, directory).messageFirstPageRequestGenerations.containsKey(sessionId))
        assertEquals(
            MessagePageApplyResult.Stale,
            store.putMessagePage(
                serverId,
                directory,
                sessionId,
                OpenCodeMessagePage(OpenCodeMessageBundle(emptyList(), emptyList()), nextCursor = null),
                before = "cursor-1",
                expectedRevision = pageRevision,
            ),
        )
        assertEquals(
            MessagePageApplyResult.Stale,
            store.putMessagePage(
                serverId = serverId,
                directory = directory,
                sessionId = sessionId,
                page = OpenCodeMessagePage(OpenCodeMessageBundle(emptyList(), emptyList()), nextCursor = null),
                before = null,
                expectedRevision = pendingRequest.expectedRevision,
                firstPageRequestGeneration = pendingRequest.generation,
            ),
        )
        assertFalse(store.currentProject(serverId, directory).messageHistoryBySession.containsKey(sessionId))
    }

    @Test
    fun removeSessionClearsPendingFirstPageGeneration() {
        val pendingRequest = store.beginMessageFirstPageRequest(serverId, directory, sessionId)
        assertTrue(store.currentProject(serverId, directory).messageFirstPageRequestGenerations.containsKey(sessionId))

        store.removeSession(serverId, directory, sessionId)

        assertFalse(store.currentProject(serverId, directory).messageFirstPageRequestGenerations.containsKey(sessionId))
        assertEquals(
            MessagePageApplyResult.Stale,
            store.putMessagePage(
                serverId = serverId,
                directory = directory,
                sessionId = sessionId,
                page = OpenCodeMessagePage(OpenCodeMessageBundle(emptyList(), emptyList()), nextCursor = null),
                before = null,
                expectedRevision = pendingRequest.expectedRevision,
                firstPageRequestGeneration = pendingRequest.generation,
            ),
        )
    }

    @Test
    fun liveMessageRebuildClearsSessionDeletionTombstone() {
        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject("""{"type":"session.deleted","properties":{"sessionID":"$sessionId"}}"""),
        )
        assertTrue(store.currentProject(serverId, directory).removedSessionRevisions.containsKey(sessionId))

        store.reduceProjectEvent(
            serverId,
            directory,
            jsonObject(
                """{"type":"message.updated","properties":{"info":{"id":"rebuilt-message","sessionID":"$sessionId","role":"assistant"}}}""",
            ),
        )

        val state = store.currentProject(serverId, directory)
        assertFalse(state.removedSessionRevisions.containsKey(sessionId))
        assertEquals("rebuilt-message", state.messagesBySession.getValue(sessionId).single().id)
    }

    private fun putFirstMessagePage(
        page: OpenCodeMessagePage,
        targetSessionId: String = sessionId,
    ): MessagePageApplyResult {
        val request = store.beginMessageFirstPageRequest(serverId, directory, targetSessionId)
        return store.putMessagePage(
            serverId = serverId,
            directory = directory,
            sessionId = targetSessionId,
            page = page,
            before = null,
            expectedRevision = request.expectedRevision,
            firstPageRequestGeneration = request.generation,
        )
    }

    private fun snapshot(sessions: List<OpenCodeSession> = emptyList()) = OpenCodeProjectSnapshot(
        serverId = serverId,
        normalizedDirectory = directory,
        pathInfo = null,
        sessions = sessions,
        statuses = emptyMap(),
        providerCount = 0,
        models = emptyList(),
        agents = emptyList(),
        commands = emptyList(),
        promptCapabilities = PromptCapabilities(isLoaded = true),
        mcps = emptyList(),
        lsps = emptyList(),
        plugins = emptyList(),
        commandCount = 0,
        permissionCount = 0,
        questionCount = 0,
    )

    private fun session(id: String, title: String, updatedAt: Long) = OpenCodeSession(
        id = id,
        title = title,
        normalizedDirectory = directory,
        path = null,
        parentId = null,
        agent = null,
        modelLabel = null,
        updatedAt = updatedAt,
        archivedAt = null,
    )

    private fun part(id: String, messageId: String, text: String) = OpenCodeMessagePart(
        id = id,
        messageId = messageId,
        sessionId = sessionId,
        type = "text",
        text = text,
        synthetic = false,
    )

    private fun jsonObject(value: String) = Json.parseToJsonElement(value).jsonObject

    private companion object {
        const val serverId = "server"
        const val directory = "E:/work/app"
        const val sessionId = "session"
    }
}
