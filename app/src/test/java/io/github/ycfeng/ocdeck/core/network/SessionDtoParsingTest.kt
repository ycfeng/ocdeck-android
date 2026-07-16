package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommentOrigin
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionDtoParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesCostAndTokens() {
        val session = json.decodeFromString<SessionDto>(
            """
            {
              "id": "ses_fixture_001",
              "title": "Fixture session",
              "directory": "X:\\workspace\\sample-project",
              "cost": 0,
              "tokens": {
                "input": 400,
                "output": 20,
                "reasoning": 80,
                "cache": {
                  "read": 1000,
                  "write": 0
                }
              },
              "model": {
                "id": "model-fast",
                "providerID": "provider-alpha",
                "variant": "medium"
              }
            }
            """.trimIndent(),
        )

        assertEquals(0.0, session.cost ?: -1.0, 0.0)
        assertEquals(400L, session.tokens?.input)
        assertEquals(20L, session.tokens?.output)
        assertEquals(80L, session.tokens?.reasoning)
        assertEquals(1_000L, session.tokens?.cache?.read)
        assertEquals(0L, session.tokens?.cache?.write)
    }

    @Test
    fun decodesSessionRevertMarker() {
        val session = json.decodeFromString<SessionDto>(
            """
            {
              "id": "ses_fixture_001",
              "title": "Reverted session",
              "directory": "X:\\workspace\\sample-project",
              "revert": {
                "messageID": "msg_fixture_001",
                "partID": "prt_fixture_001"
              }
            }
            """.trimIndent(),
        )

        assertEquals("msg_fixture_001", session.revert?.messageID)
        assertEquals("prt_fixture_001", session.revert?.partID)
    }

    @Test
    fun decodesMessageInfoTokens() {
        val message = json.decodeFromString<MessageInfoDto>(
            """
            {
              "id": "msg_fixture_001",
              "sessionID": "ses_fixture_001",
              "role": "assistant",
              "tokens": {
                "input": 1000,
                "output": 200,
                "reasoning": 100,
                "cache": {
                  "read": 700,
                  "write": 0
                }
              },
              "model": {
                "providerID": "provider-alpha",
                "modelID": "model-fast",
                "variant": "xhigh"
              }
            }
            """.trimIndent(),
        )

        assertEquals(1_000L, message.tokens?.input)
        assertEquals(200L, message.tokens?.output)
        assertEquals(100L, message.tokens?.reasoning)
        assertEquals(700L, message.tokens?.cache?.read)
        assertEquals(0L, message.tokens?.cache?.write)
    }

    @Test
    fun decodesHistoryImageAttachmentFields() {
        val message = json.decodeFromString<MessageWithPartsDto>(
            """
            {
              "info": {
                "id": "msg_fixture_001",
                "sessionID": "ses_fixture_001",
                "role": "user"
              },
              "parts": [
                {
                  "id": "prt_fixture_001",
                  "messageID": "msg_fixture_001",
                  "sessionID": "ses_fixture_001",
                  "type": "file",
                  "filename": "screenshot.png",
                  "mime": "image/png",
                  "url": "data:image/png;base64,AA==",
                  "futureImageField": { "width": 1280 }
                }
              ]
            }
            """.trimIndent(),
        )

        val part = message.parts.single()

        assertEquals("file", part.type)
        assertEquals("screenshot.png", part.filename)
        assertEquals("image/png", part.mime)
        assertEquals("data:image/png;base64,AA==", part.url)
    }

    @Test
    fun decodesMessagePartCommentMetadata() {
        val part = json.decodeFromString<MessagePartDto>(
            """
            {
              "id": "prt_fixture_001",
              "messageID": "msg_fixture_001",
              "sessionID": "ses_fixture_001",
              "type": "text",
              "text": "synthetic note",
              "synthetic": true,
              "metadata": {
                "opencodeComment": {
                  "path": "src/Main.kt",
                  "selection": {
                    "startLine": 12,
                    "startChar": 1,
                    "endLine": 14,
                    "endChar": 5
                  },
                  "comment": "Keep the existing branch",
                  "preview": "when (value) {",
                  "origin": "review"
                }
              }
            }
            """.trimIndent(),
        )

        val comment = part.metadata.toOpenCodeMessageComment()!!

        assertEquals("src/Main.kt", comment.path)
        assertEquals(12, comment.selection?.startLine)
        assertEquals(1, comment.selection?.startChar)
        assertEquals(14, comment.selection?.endLine)
        assertEquals(5, comment.selection?.endChar)
        assertEquals("Keep the existing branch", comment.comment)
        assertEquals("when (value) {", comment.preview)
        assertEquals(OpenCodeCommentOrigin.Review, comment.origin)
    }

    @Test
    fun invalidSelectionDoesNotDiscardValidCommentMetadata() {
        val part = json.decodeFromString<MessagePartDto>(
            """
            {
              "id": "prt_fixture_001",
              "messageID": "msg_fixture_001",
              "sessionID": "ses_fixture_001",
              "type": "text",
              "metadata": {
                "opencodeComment": {
                  "path": "README.md",
                  "selection": { "startLine": "invalid" },
                  "comment": "File-level note"
                }
              }
            }
            """.trimIndent(),
        )

        val comment = part.metadata.toOpenCodeMessageComment()!!

        assertEquals("README.md", comment.path)
        assertEquals("File-level note", comment.comment)
        assertNull(comment.selection)
    }

    @Test
    fun malformedCommentMetadataIsIgnored() {
        val part = json.decodeFromString<MessagePartDto>(
            """
            {
              "id": "prt_fixture_001",
              "messageID": "msg_fixture_001",
              "sessionID": "ses_fixture_001",
              "type": "text",
              "metadata": {
                "opencodeComment": {
                  "path": 42,
                  "comment": ["invalid"]
                }
              }
            }
            """.trimIndent(),
        )

        assertNull(part.metadata.toOpenCodeMessageComment())
    }
}
