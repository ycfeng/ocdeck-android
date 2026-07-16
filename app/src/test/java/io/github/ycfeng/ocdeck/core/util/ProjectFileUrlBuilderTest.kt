package io.github.ycfeng.ocdeck.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectFileUrlBuilderTest {
    private val builder = ProjectFileUrlBuilder()

    @Test
    fun buildsAndRestoresPosixFileUrls() {
        val url = builder.build("/repo/", "src/./Main.kt")

        assertEquals("file:///repo/src/Main.kt", url)
        assertEquals("src/Main.kt", builder.relativePath("/repo", url))
    }

    @Test
    fun encodesEachUtf8PathSegmentWithoutFormEncoding() {
        val url = builder.build(
            "E:\\Project Root\\工作",
            "src\\A 100%?#.kt",
        )

        assertEquals(
            "file:///E:/Project%20Root/%E5%B7%A5%E4%BD%9C/src/A%20100%25%3F%23.kt",
            url,
        )
        assertEquals(
            "src/A 100%?#.kt",
            builder.relativePath("e:/project root/工作", url),
        )
    }

    @Test
    fun preservesLiteralBackslashesForPosixProjectFiles() {
        val relativePath = "src/name\\literal.kt"
        val url = builder.build("/repo", relativePath)

        assertEquals("file:///repo/src/name%5Cliteral.kt", url)
        assertEquals(relativePath, builder.relativePath("/repo", url))
    }

    @Test
    fun supportsUncProjects() {
        val url = builder.build("\\\\server\\share\\repo", "src\\Main.kt")

        assertEquals("file://server/share/repo/src/Main.kt", url)
        assertEquals("src/Main.kt", builder.relativePath("//SERVER/share/repo", url))
    }

    @Test
    fun restoresWindowsPathsCaseInsensitively() {
        assertEquals(
            "SRC/Main.kt",
            builder.relativePath("E:/Repo", "file:///e:/repo/SRC/Main.kt"),
        )
    }

    @Test
    fun rejectsUrlsOutsideTheProjectOrWithNonContextComponents() {
        assertNull(builder.relativePath("/repo", "file:///repo-other/Main.kt"))
        assertNull(builder.relativePath("/repo", "file:///repo/Main.kt?start=1&end=2"))
        assertNull(builder.relativePath("/repo", "file:///repo/Main.kt#fragment"))
        assertNull(builder.relativePath("/repo", "https://example.test/repo/Main.kt"))
    }

    @Test
    fun rejectsInvalidProjectOrRelativePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            builder.build("relative/project", "Main.kt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            builder.build("/repo", "../secret.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            builder.build("E:/repo", "C:\\secret.txt")
        }
    }
}
