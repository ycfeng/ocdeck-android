package io.github.ycfeng.ocdeck.feature.project

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectPickerCompletionTest {
    private val pathNormalizer = PathNormalizer()

    @Test
    fun appendsSlashToPosixDirectorySuggestion() {
        assertEquals(
            "/workspace/sample-project/",
            completeDirectorySuggestion("/workspace/sample-project", pathNormalizer),
        )
    }

    @Test
    fun normalizesWindowsDirectoryBeforeAppendingSlash() {
        assertEquals(
            "X:/workspace/sample-project/",
            completeDirectorySuggestion("X:\\workspace\\sample-project", pathNormalizer),
        )
    }

    @Test
    fun doesNotDuplicateRootSlash() {
        assertEquals("/", completeDirectorySuggestion("/", pathNormalizer))
        assertEquals("X:/", completeDirectorySuggestion("x:/", pathNormalizer))
    }
}
