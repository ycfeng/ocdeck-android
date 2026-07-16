package io.github.ycfeng.ocdeck.core.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileContentDtoParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesTextContentAndIgnoresUnknownFields() {
        val content = json.decodeFromString<FileContentDto>(
            """
            {
              "type": "text",
              "content": "fun main() {}",
              "futureField": true
            }
            """.trimIndent(),
        )

        assertEquals("text", content.type)
        assertEquals("fun main() {}", content.content)
        assertNull(content.encoding)
        assertNull(content.mimeType)
    }

    @Test
    fun decodesBinaryImageMetadata() {
        val content = json.decodeFromString<FileContentDto>(
            """
            {
              "type": "binary",
              "content": "iVBORw0KGgo=",
              "encoding": "base64",
              "mimeType": "image/png"
            }
            """.trimIndent(),
        )

        assertEquals("binary", content.type)
        assertEquals("iVBORw0KGgo=", content.content)
        assertEquals("base64", content.encoding)
        assertEquals("image/png", content.mimeType)
    }

    @Test
    fun rejectsContentWithoutRequiredFields() {
        listOf(
            """{"type":"text"}""",
            """{"content":"hello"}""",
        ).forEach { payload ->
            assertTrue(runCatching { json.decodeFromString<FileContentDto>(payload) }.isFailure)
        }
    }

    @Test
    fun rejectsFileEntryWithoutRequiredFields() {
        assertTrue(
            runCatching {
                json.decodeFromString<FileEntryDto>(
                    """{"name":"README.md","path":"README.md","type":"file","ignored":false}""",
                )
            }.isFailure,
        )
    }
}
