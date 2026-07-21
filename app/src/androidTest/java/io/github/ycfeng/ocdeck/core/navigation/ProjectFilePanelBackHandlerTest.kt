package io.github.ycfeng.ocdeck.core.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ycfeng.ocdeck.feature.file.ProjectFileBrowserPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectFilePanelBackHandlerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backConsumesPreviewAndPanelBeforeUnderlyingContent() {
        var isOpen by mutableStateOf(true)
        var page by mutableStateOf(ProjectFileBrowserPage.Content)
        var underlyingBackCount = 0

        composeRule.setContent {
            BackHandler { underlyingBackCount += 1 }
            ProjectFilePanelBackHandler(
                isOpen = isOpen,
                currentPage = { page },
                onShowTree = { page = ProjectFileBrowserPage.Tree },
                onClose = { isOpen = false },
            )
        }

        pressBack()
        assertEquals(ProjectFileBrowserPage.Tree, page)
        assertTrue(isOpen)
        assertEquals(0, underlyingBackCount)

        pressBack()
        assertEquals(ProjectFileBrowserPage.Tree, page)
        assertFalse(isOpen)
        assertEquals(0, underlyingBackCount)

        pressBack()
        assertEquals(1, underlyingBackCount)
    }

    private fun pressBack() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }
}
