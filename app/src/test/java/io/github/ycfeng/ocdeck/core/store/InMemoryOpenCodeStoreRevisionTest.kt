package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageBundle
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
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
