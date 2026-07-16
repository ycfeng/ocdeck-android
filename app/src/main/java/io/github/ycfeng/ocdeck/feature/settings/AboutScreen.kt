package io.github.ycfeng.ocdeck.feature.settings

import android.content.res.AssetManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ycfeng.ocdeck.BuildConfig
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.ui.component.OpenCodeCard
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSectionLabel
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AboutScreen(
    onBack: () -> Unit,
) {
    val assetManager = LocalContext.current.assets
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val linkOpenFailedMessage = stringResource(R.string.about_link_open_failed)
    val projectUrl = BuildConfig.PROJECT_URL
    val upstreamUrl = stringResource(R.string.about_upstream_url)
    val legalDocuments by produceState<List<LegalDocumentContent>?>(null, assetManager) {
        value = loadLegalDocuments(assetManager)
    }

    fun openUrl(url: String) {
        try {
            uriHandler.openUri(url)
        } catch (_: Exception) {
            scope.launch {
                snackbarHostState.showSnackbar(linkOpenFailedMessage)
            }
        }
    }

    Scaffold(
        containerColor = OpenCodePalette.Canvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsHeader(
                    title = stringResource(R.string.about_title),
                    subtitle = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    onBack = onBack,
                )
            }
            item {
                OpenCodeCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = OpenCodePalette.AccentSoft,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            color = OpenCodePalette.Text,
                        )
                        Text(
                            text = stringResource(R.string.about_app_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenCodePalette.MutedText,
                        )
                        Text(
                            text = stringResource(R.string.about_independence_statement),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenCodePalette.MutedText,
                        )
                    }
                }
            }
            item { OpenCodeSectionLabel(stringResource(R.string.about_links_section)) }
            if (projectUrl.isNotEmpty()) {
                item {
                    SettingsActionCard(
                        title = stringResource(R.string.about_project_title),
                        subtitle = projectUrl,
                        action = stringResource(R.string.action_open),
                        onClick = { openUrl(projectUrl) },
                    )
                }
            }
            item {
                SettingsActionCard(
                    title = stringResource(R.string.about_upstream_title),
                    subtitle = upstreamUrl,
                    action = stringResource(R.string.action_open),
                    onClick = { openUrl(UpstreamUrl) },
                )
            }
            item { OpenCodeSectionLabel(stringResource(R.string.about_legal_section)) }
            item {
                OpenCodeCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.about_legal_summary),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenCodePalette.MutedText,
                    )
                }
            }
            val documents = legalDocuments
            if (documents == null) {
                item {
                    Text(
                        text = stringResource(R.string.about_legal_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenCodePalette.MutedText,
                    )
                }
            } else {
                documents.forEach { document ->
                    item(key = document.assetPath) {
                        LegalDocumentCard(document)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegalDocumentCard(document: LegalDocumentContent) {
    OpenCodeCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(document.titleResId),
                style = MaterialTheme.typography.titleMedium,
                color = OpenCodePalette.Text,
            )
            SelectionContainer {
                Text(
                    text = document.text ?: stringResource(R.string.about_legal_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (document.text == null) OpenCodePalette.Danger else OpenCodePalette.MutedText,
                )
            }
        }
    }
}

private suspend fun loadLegalDocuments(assetManager: AssetManager): List<LegalDocumentContent> =
    withContext(Dispatchers.IO) {
        LegalAssets.map { asset ->
            val text = try {
                assetManager.open(asset.assetPath).use { input ->
                    InputStreamReader(input, Charsets.UTF_8).use { reader -> reader.readText() }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
            LegalDocumentContent(
                assetPath = asset.assetPath,
                titleResId = asset.titleResId,
                text = text,
            )
        }
    }

private data class LegalAsset(
    val assetPath: String,
    @StringRes val titleResId: Int,
)

private data class LegalDocumentContent(
    val assetPath: String,
    @StringRes val titleResId: Int,
    val text: String?,
) {
    override fun toString(): String =
        "LegalDocumentContent(assetPathPresent=${assetPath.isNotEmpty()}, titleResId=$titleResId, " +
            "textLength=${text?.length})"
}

private val LegalAssets = listOf(
    LegalAsset("legal/LICENSE.txt", R.string.about_license_title),
    LegalAsset("legal/NOTICE.txt", R.string.about_notice_title),
    LegalAsset("legal/THIRD_PARTY_NOTICES.txt", R.string.about_third_party_title),
    LegalAsset("legal/THIRD_PARTY_LICENSES.txt", R.string.about_third_party_licenses_title),
    LegalAsset("legal/TRADEMARKS.md", R.string.about_trademarks_title),
)

private const val UpstreamUrl = "https://github.com/anomalyco/opencode"
