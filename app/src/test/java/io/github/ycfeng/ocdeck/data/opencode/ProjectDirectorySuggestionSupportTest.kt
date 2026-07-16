package io.github.ycfeng.ocdeck.data.opencode

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectDirectorySuggestionSupportTest {
    private val pathNormalizer = PathNormalizer()

    @Test
    fun buildsBrowseRequestForWindowsPartialPath() {
        val request = buildDirectoryBrowseRequest("X:\\workspace\\sample-project\\s", pathNormalizer)

        assertEquals(DirectoryBrowseRequest("X:/workspace/sample-project", "s"), request)
    }

    @Test
    fun preservesWindowsDriveRootForFileApi() {
        val request = buildDirectoryBrowseRequest("x:/", pathNormalizer)

        assertEquals(DirectoryBrowseRequest("X:/", ""), request)
    }

    @Test
    fun browsesDirectoryWhenInputEndsWithSlash() {
        val request = buildDirectoryBrowseRequest("X:/workspace/sample-project/", pathNormalizer)

        assertEquals(DirectoryBrowseRequest("X:/workspace/sample-project", ""), request)
    }

    @Test
    fun buildsBrowseRequestForPosixPath() {
        val request = buildDirectoryBrowseRequest("/workspace/sample-project/src", pathNormalizer)

        assertEquals(DirectoryBrowseRequest("/workspace/sample-project", "src"), request)
    }

    @Test
    fun returnsNullForKeywordSearchInput() {
        assertNull(buildDirectoryBrowseRequest("test", pathNormalizer))
    }

    @Test
    fun joinsWindowsRootWithoutDroppingSeparator() {
        assertEquals("X:/workspace", joinDirectory("X:/", "workspace", pathNormalizer))
    }
}
