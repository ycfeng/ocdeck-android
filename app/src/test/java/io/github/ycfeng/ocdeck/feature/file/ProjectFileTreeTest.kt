package io.github.ycfeng.ocdeck.feature.file

import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileEntry
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileType
import io.github.ycfeng.ocdeck.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFileTreeTest {
    @Test
    fun flattensExpandedDirectoriesInDisplayOrder() {
        val rows = flattenProjectFileTree(
            directories = mapOf(
                "" to loaded(directory("src"), file("README.md")),
                "src" to loaded(file("src/Main.kt")),
            ),
            expandedDirectories = setOf("src"),
        )

        assertEquals(
            listOf("src", "src/Main.kt", "README.md"),
            rows.filterIsInstance<ProjectFileTreeRow.Entry>().map { it.file.path },
        )
        assertEquals(listOf(0, 1, 0), rows.filterIsInstance<ProjectFileTreeRow.Entry>().map { it.depth })
    }

    @Test
    fun emitsLoadingErrorAndEmptyRows() {
        val error = UiText.Resource(R.string.file_browser_directory_failed)

        assertTrue(
            flattenProjectFileTree(
                directories = mapOf("" to ProjectFileDirectoryState(isLoading = true)),
                expandedDirectories = emptySet(),
            ).single() is ProjectFileTreeRow.Loading,
        )
        assertEquals(
            error,
            (flattenProjectFileTree(
                directories = mapOf("" to ProjectFileDirectoryState(isLoaded = true, error = error)),
                expandedDirectories = emptySet(),
            ).single() as ProjectFileTreeRow.Error).message,
        )
        assertTrue(
            flattenProjectFileTree(
                directories = mapOf("" to loaded()),
                expandedDirectories = emptySet(),
            ).single() is ProjectFileTreeRow.Empty,
        )
    }

    @Test
    fun stopsAtConfiguredDepth() {
        val rows = flattenProjectFileTree(
            directories = mapOf(
                "" to loaded(directory("a")),
                "a" to loaded(directory("a/b")),
                "a/b" to loaded(directory("a")),
            ),
            expandedDirectories = setOf("a", "a/b"),
            maxDepth = 1,
        )

        assertEquals(
            listOf("a", "a/b"),
            rows.filterIsInstance<ProjectFileTreeRow.Entry>().map { it.file.path },
        )
    }

    @Test
    fun protectsAgainstCyclesAndDuplicatePaths() {
        val rows = flattenProjectFileTree(
            directories = mapOf(
                "" to loaded(directory("a"), file("README.md"), file("README.md")),
                "a" to loaded(directory("a/b")),
                "a/b" to loaded(directory("a")),
            ),
            expandedDirectories = setOf("a", "a/b"),
        )

        assertEquals(
            listOf("a", "a/b", "README.md"),
            rows.filterIsInstance<ProjectFileTreeRow.Entry>().map { it.file.path },
        )
    }

    private fun loaded(vararg entries: OpenCodeFileEntry) = ProjectFileDirectoryState(
        entries = entries.toList(),
        isLoaded = true,
    )

    private fun directory(path: String) = OpenCodeFileEntry(
        name = path.substringAfterLast('/'),
        path = path,
        type = OpenCodeFileType.Directory,
        ignored = false,
    )

    private fun file(path: String) = OpenCodeFileEntry(
        name = path.substringAfterLast('/'),
        path = path,
        type = OpenCodeFileType.File,
        ignored = false,
    )
}
