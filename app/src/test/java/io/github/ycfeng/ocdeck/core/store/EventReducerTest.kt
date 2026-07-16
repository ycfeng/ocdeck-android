package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommentOrigin
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionInfo
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventReducerTest {
    private val reducer = OpenCodeEventReducer()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sessionUpdateUpsertsWithoutClearingExistingState() {
        val state = OpenCodeProjectState(
            serverId = "local",
            normalizedDirectory = "E:/work/app",
            providerCount = 2,
        )
        val event = jsonObject(
            """
            {
              "type": "session.updated",
              "properties": {
                "info": {
                  "id": "ses_1",
                  "title": "Build app",
                  "directory": "E:/work/app",
                  "time": { "updated": 12 }
                }
              }
            }
            """,
        )

        val next = reducer.reduce(state, event)

        assertEquals(1, next.sessions.size)
        assertEquals("Build app", next.sessions.single().title)
        assertEquals(2, next.providerCount)
    }

    @Test
    fun sessionUpdateSetsAndClearsRevertWithoutDeletingMessages() {
        val message = OpenCodeMessage(
            id = "msg_200",
            sessionId = "ses_1",
            role = "user",
            text = "Restore me",
        )
        val state = OpenCodeProjectState(
            serverId = "local",
            normalizedDirectory = "/repo",
            sessions = listOf(
                OpenCodeSession(
                    id = "ses_1",
                    title = "Session",
                    normalizedDirectory = "/repo",
                    path = null,
                    parentId = null,
                    agent = null,
                    modelLabel = null,
                    updatedAt = null,
                    archivedAt = null,
                ),
            ),
            messagesBySession = mapOf("ses_1" to listOf(message)),
        )
        val revertedEvent = jsonObject(
            """
            {
              "type": "session.updated",
              "properties": {
                "info": {
                  "id": "ses_1",
                  "title": "Session",
                  "directory": "/repo",
                  "revert": { "messageID": "msg_200" }
                }
              }
            }
            """,
        )
        val restoredEvent = jsonObject(
            """
            {
              "type": "session.updated",
              "properties": {
                "info": {
                  "id": "ses_1",
                  "title": "Session",
                  "directory": "/repo"
                }
              }
            }
            """,
        )

        val reverted = reducer.reduce(state, revertedEvent)
        val restored = reducer.reduce(reverted, restoredEvent)

        assertEquals("msg_200", reverted.sessions.single().revert?.messageId)
        assertEquals(listOf(message), reverted.messagesBySession.getValue("ses_1"))
        assertNull(restored.sessions.single().revert)
        assertEquals(listOf(message), restored.messagesBySession.getValue("ses_1"))
    }

    @Test
    fun messageAndPartEventsBuildRenderedText() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val messageEvent = jsonObject(
            """
            {
              "type": "message.updated",
              "properties": {
                "info": { "id": "msg1", "sessionID": "ses1", "role": "assistant" },
                "parts": [
                  { "id": "prt1", "messageID": "msg1", "sessionID": "ses1", "type": "text", "text": "Hello" }
                ]
              }
            }
            """,
        )
        val deltaEvent = jsonObject(
            """
            {
              "type": "part.delta",
              "properties": {
                "messageID": "msg1",
                "partID": "prt1",
                "field": "text",
                "delta": " world"
              }
            }
            """,
        )

        val next = reducer.reduce(reducer.reduce(state, messageEvent), deltaEvent)

        assertEquals("Hello world", next.messagesBySession.getValue("ses1").single().text)
    }

    @Test
    fun messagePartEventsConfirmMatchingOptimisticMessage() {
        val optimisticMessage = OpenCodeMessage(
            id = "msg1",
            sessionId = "ses1",
            role = "user",
            text = "pending",
            isOptimistic = true,
        )
        val existingPart = OpenCodeMessagePart("prt1", "msg1", "ses1", "text", "pending", false)
        val state = OpenCodeProjectState(
            serverId = "local",
            normalizedDirectory = "/repo",
            messagesBySession = mapOf("ses1" to listOf(optimisticMessage)),
            partsByMessage = mapOf("msg1" to listOf(existingPart)),
        )
        val delta = jsonObject(
            """{"type":"message.part.delta","properties":{"messageID":"msg1","partID":"prt1","field":"text","delta":"+delta"}}""",
        )
        val nonTextDelta = jsonObject(
            """{"type":"message.part.delta","properties":{"messageID":"msg1","partID":"prt1","field":"metadata","delta":"ignored"}}""",
        )
        val updated = jsonObject(
            """{"type":"message.part.updated","properties":{"part":{"id":"prt1","messageID":"msg1","sessionID":"ses1","type":"text","text":"updated"}}}""",
        )
        val removed = jsonObject(
            """{"type":"message.part.removed","properties":{"messageID":"msg1","partID":"prt1"}}""",
        )

        assertFalse(reducer.reduce(state, delta).messagesBySession.getValue("ses1").single().isOptimistic)
        assertFalse(reducer.reduce(state, nonTextDelta).messagesBySession.getValue("ses1").single().isOptimistic)
        assertFalse(reducer.reduce(state, updated).messagesBySession.getValue("ses1").single().isOptimistic)
        assertFalse(reducer.reduce(state, removed).messagesBySession.getValue("ses1").single().isOptimistic)
    }

    @Test
    fun messagePartUpdatedEventsUseServerShape() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val partUpdatedEvent = jsonObject(
            """
            {
              "type": "message.part.updated",
              "properties": {
                "sessionID": "ses1",
                "part": {
                  "id": "prt1",
                  "messageID": "msg1",
                  "sessionID": "ses1",
                  "type": "text",
                  "text": ""
                }
              }
            }
            """,
        )
        val deltaEvent = jsonObject(
            """
            {
              "type": "message.part.delta",
              "properties": {
                "sessionID": "ses1",
                "messageID": "msg1",
                "partID": "prt1",
                "field": "text",
                "delta": "ok"
              }
            }
            """,
        )
        val messageUpdatedEvent = jsonObject(
            """
            {
              "type": "message.updated",
              "properties": {
                "sessionID": "ses1",
                "info": {
                  "id": "msg1",
                  "sessionID": "ses1",
                  "role": "assistant",
                  "time": { "created": 1, "completed": 2 }
                }
              }
            }
            """,
        )

        val next = reducer.reduce(
            reducer.reduce(
                reducer.reduce(state, partUpdatedEvent),
                deltaEvent,
            ),
            messageUpdatedEvent,
        )
        val message = next.messagesBySession.getValue("ses1").single()

        assertEquals("ok", message.text)
        assertEquals("assistant", message.role)
        assertEquals(2L, message.completedAt)
    }

    @Test
    fun messagePartUpdatedParsesToolStateAndInput() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val event = jsonObject(
            """
            {
              "type": "message.part.updated",
              "properties": {
                "part": {
                  "id": "prt1",
                  "messageID": "msg1",
                  "sessionID": "ses1",
                  "type": "tool",
                  "tool": "read",
                  "state": {
                    "status": "running",
                    "input": {
                      "filePath": "/repo/src/Main.kt",
                      "offset": 12,
                      "limit": 40
                    }
                  }
                }
              }
            }
            """,
        )

        val next = reducer.reduce(state, event)
        val part = next.partsByMessage.getValue("msg1").single()

        assertEquals("tool", part.type)
        assertEquals("read", part.tool)
        assertEquals("running", part.stateStatus)
        assertEquals("/repo/src/Main.kt", part.toolInput["filePath"])
        assertEquals("12", part.toolInput["offset"])
        assertEquals("40", part.toolInput["limit"])
    }

    @Test
    fun messagePartUpdatedPreservesCommentMetadata() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val event = jsonObject(
            """
            {
              "type": "message.part.updated",
              "properties": {
                "part": {
                  "id": "prt_comment",
                  "messageID": "msg1",
                  "sessionID": "ses1",
                  "type": "text",
                  "text": "The user made the following comment regarding line 8 of src/Main.kt: Use the shared helper",
                  "synthetic": true,
                  "metadata": {
                    "opencodeComment": {
                      "path": "src/Main.kt",
                      "selection": {
                        "startLine": 8,
                        "startChar": 0,
                        "endLine": 8,
                        "endChar": 0
                      },
                      "comment": "Use the shared helper",
                      "preview": "return helper()",
                      "origin": "file"
                    }
                  }
                }
              }
            }
            """,
        )

        val next = reducer.reduce(state, event)
        val part = next.partsByMessage.getValue("msg1").single()

        assertEquals("", next.messagesBySession.getValue("ses1").single().text)
        assertEquals("src/Main.kt", part.opencodeComment?.path)
        assertEquals(8, part.opencodeComment?.selection?.startLine)
        assertEquals("Use the shared helper", part.opencodeComment?.comment)
        assertEquals("return helper()", part.opencodeComment?.preview)
        assertEquals(OpenCodeCommentOrigin.File, part.opencodeComment?.origin)
    }

    @Test
    fun messagePartUpdatedPreservesImageAttachmentFields() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val event = jsonObject(
            """
            {
              "type": "message.part.updated",
              "properties": {
                "part": {
                  "id": "prt_image",
                  "messageID": "msg1",
                  "sessionID": "ses1",
                  "type": "file",
                  "filename": "screenshot.png",
                  "mime": "image/png",
                  "url": "data:image/png;base64,AA=="
                }
              }
            }
            """,
        )

        val next = reducer.reduce(state, event)
        val part = next.partsByMessage.getValue("msg1").single()

        assertEquals("file", part.type)
        assertEquals("screenshot.png", part.filename)
        assertEquals("image/png", part.mime)
        assertEquals("data:image/png;base64,AA==", part.url)
    }

    @Test
    fun messagePartUpdatedParsesShellOutputMetadataAndError() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val completedEvent = jsonObject(
            """
            {
              "type": "message.part.updated",
              "properties": {
                "part": {
                  "id": "prt1",
                  "messageID": "msg1",
                  "sessionID": "ses1",
                  "type": "tool",
                  "tool": "bash",
                  "state": {
                    "status": "completed",
                    "input": { "command": "playwright-cli response-body 37" },
                    "output": "body text",
                    "metadata": {
                      "output": "body preview",
                      "exit": 0,
                      "truncated": false
                    }
                  }
                }
              }
            }
            """,
        )
        val errorEvent = jsonObject(
            """
            {
              "type": "message.part.updated",
              "properties": {
                "part": {
                  "id": "prt2",
                  "messageID": "msg1",
                  "sessionID": "ses1",
                  "type": "tool",
                  "tool": "bash",
                  "state": {
                    "status": "error",
                    "input": { "command": "bad-command" },
                    "error": "command not found"
                  }
                }
              }
            }
            """,
        )

        val next = reducer.reduce(reducer.reduce(state, completedEvent), errorEvent)
        val completed = next.partsByMessage.getValue("msg1").first { it.id == "prt1" }
        val error = next.partsByMessage.getValue("msg1").first { it.id == "prt2" }

        assertEquals("bash", completed.tool)
        assertEquals("playwright-cli response-body 37", completed.toolInput["command"])
        assertEquals("{\"command\":\"playwright-cli response-body 37\"}", completed.toolInputJson)
        assertEquals("body text", completed.toolOutput)
        assertEquals("body preview", completed.toolMetadata["output"])
        assertEquals("0", completed.toolMetadata["exit"])
        assertEquals("false", completed.toolMetadata["truncated"])
        assertEquals("{\"output\":\"body preview\",\"exit\":0,\"truncated\":false}", completed.toolMetadataJson)
        assertEquals("command not found", error.toolError)
    }

    @Test
    fun permissionEventsUpsertAndRemovePendingRequest() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val askedEvent = jsonObject(
            """
            {
              "type": "permission.asked",
              "properties": {
                "permission": {
                  "id": "per1",
                  "sessionID": "ses1",
                  "permission": "external_directory",
                  "patterns": ["E:/outside/*"],
                  "always": ["E:/outside/*"],
                  "tool": { "messageID": "msg1", "callID": "call1" }
                }
              }
            }
            """,
        )
        val repliedEvent = jsonObject(
            """
            {
              "type": "permission.replied",
              "properties": {
                "sessionID": "ses1",
                "requestID": "per1"
              }
            }
            """,
        )

        val pending = reducer.reduce(state, askedEvent)
        val request = pending.permissionsBySession.getValue("ses1").single()

        assertEquals(1, pending.permissionCount)
        assertEquals("external_directory", request.permission)
        assertEquals(listOf("E:/outside/*"), request.patterns)
        assertEquals("msg1", request.tool?.messageId)

        val cleared = reducer.reduce(pending, repliedEvent)

        assertEquals(0, cleared.permissionCount)
        assertFalse(cleared.permissionsBySession.containsKey("ses1"))
    }

    @Test
    fun messagePartUpdatedPreservesApplyPatchMetadataFilesJson() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val event = jsonObject(
            """
            {
              "type": "message.part.updated",
              "properties": {
                "part": {
                  "id": "prt1",
                  "messageID": "msg1",
                  "sessionID": "ses1",
                  "type": "tool",
                  "tool": "apply_patch",
                  "state": {
                    "status": "completed",
                    "input": { "patchText": "*** Begin Patch" },
                    "output": "Success. Updated the following files:\nM app/src/Main.kt",
                    "metadata": {
                      "files": [
                        {
                          "filePath": "E:/repo/app/src/Main.kt",
                          "relativePath": "app/src/Main.kt",
                          "type": "update",
                          "diff": "@@\n+line",
                          "additions": 1,
                          "deletions": 0
                        }
                      ],
                      "truncated": false
                    }
                  }
                }
              }
            }
            """,
        )

        val next = reducer.reduce(state, event)
        val part = next.partsByMessage.getValue("msg1").single()

        assertEquals("apply_patch", part.tool)
        assertEquals("*** Begin Patch", part.toolInput["patchText"])
        assertTrue(part.toolMetadataJson.orEmpty().contains("\"files\""))
        assertTrue(part.toolMetadataJson.orEmpty().contains("app/src/Main.kt"))
        assertEquals("false", part.toolMetadata["truncated"])
    }

    @Test
    fun metadataOnlyMessageUpdatePreservesExistingLocalText() {
        val state = OpenCodeProjectState(
            serverId = "local",
            normalizedDirectory = "/repo",
            messagesBySession = mapOf(
                "ses1" to listOf(
                    OpenCodeMessage(
                        id = "msg1",
                        sessionId = "ses1",
                        role = "user",
                        text = "probe0703",
                        createdAt = 42L,
                        isOptimistic = true,
                    ),
                ),
            ),
        )
        val event = jsonObject(
            """
            {
              "type": "message.updated",
              "properties": {
                "info": { "id": "msg1", "sessionID": "ses1", "role": "user" }
              }
            }
            """,
        )

        val next = reducer.reduce(state, event)
        val message = next.messagesBySession.getValue("ses1").single()

        assertEquals("probe0703", message.text)
        assertEquals(42L, message.createdAt)
        assertFalse(message.isOptimistic)
    }

    @Test
    fun messageUpdateParsesTokensAndMetadataUpdatePreservesThem() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val messageEvent = jsonObject(
            """
            {
              "type": "message.updated",
              "properties": {
                "info": {
                  "id": "msg1",
                  "sessionID": "ses1",
                  "role": "assistant",
                  "tokens": {
                    "input": 17691,
                    "output": 283,
                    "reasoning": 102,
                    "cache": { "read": 22528, "write": 0 }
                  }
                },
                "parts": [
                  { "id": "prt1", "messageID": "msg1", "sessionID": "ses1", "type": "text", "text": "done" }
                ]
              }
            }
            """,
        )
        val metadataOnlyEvent = jsonObject(
            """
            {
              "type": "message.updated",
              "properties": {
                "info": { "id": "msg1", "sessionID": "ses1", "role": "assistant" }
              }
            }
            """,
        )

        val next = reducer.reduce(reducer.reduce(state, messageEvent), metadataOnlyEvent)
        val message = next.messagesBySession.getValue("ses1").single()

        assertEquals("done", message.text)
        assertEquals(
            OpenCodeSessionTokens(input = 17_691, output = 283, reasoning = 102, cacheRead = 22_528),
            message.tokens,
        )
    }

    @Test
    fun globalSyncWrapperIsReduced() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val event = jsonObject(
            """
            {
              "directory": "/repo",
              "payload": {
                "type": "sync",
                "syncEvent": {
                  "type": "session.deleted",
                  "properties": { "sessionID": "ses1" }
                }
              }
            }
            """,
        )
        val seeded = state.copy(
            sessions = listOf(
                io.github.ycfeng.ocdeck.domain.model.OpenCodeSession(
                    id = "ses1",
                    title = "old",
                    normalizedDirectory = "/repo",
                    path = null,
                    parentId = null,
                    agent = null,
                    modelLabel = null,
                    updatedAt = null,
                    archivedAt = null,
                ),
            ),
            messagesBySession = mapOf(
                "ses1" to listOf(OpenCodeMessage("msg1", "ses1", "assistant", "old")),
            ),
            partsByMessage = mapOf(
                "msg1" to listOf(
                    OpenCodeMessagePart("part1", "msg1", "ses1", "text", "old", synthetic = false),
                ),
            ),
        )

        val next = reducer.reduce(seeded, event)

        assertTrue(next.sessions.isEmpty())
        assertFalse(next.messagesBySession.containsKey("ses1"))
        assertFalse(next.partsByMessage.containsKey("msg1"))
    }

    @Test
    fun questionAskedUpsertsPendingRequest() {
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val event = jsonObject(
            """
            {
              "type": "question.asked",
              "properties": {
                "id": "que_1",
                "sessionID": "ses1",
                "questions": [
                  {
                    "header": "Implementation",
                    "question": "Which implementation should be used?",
                    "multiple": true,
                    "custom": false,
                    "options": [
                      { "label": "Minimal", "description": "Use the smallest correct change" },
                      { "label": "Extended" }
                    ]
                  }
                ],
                "tool": { "messageID": "msg1", "callID": "call1" }
              }
            }
            """,
        )

        val next = reducer.reduce(state, event)
        val request = next.questionsBySession.getValue("ses1").single()

        assertEquals(1, next.questionCount)
        assertEquals("que_1", request.id)
        assertEquals("ses1", request.sessionId)
        assertEquals("Which implementation should be used?", request.questions.single().question)
        assertEquals("Implementation", request.questions.single().header)
        assertTrue(request.questions.single().multiple)
        assertFalse(request.questions.single().custom)
        assertEquals("Minimal", request.questions.single().options.first().label)
        assertEquals("call1", request.tool?.callId)
    }

    @Test
    fun questionReplyAndRejectRemovePendingRequest() {
        val first = OpenCodeQuestionRequest(
            id = "que_1",
            sessionId = "ses1",
            questions = listOf(OpenCodeQuestionInfo(header = null, question = "First?", options = emptyList())),
        )
        val second = OpenCodeQuestionRequest(
            id = "que_2",
            sessionId = "ses2",
            questions = listOf(OpenCodeQuestionInfo(header = null, question = "Second?", options = emptyList())),
        )
        val state = OpenCodeProjectState(
            serverId = "local",
            normalizedDirectory = "/repo",
            questionsBySession = mapOf("ses1" to listOf(first), "ses2" to listOf(second)),
            questionCount = 2,
        )
        val replied = jsonObject(
            """
            {
              "type": "question.replied",
              "properties": { "sessionID": "ses1", "requestID": "que_1", "answers": [["Minimal"]] }
            }
            """,
        )
        val rejected = jsonObject(
            """
            {
              "type": "question.rejected",
              "properties": { "requestID": "que_2" }
            }
            """,
        )

        val afterReply = reducer.reduce(state, replied)
        val afterReject = reducer.reduce(afterReply, rejected)

        assertFalse(afterReply.questionsBySession.containsKey("ses1"))
        assertEquals(1, afterReply.questionCount)
        assertEquals("que_2", afterReply.questionsBySession.getValue("ses2").single().id)
        assertTrue(afterReject.questionsBySession.isEmpty())
        assertEquals(0, afterReject.questionCount)
    }

    @Test
    fun sessionIdleAppendsTurnCompleteNotification() {
        val reducer = OpenCodeEventReducer(nowMillis = { 42L })
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val event = jsonObject(
            """
            {
              "type": "session.idle",
              "properties": { "sessionID": "ses1" }
            }
            """,
        )

        val next = reducer.reduce(state, event)
        val notification = next.notifications.items.single() as TurnCompleteNotification

        assertEquals("/repo", notification.directory)
        assertEquals("ses1", notification.sessionId)
        assertEquals(42L, notification.timeMillis)
        assertFalse(notification.viewed)
    }

    @Test
    fun sessionIdleForActiveSessionIsAlreadyViewed() {
        val reducer = OpenCodeEventReducer(nowMillis = { 42L })
        val state = OpenCodeProjectState(
            serverId = "local",
            normalizedDirectory = "/repo",
            activeSessionId = "ses1",
        )
        val event = jsonObject(
            """
            {
              "type": "session.idle",
              "properties": { "sessionID": "ses1" }
            }
            """,
        )

        val next = reducer.reduce(state, event)
        val notification = next.notifications.items.single() as TurnCompleteNotification

        assertTrue(notification.viewed)
        assertEquals(0, next.notifications.sessionUnseenCount("ses1"))
    }

    @Test
    fun sessionErrorAppendsRedactedErrorNotification() {
        val reducer = OpenCodeEventReducer(nowMillis = { 42L })
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val event = jsonObject(
            """
            {
              "type": "session.error",
              "properties": {
                "sessionID": "ses1",
                "error": "Authorization: Bearer abc123\npassword=secret-value"
              }
            }
            """,
        )

        val next = reducer.reduce(state, event)
        val notification = next.notifications.items.single() as ErrorNotification

        assertEquals("ses1", notification.sessionId)
        assertTrue(notification.summary.contains(Redactor.REDACTED))
        assertFalse(notification.summary.contains("abc123"))
        assertFalse(notification.summary.contains("secret-value"))
        assertTrue(next.notifications.sessionUnseenHasError("ses1"))
    }

    @Test
    fun messageAndToolErrorsAreRedactedBeforeEnteringState() {
        val reducer = OpenCodeEventReducer()
        val state = OpenCodeProjectState(serverId = "local", normalizedDirectory = "/repo")
        val messageEvent = jsonObject(
            """
            {
              "type": "message.updated",
              "properties": {
                "info": {
                  "id": "msg1",
                  "sessionID": "ses1",
                  "role": "assistant",
                  "error": {
                    "message": "Authorization: Bearer message-token",
                    "env": {"OPENAI_API_KEY": "message-key"}
                  }
                }
              }
            }
            """,
        )
        val partEvent = jsonObject(
            """
            {
              "type": "message.part.updated",
              "properties": {
                "part": {
                  "id": "prt1",
                  "messageID": "msg1",
                  "sessionID": "ses1",
                  "type": "tool",
                  "state": {
                    "status": "error",
                    "error": "password=tool-password"
                  }
                }
              }
            }
            """,
        )

        val next = reducer.reduce(reducer.reduce(state, messageEvent), partEvent)
        val messageError = next.messagesBySession.getValue("ses1").single().error.orEmpty()
        val toolError = next.partsByMessage.getValue("msg1").single().toolError.orEmpty()

        assertTrue(messageError.contains(Redactor.REDACTED))
        assertFalse(messageError.contains("message-token"))
        assertFalse(messageError.contains("message-key"))
        assertTrue(toolError.contains(Redactor.REDACTED))
        assertFalse(toolError.contains("tool-password"))
    }

    @Test
    fun sessionNotificationsIgnoreChildSessions() {
        val reducer = OpenCodeEventReducer(nowMillis = { 42L })
        val state = OpenCodeProjectState(
            serverId = "local",
            normalizedDirectory = "/repo",
            sessions = listOf(
                OpenCodeSession(
                    id = "child",
                    title = "child",
                    normalizedDirectory = "/repo",
                    path = null,
                    parentId = "parent",
                    agent = null,
                    modelLabel = null,
                    updatedAt = null,
                    archivedAt = null,
                ),
            ),
        )
        val idleEvent = jsonObject(
            """
            {
              "type": "session.idle",
              "properties": { "sessionID": "child" }
            }
            """,
        )
        val errorEvent = jsonObject(
            """
            {
              "type": "session.error",
              "properties": { "sessionID": "child", "error": "failed" }
            }
            """,
        )

        val next = reducer.reduce(reducer.reduce(state, idleEvent), errorEvent)

        assertTrue(next.notifications.items.isEmpty())
    }

    private fun jsonObject(source: String): JsonObject = json.parseToJsonElement(source).jsonObject
}
