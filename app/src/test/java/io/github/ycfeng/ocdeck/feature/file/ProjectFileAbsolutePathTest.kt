package io.github.ycfeng.ocdeck.feature.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectFileAbsolutePathTest {
    @Test
    fun joinsPosixProjectPaths() {
        assertEquals(
            "/repo/src/Main.kt",
            buildProjectFileAbsolutePath("/repo/", "src/./Main.kt"),
        )
        assertEquals(
            "/src/Main.kt",
            buildProjectFileAbsolutePath("/", "src/Main.kt"),
        )
    }

    @Test
    fun joinsWindowsProjectPathsWithForwardSlashes() {
        assertEquals(
            "E:/Repo/src/Main.kt",
            buildProjectFileAbsolutePath("E:\\Repo\\", "src\\Main.kt"),
        )
        assertEquals(
            "E:/src/Main.kt",
            buildProjectFileAbsolutePath("e:/", "src/Main.kt"),
        )
    }

    @Test
    fun joinsUncProjectPathsWithForwardSlashes() {
        assertEquals(
            "//server/share/repo/src/Main.kt",
            buildProjectFileAbsolutePath("\\\\server\\share\\repo\\", "src\\Main.kt"),
        )
    }

    @Test
    fun preservesLiteralBackslashesInPosixFileNames() {
        assertEquals(
            "/repo/src/name\\literal.kt",
            buildProjectFileAbsolutePath("/repo", "src/name\\literal.kt"),
        )
    }

    @Test
    fun rejectsInvalidRootsAndRelativePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            buildProjectFileAbsolutePath("relative/project", "src/Main.kt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildProjectFileAbsolutePath("/repo", "../secret.txt")
        }
    }
}
