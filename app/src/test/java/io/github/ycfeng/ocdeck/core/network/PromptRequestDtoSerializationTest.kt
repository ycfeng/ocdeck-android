package io.github.ycfeng.ocdeck.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class PromptRequestDtoSerializationTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun serializesPromptRequestLikeWebClient() {
        val encoded = json.encodeToString(
            PromptRequestDto(
                messageID = "msg_fixture_001",
                parts = listOf(
                    PromptPartDto(
                        id = "prt_fixture_001",
                        type = "text",
                        text = "hi",
                    ),
                ),
                agent = "build",
                model = PromptModelDto(
                    providerID = "provider-alpha",
                    modelID = "model-fast",
                ),
                variant = "medium",
            ),
        )
        val root = json.parseToJsonElement(encoded).jsonObject
        val part = root.getValue("parts").jsonArray.first().jsonObject
        val model = root.getValue("model").jsonObject

        assertTrue(root.containsKey("messageID"))
        assertTrue(part.containsKey("id"))
        assertTrue(root.containsKey("variant"))
        assertFalse(model.containsKey("variant"))
    }

    @Test
    fun serializesFilePartsWithoutNullText() {
        val encoded = json.encodeToString(
            PromptRequestDto(
                messageID = "msg_fixture_001",
                parts = listOf(
                    PromptPartDto(
                        id = "prt_fixture_001",
                        type = "text",
                        text = "",
                    ),
                    PromptPartDto(
                        id = "prt_fixture_002",
                        type = "file",
                        mime = "text/plain",
                        url = "data:text/plain;base64,aGk=",
                        filename = "note.txt",
                    ),
                ),
            ),
        )
        val part = json.parseToJsonElement(encoded)
            .jsonObject
            .getValue("parts")
            .jsonArray[1]
            .jsonObject

        assertEquals("file", part.getValue("type").jsonPrimitive.content)
        assertEquals("text/plain", part.getValue("mime").jsonPrimitive.content)
        assertEquals("data:text/plain;base64,aGk=", part.getValue("url").jsonPrimitive.content)
        assertEquals("note.txt", part.getValue("filename").jsonPrimitive.content)
        assertFalse(part.containsKey("text"))
    }

    @Test
    fun serializesProjectFileContextWithoutNullText() {
        val encoded = json.encodeToString(
            PromptRequestDto(
                messageID = "msg_fixture_001",
                parts = listOf(
                    PromptPartDto(
                        id = "prt_context_001",
                        type = "file",
                        mime = "text/plain",
                        url = "file:///E:/repo/src/Main.kt",
                        filename = "Main.kt",
                    ),
                ),
            ),
        )
        val part = json.parseToJsonElement(encoded)
            .jsonObject
            .getValue("parts")
            .jsonArray
            .single()
            .jsonObject

        assertEquals("file:///E:/repo/src/Main.kt", part.getValue("url").jsonPrimitive.content)
        assertEquals("Main.kt", part.getValue("filename").jsonPrimitive.content)
        assertFalse(part.containsKey("text"))
    }

    @Test
    fun serializesRevertRequestWithoutNullPartId() {
        val encoded = json.encodeToString(RevertSessionRequestDto(messageID = "msg_fixture_001"))
        val root = json.parseToJsonElement(encoded).jsonObject

        assertEquals("msg_fixture_001", root.getValue("messageID").jsonPrimitive.content)
        assertFalse(root.containsKey("partID"))
    }
}
