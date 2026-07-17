package io.github.ycfeng.ocdeck.ui.component

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.data.settings.AppLanguagePreference
import io.github.ycfeng.ocdeck.data.settings.localized
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizedWindowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun popupUsesParentLocaleAndUpdatesWhileOpen() {
        val initialPreference = preferenceOppositeToActivity()
        val updatedPreference = initialPreference.oppositeExplicitPreference()
        val initialText = localizedText(initialPreference, R.string.composer_search_model)
        val updatedText = localizedText(updatedPreference, R.string.composer_search_model)
        var preference by mutableStateOf(initialPreference)

        composeRule.setContent {
            val localizedContext = remember(preference) {
                composeRule.activity.localized(preference)
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides Configuration(localizedContext.resources.configuration),
            ) {
                Box {
                    LocalizedPopup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset.Zero,
                        onDismissRequest = {},
                        properties = PopupProperties(focusable = true),
                    ) {
                        Text(stringResource(R.string.composer_search_model))
                    }
                }
            }
        }

        composeRule.onNodeWithText(initialText).assertIsDisplayed()
        composeRule.runOnIdle { preference = updatedPreference }
        composeRule.onNodeWithText(updatedText).assertIsDisplayed()
    }

    @Test
    fun modalBottomSheetUsesParentLocale() {
        val preference = preferenceOppositeToActivity()
        val expectedText = localizedText(preference, R.string.attachment_title)
        assertNotEquals(composeRule.activity.getString(R.string.attachment_title), expectedText)

        composeRule.setContent {
            val localizedContext = remember {
                composeRule.activity.localized(preference)
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides Configuration(localizedContext.resources.configuration),
            ) {
                LocalizedModalBottomSheet(onDismissRequest = {}) {
                    Text(stringResource(R.string.attachment_title))
                }
            }
        }

        composeRule.onNodeWithText(expectedText).assertIsDisplayed()
    }

    private fun preferenceOppositeToActivity(): AppLanguagePreference {
        val activityText = composeRule.activity.getString(R.string.composer_search_model)
        val englishText = localizedText(AppLanguagePreference.English, R.string.composer_search_model)
        return if (activityText != englishText) {
            AppLanguagePreference.English
        } else {
            AppLanguagePreference.SimplifiedChinese
        }
    }

    private fun AppLanguagePreference.oppositeExplicitPreference(): AppLanguagePreference = when (this) {
        AppLanguagePreference.English -> AppLanguagePreference.SimplifiedChinese
        AppLanguagePreference.SimplifiedChinese -> AppLanguagePreference.English
        AppLanguagePreference.System -> error("Expected an explicit language preference")
    }

    private fun localizedText(
        preference: AppLanguagePreference,
        @StringRes resourceId: Int,
    ): String = composeRule.activity.localized(preference).getString(resourceId)
}
