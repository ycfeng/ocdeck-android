package io.github.ycfeng.ocdeck.feature.session

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.ui.theme.OpenCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionMessageMetaTextTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun ellipsizedMetadataOpensAndClosesFullTextPopup() {
        val metadata = "Build · GPT-5.6 Sol Fast · 10:24"
        setMetadataContent(metadata = metadata, widthDp = 120)

        composeRule.waitUntil {
            composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size == 1
        }
        val collapsed = composeRule.activity.getString(R.string.status_collapsed)
        composeRule.onNode(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed))
            .performClick()

        composeRule.onAllNodesWithText(metadata).assertCountEquals(2)

        val expanded = composeRule.activity.getString(R.string.status_expanded)
        composeRule.onNode(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expanded))
            .performClick()
        composeRule.onAllNodesWithText(metadata).assertCountEquals(1)
    }

    @Test
    fun metadataWithoutOverflowHasNoClickAction() {
        val metadata = "Build · 10:24"
        setMetadataContent(metadata = metadata, widthDp = 320)

        composeRule.waitForIdle()

        composeRule.onNode(hasClickAction()).assertDoesNotExist()
        composeRule.onAllNodesWithText(metadata).assertCountEquals(1)
    }

    private fun setMetadataContent(metadata: String, widthDp: Int) {
        composeRule.setContent {
            OpenCodeTheme {
                Box(modifier = Modifier.width(widthDp.dp)) {
                    MessageMetaText(
                        text = metadata,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
