package io.github.ycfeng.ocdeck.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathNormalizerTest {
    private val normalizer = PathNormalizer()

    @Test
    fun convertsWindowsBackslashesToForwardSlashes() {
        assertEquals(
            "X:/workspace/sample-project",
            normalizer.normalize("X:\\workspace\\sample-project"),
        )
    }

    @Test
    fun removesTrailingSlashExceptRoots() {
        assertEquals("X:/workspace/sample-project", normalizer.normalize("X:/workspace/sample-project/"))
        assertEquals("/", normalizer.normalize("/"))
        assertEquals("C:/", normalizer.normalize("c:/"))
    }

    @Test
    fun comparesWindowsDrivePathsCaseInsensitively() {
        assertTrue(normalizer.areSame("X:/Workspace/Sample-Project", "x:/workspace/sample-project/"))
    }

    @Test
    fun dedupesRecentProjectsByNormalizedPath() {
        val result = normalizer.dedupe(
            listOf(
                "X:\\workspace\\sample-project",
                "x:/workspace/sample-project/",
                "/",
            ),
        )

        assertEquals(listOf("X:/workspace/sample-project", "/"), result)
    }
}
