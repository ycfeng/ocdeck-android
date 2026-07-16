package io.github.ycfeng.ocdeck.core.network

import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMessagesResponseReaderTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun decodesBoundedResponseWithUnknownFields() {
        val body = """
            [{
              "info": {
                "id": "message-1",
                "sessionID": "session-1",
                "role": "user",
                "futureInfo": true
              },
              "parts": [{
                "id": "part-1",
                "messageID": "message-1",
                "sessionID": "session-1",
                "type": "text",
                "text": "hello",
                "futurePart": 1
              }],
              "futureEnvelope": {}
            }]
        """.trimIndent().toResponseBody()

        val messages = body.readSessionMessages(json, maxBytes = 4_096L)

        assertEquals("message-1", messages.single().info.id)
        assertEquals("hello", messages.single().parts.single().text)
    }

    @Test
    fun streamsExactLimitAndRejectsTrailingMaxPlusOneByte() {
        val exact = "[]".toResponseBody()
        assertTrue(exact.readSessionMessages(json, maxBytes = 2L).isEmpty())

        val failure = runCatching {
            "[] ".toResponseBody().readSessionMessages(json, maxBytes = 2L)
        }.exceptionOrNull()

        assertTrue(failure is SessionMessagesResponseTooLargeException)
    }
}
