package io.github.ycfeng.ocdeck.data.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewDiffParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun extractDiffFilesSupportsArrayPayload() {
        val payload = json.parseToJsonElement(
            """
            [
              {
                "file": "src/Main.kt",
                "status": "modified",
                "additions": 3,
                "deletions": 1,
                "patch": "@@ -1 +1 @@"
              },
              {
                "file": "README.md",
                "oldFile": "README.old.md",
                "status": "renamed",
                "added": 2,
                "removed": 0,
                "diff": "diff --git"
              }
            ]
            """.trimIndent(),
        )

        val diffs = payload.extractDiffFiles()

        assertEquals(listOf("src/Main.kt", "README.md"), diffs.map { it.file })
        assertEquals("modified", diffs.first().status)
        assertEquals(3, diffs.first().additions)
        assertEquals(1, diffs.first().deletions)
        assertEquals("README.old.md", diffs.last().oldFile)
        assertEquals(2, diffs.last().additions)
        assertEquals("diff --git", diffs.last().patch)
    }

    @Test
    fun extractDiffFilesSupportsNestedPayload() {
        val payload = json.parseToJsonElement(
            """
            {
              "data": {
                "diffs": [
                  {
                    "path": "app/src/App.kt",
                    "type": "added",
                    "additions": "4",
                    "deletions": "0"
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        val diff = payload.extractDiffFiles().single()

        assertEquals("app/src/App.kt", diff.file)
        assertEquals("added", diff.status)
        assertEquals(4, diff.additions)
        assertEquals(0, diff.deletions)
    }

    @Test
    fun extractDiffFilesReturnsEmptyForEmptyPayload() {
        val payload = json.parseToJsonElement("[]")

        assertTrue(payload.extractDiffFiles().isEmpty())
    }
}
