package io.github.ycfeng.ocdeck.feature.file

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileEntry
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileType
import io.github.ycfeng.ocdeck.ui.theme.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectFilePanelCopyMenuTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fileLongPressCopiesEachValueWithoutChangingSelection() {
        val file = OpenCodeFileEntry(
            name = "Main.kt",
            path = "src/Main.kt",
            type = OpenCodeFileType.File,
            ignored = false,
        )
        var selectionToggleCount = 0
        setPanelContent(file) { selectionToggleCount += 1 }

        val rowDescription = composeRule.activity.getString(
            R.string.project_file_picker_toggle_file,
            file.name,
        )
        assertMenuCopy(rowDescription, R.string.file_browser_copy_file_name, "Main.kt")
        assertMenuCopy(rowDescription, R.string.file_browser_copy_relative_path, "src/Main.kt")
        assertMenuCopy(rowDescription, R.string.file_browser_copy_absolute_path, "E:/repo/src/Main.kt")
        assertEquals(0, selectionToggleCount)
    }

    @Test
    fun directoryLongPressUsesDirectoryNameAction() {
        val directory = OpenCodeFileEntry(
            name = "src",
            path = "src",
            type = OpenCodeFileType.Directory,
            ignored = false,
        )
        var directoryToggleCount = 0
        setPanelContent(directory) { directoryToggleCount += 1 }

        val rowDescription = composeRule.activity.getString(
            R.string.file_browser_expand_directory,
            directory.name,
        )
        assertMenuCopy(rowDescription, R.string.file_browser_copy_directory_name, "src")
        assertEquals(0, directoryToggleCount)
    }

    private fun setPanelContent(
        entry: OpenCodeFileEntry,
        onRowClick: () -> Unit,
    ) {
        composeRule.setContent {
            OpenCodeTheme {
                ProjectFilePanel(
                    state = ProjectFileBrowserUiState(
                        directories = mapOf(
                            "" to ProjectFileDirectoryState(
                                entries = listOf(entry),
                                isLoaded = true,
                            ),
                        ),
                    ),
                    projectDirectory = "E:/repo",
                    onSearchQueryChanged = {},
                    onToggleDirectory = { onRowClick() },
                    onRetryDirectory = {},
                    onOpenFile = { onRowClick() },
                    onShowTree = {},
                    onRefreshTree = {},
                    onRefreshFile = {},
                    onClose = {},
                    mode = ProjectFilePanelMode.Pick,
                    selectionLimit = 10,
                    onToggleFileSelection = { onRowClick() },
                )
            }
        }
    }

    private fun assertMenuCopy(
        rowDescription: String,
        menuLabelRes: Int,
        expectedClipboardText: String,
    ) {
        composeRule.onNodeWithContentDescription(rowDescription)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        val menuLabel = composeRule.activity.getString(menuLabelRes)
        composeRule.onNodeWithText(menuLabel).assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        val clipboard = composeRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(expectedClipboardText, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
