package io.github.ycfeng.ocdeck.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFilePathNormalizerTest {
    private val normalizer = ProjectFilePathNormalizer()

    @Test
    fun normalizesSeparatorsAndDotSegments() {
        assertEquals(
            "src/main/App.kt",
            normalizer.normalize("src\\.\\main//App.kt"),
        )
    }

    @Test
    fun preservesFileNameWhitespace() {
        assertEquals(
            "docs/file name .md ",
            normalizer.normalize("docs/file name .md "),
        )
    }

    @Test
    fun preservesBackslashesForPosixProjects() {
        assertEquals(
            "a\\b.txt",
            normalizer.normalize("a\\b.txt", backslashIsSeparator = false),
        )
        assertEquals(
            "a\\b.txt",
            normalizer.name("a\\b.txt", backslashIsSeparator = false),
        )
    }

    @Test
    fun detectsRemotePathSeparatorStyle() {
        assertTrue(normalizer.usesWindowsSeparators("C:/project/test"))
        assertTrue(normalizer.usesWindowsSeparators("\\\\server\\share"))
        assertTrue(!normalizer.usesWindowsSeparators("/mnt/project/test"))
    }

    @Test
    fun allowsEmptyPathOnlyForProjectRoot() {
        assertEquals("", normalizer.normalize("", allowEmpty = true))
        assertFails { normalizer.normalize("") }
    }

    @Test
    fun rejectsAbsoluteAndEscapingPaths() {
        listOf(
            "/etc/passwd",
            "C:\\project\\file.kt",
            "\\\\server\\share\\file.kt",
            "src/../secret.txt",
            "../secret.txt",
            "src/\u0000secret.txt",
        ).forEach { path -> assertFails { normalizer.normalize(path) } }
    }

    @Test
    fun returnsParentAndName() {
        assertEquals("src/main", normalizer.parent("src/main/App.kt"))
        assertEquals("App.kt", normalizer.name("src/main/App.kt"))
        assertEquals("", normalizer.parent("README.md"))
    }

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }
}
