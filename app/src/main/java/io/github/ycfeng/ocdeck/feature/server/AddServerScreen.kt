package io.github.ycfeng.ocdeck.feature.server

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.security.SshPrivateKeyReader
import io.github.ycfeng.ocdeck.core.security.SshPrivateKeyTooLargeException
import io.github.ycfeng.ocdeck.data.server.FrpcWireProtocol
import io.github.ycfeng.ocdeck.data.server.SshAuthMethod
import io.github.ycfeng.ocdeck.data.server.SshHostKeyPolicy
import io.github.ycfeng.ocdeck.ui.component.OpenCodeCard
import io.github.ycfeng.ocdeck.ui.component.OpenCodeConfirmDialog
import io.github.ycfeng.ocdeck.ui.component.OpenCodePrimaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSecondaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSectionLabel
import io.github.ycfeng.ocdeck.ui.component.OpenCodeTopBar
import io.github.ycfeng.ocdeck.ui.component.openCodeOutlinedTextFieldColors
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import io.github.ycfeng.ocdeck.ui.text.asString
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AddServerScreen(
    viewModel: AddServerViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenTitle = if (state.isEditMode) stringResource(R.string.server_edit_title) else stringResource(R.string.server_add_title)
    val sensitiveSuffix = if (state.isEditMode) stringResource(R.string.server_sensitive_suffix) else ""
    val privateKeyFileDefault = stringResource(R.string.server_private_key_file_default)
    val privateKeyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.onPrivateKeyFileReadStarted()
        scope.launch {
            try {
                val metadata = queryPrivateKeyDocumentMetadata(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    defaultFileName = privateKeyFileDefault,
                )
                val content = SshPrivateKeyReader.readUtf8(metadata.sizeBytes) {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to open SSH private key document")
                }
                if (content.isBlank()) {
                    viewModel.onPrivateKeyFileReadFailed()
                } else {
                    viewModel.onPrivateKeyFileSelected(metadata.fileName, content)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SshPrivateKeyTooLargeException) {
                viewModel.onPrivateKeyFileTooLarge()
            } catch (_: Exception) {
                viewModel.onPrivateKeyFileReadFailed()
            }
        }
    }

    LaunchedEffect(state.savedServerId) {
        state.savedServerId?.let(onSaved)
    }

    if (state.showCleartextHttpCredentialsWarning) {
        OpenCodeConfirmDialog(
            title = stringResource(R.string.server_direct_http_credentials_warning_title),
            message = stringResource(R.string.server_direct_http_credentials_warning_message),
            confirmText = stringResource(R.string.server_direct_http_credentials_warning_confirm),
            isConfirming = state.isSaving,
            onDismiss = viewModel::dismissCleartextHttpWarning,
            onConfirm = viewModel::confirmCleartextHttpSave,
        )
    }

    Scaffold(
        containerColor = OpenCodePalette.Canvas,
        topBar = {
            OpenCodeTopBar(
                navigationLabel = stringResource(R.string.action_back),
                navigationIconRes = R.drawable.ic_opencode_arrow_left,
                onNavigationClick = onBack,
                title = screenTitle,
                actions = {
                    Spacer(Modifier.width(48.dp))
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 17.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(screenTitle, style = MaterialTheme.typography.headlineMedium)
                    OpenCodeSectionLabel(
                        if (state.isEditMode) {
                            stringResource(R.string.server_edit_subtitle)
                        } else {
                            stringResource(R.string.server_add_subtitle)
                        },
                    )
                }
            }
            item {
                OpenCodeCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OpenCodeSectionLabel(stringResource(R.string.server_section_base_connection))
                        ServerField(stringResource(R.string.server_base_url_label), state.baseUrl, viewModel::onBaseUrlChanged)
                        ServerField(stringResource(R.string.server_name_label), state.name, viewModel::onNameChanged)
                        TwoColumnRow(
                            first = {
                                ServerField(stringResource(R.string.server_username_label), state.username, viewModel::onUsernameChanged)
                            },
                            second = {
                                ServerField(
                                    label = stringResource(R.string.server_password_label, sensitiveSuffix),
                                    value = state.password,
                                    onValueChange = viewModel::onPasswordChanged,
                                    password = true,
                                )
                            },
                        )

                        ConnectionModeRow(state.connectionMode, viewModel::onConnectionModeChanged)
                        if (state.connectionMode == ServerConnectionMode.Ssh) {
                            OpenCodeSectionLabel(stringResource(R.string.server_section_ssh_forwarding))
                            ServerField(stringResource(R.string.server_ssh_host_label), state.sshHost, viewModel::onSshHostChanged)
                            TwoColumnRow(
                                first = {
                                    ServerField(
                                        label = stringResource(R.string.server_ssh_port_label),
                                        value = state.sshPort,
                                        onValueChange = viewModel::onSshPortChanged,
                                        keyboardType = KeyboardType.Number,
                                    )
                                },
                                second = {
                                    ServerField(stringResource(R.string.server_ssh_username_label), state.sshUsername, viewModel::onSshUsernameChanged)
                                },
                            )
                            AuthMethodRow(state.sshAuthMethod, viewModel::onSshAuthMethodChanged)
                            if (state.sshAuthMethod.usesPassword) {
                                ServerField(
                                    label = stringResource(R.string.server_ssh_password_label, sensitiveSuffix),
                                    value = state.sshPassword,
                                    onValueChange = viewModel::onSshPasswordChanged,
                                    password = true,
                                )
                            }
                            if (state.sshAuthMethod.usesPrivateKey) {
                                PrivateKeySourceRow(state.privateKeySource, viewModel::onPrivateKeySourceChanged)
                                if (state.privateKeySource == PrivateKeySource.Text) {
                                    ServerField(
                                        label = stringResource(R.string.server_private_key_text_label, sensitiveSuffix),
                                        value = state.privateKey,
                                        onValueChange = viewModel::onPrivateKeyChanged,
                                        singleLine = false,
                                        minLines = 5,
                                    )
                                } else {
                                    PrivateKeyFilePanel(
                                        fileName = state.privateKeyFileName,
                                        isReading = state.isReadingPrivateKey,
                                        onChooseFile = { privateKeyLauncher.launch("*/*") },
                                        onClearFile = viewModel::onPrivateKeyFileCleared,
                                    )
                                }
                                ServerField(
                                    label = stringResource(R.string.server_private_key_passphrase_label, sensitiveSuffix),
                                    value = state.privateKeyPassphrase,
                                    onValueChange = viewModel::onPrivateKeyPassphraseChanged,
                                    password = true,
                                )
                            }

                            OpenCodeSectionLabel(stringResource(R.string.server_section_forwarding_settings))
                            TwoColumnRow(
                                first = {
                                    ServerField(
                                        label = stringResource(R.string.server_local_port_label),
                                        value = state.localPort,
                                        onValueChange = viewModel::onLocalPortChanged,
                                        keyboardType = KeyboardType.Number,
                                    )
                                },
                                second = {
                                    ServerField(
                                        label = stringResource(R.string.server_connect_timeout_label),
                                        value = state.connectTimeoutSeconds,
                                        onValueChange = viewModel::onConnectTimeoutChanged,
                                        keyboardType = KeyboardType.Number,
                                    )
                                },
                            )
                            ServerField(
                                label = stringResource(R.string.server_keepalive_label),
                                value = state.keepAliveSeconds,
                                onValueChange = viewModel::onKeepAliveChanged,
                                keyboardType = KeyboardType.Number,
                            )

                            OpenCodeSectionLabel(stringResource(R.string.server_host_key_policy_label))
                            HostKeyPolicyRow(state.hostKeyPolicy, viewModel::onHostKeyPolicyChanged)
                            if (state.hostKeyPolicy == SshHostKeyPolicy.Fingerprint) {
                                ServerField(stringResource(R.string.server_host_fingerprint_label, sensitiveSuffix), state.hostFingerprint, viewModel::onHostFingerprintChanged)
                            } else {
                                SecurityHint(stringResource(R.string.server_host_key_hint))
                            }
                        }

                        if (state.connectionMode == ServerConnectionMode.FrpcStcp) {
                            OpenCodeSectionLabel(stringResource(R.string.server_section_frpc_stcp))
                            ServerField(stringResource(R.string.server_frpc_server_addr_label), state.frpcServerAddr, viewModel::onFrpcServerAddrChanged)
                            TwoColumnRow(
                                first = {
                                    ServerField(
                                        label = stringResource(R.string.server_frpc_server_port_label),
                                        value = state.frpcServerPort,
                                        onValueChange = viewModel::onFrpcServerPortChanged,
                                        keyboardType = KeyboardType.Number,
                                    )
                                },
                                second = {
                                    ServerField(
                                        label = stringResource(R.string.server_frpc_bind_port_label),
                                        value = state.frpcBindPort,
                                        onValueChange = viewModel::onFrpcBindPortChanged,
                                        keyboardType = KeyboardType.Number,
                                    )
                                },
                            )
                            ServerField(
                                label = stringResource(R.string.server_frpc_auth_token_label, sensitiveSuffix),
                                value = state.frpcAuthToken,
                                onValueChange = viewModel::onFrpcAuthTokenChanged,
                                password = true,
                            )
                            ServerField(stringResource(R.string.server_frpc_user_label), state.frpcUser, viewModel::onFrpcUserChanged)
                            ServerField(stringResource(R.string.server_frpc_server_user_label), state.frpcServerUser, viewModel::onFrpcServerUserChanged)
                            ServerField(stringResource(R.string.server_frpc_server_name_label), state.frpcServerName, viewModel::onFrpcServerNameChanged)
                            ServerField(
                                label = stringResource(R.string.server_frpc_secret_key_label, sensitiveSuffix),
                                value = state.frpcSecretKey,
                                onValueChange = viewModel::onFrpcSecretKeyChanged,
                                password = true,
                            )
                            FrpcWireProtocolRow(state.frpcWireProtocol, viewModel::onFrpcWireProtocolChanged)
                            SecurityHint(stringResource(R.string.server_frpc_security_hint))
                        }

                        if (state.isLoading) {
                            Text(stringResource(R.string.server_loading_config), color = OpenCodePalette.MutedText)
                        }
                        state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                        OpenCodePrimaryButton(
                            text = if (state.isSaving) {
                                stringResource(R.string.action_saving)
                            } else if (state.isEditMode) {
                                stringResource(R.string.server_save_edit)
                            } else {
                                stringResource(R.string.server_save_add)
                            },
                            enabled = !state.isSaving && !state.isLoading && !state.isReadingPrivateKey && state.baseUrl.isNotBlank(),
                            onClick = viewModel::save,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OpenCodeSecondaryButton(text = stringResource(R.string.action_back), onClick = onBack, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionModeRow(
    selected: ServerConnectionMode,
    onSelected: (ServerConnectionMode) -> Unit,
) {
    ChoiceRow(
        label = stringResource(R.string.server_connection_mode_label),
        choices = listOf(
            ChoiceOption(
                value = ServerConnectionMode.Direct,
                title = stringResource(R.string.server_connection_direct_title),
                description = stringResource(R.string.server_connection_direct_description),
            ),
            ChoiceOption(
                value = ServerConnectionMode.Ssh,
                title = stringResource(R.string.server_connection_ssh_title),
                description = stringResource(R.string.server_connection_ssh_description),
            ),
            ChoiceOption(
                value = ServerConnectionMode.FrpcStcp,
                title = stringResource(R.string.server_connection_frpc_stcp_title),
                description = stringResource(R.string.server_connection_frpc_stcp_description),
            ),
        ),
        selected = selected,
        onSelected = onSelected,
    )
}

@Composable
private fun AuthMethodRow(
    selected: SshAuthMethod,
    onSelected: (SshAuthMethod) -> Unit,
) {
    ChoiceRow(
        label = stringResource(R.string.server_auth_method_label),
        choices = listOf(
            ChoiceOption(
                value = SshAuthMethod.PrivateKey,
                title = stringResource(R.string.server_auth_private_key_title),
                description = stringResource(R.string.server_auth_private_key_description),
            ),
            ChoiceOption(
                value = SshAuthMethod.Password,
                title = stringResource(R.string.server_auth_password_title),
                description = stringResource(R.string.server_auth_password_description),
            ),
            ChoiceOption(
                value = SshAuthMethod.PasswordAndPrivateKey,
                title = stringResource(R.string.server_auth_password_and_key_title),
                description = stringResource(R.string.server_auth_password_and_key_description),
            ),
        ),
        selected = selected,
        onSelected = onSelected,
    )
}

@Composable
private fun PrivateKeySourceRow(
    selected: PrivateKeySource,
    onSelected: (PrivateKeySource) -> Unit,
) {
    ChoiceRow(
        label = stringResource(R.string.server_private_key_source_label),
        choices = listOf(
            ChoiceOption(
                value = PrivateKeySource.Text,
                title = stringResource(R.string.server_private_key_source_text_title),
                description = stringResource(R.string.server_private_key_source_text_description),
            ),
            ChoiceOption(
                value = PrivateKeySource.File,
                title = stringResource(R.string.server_private_key_source_file_title),
                description = stringResource(R.string.server_private_key_source_file_description),
            ),
        ),
        selected = selected,
        onSelected = onSelected,
    )
}

@Composable
private fun HostKeyPolicyRow(
    selected: SshHostKeyPolicy,
    onSelected: (SshHostKeyPolicy) -> Unit,
) {
    ChoiceRow(
        label = stringResource(R.string.server_host_key_policy_label),
        choices = listOf(
            ChoiceOption(
                value = SshHostKeyPolicy.AcceptNew,
                title = stringResource(R.string.server_host_key_accept_new_title),
                description = stringResource(R.string.server_host_key_accept_new_description),
            ),
            ChoiceOption(
                value = SshHostKeyPolicy.Fingerprint,
                title = stringResource(R.string.server_host_key_fingerprint_title),
                description = stringResource(R.string.server_host_key_fingerprint_description),
            ),
        ),
        selected = selected,
        onSelected = onSelected,
    )
}

@Composable
private fun FrpcWireProtocolRow(
    selected: FrpcWireProtocol,
    onSelected: (FrpcWireProtocol) -> Unit,
) {
    ChoiceRow(
        label = stringResource(R.string.server_frpc_wire_protocol_label),
        choices = listOf(
            ChoiceOption(
                value = FrpcWireProtocol.V1,
                title = stringResource(R.string.server_frpc_wire_protocol_v1_title),
                description = stringResource(R.string.server_frpc_wire_protocol_v1_description),
            ),
            ChoiceOption(
                value = FrpcWireProtocol.V2,
                title = stringResource(R.string.server_frpc_wire_protocol_v2_title),
                description = stringResource(R.string.server_frpc_wire_protocol_v2_description),
            ),
        ),
        selected = selected,
        onSelected = onSelected,
    )
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    choices: List<ChoiceOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = OpenCodePalette.Text)
        choices.forEach { choice ->
            ChoiceCard(
                title = choice.title,
                description = choice.description,
                selected = choice.value == selected,
                onClick = { onSelected(choice.value) },
            )
        }
    }
}

private data class ChoiceOption<T>(
    val value: T,
    val title: String,
    val description: String,
)

@Composable
private fun ChoiceCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
        ),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) OpenCodePalette.PanelMuted else OpenCodePalette.Panel,
        border = BorderStroke(1.dp, if (selected) OpenCodePalette.SelectionBorder else OpenCodePalette.Border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = OpenCodePalette.Text)
                Text(description, style = MaterialTheme.typography.bodySmall, color = OpenCodePalette.MutedText)
            }
            ChoiceIndicator(selected)
        }
    }
}

@Composable
private fun ChoiceIndicator(selected: Boolean) {
    Surface(
        modifier = Modifier.size(22.dp),
        shape = RoundedCornerShape(11.dp),
        color = if (selected) OpenCodePalette.SelectionBorder else OpenCodePalette.Panel,
        border = BorderStroke(1.dp, if (selected) OpenCodePalette.SelectionBorder else OpenCodePalette.Border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        if (selected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_opencode_check_linear),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PrivateKeyFilePanel(
    fileName: String?,
    isReading: Boolean,
    onChooseFile: () -> Unit,
    onClearFile: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = OpenCodePalette.PanelMuted,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.server_private_key_file_label), style = MaterialTheme.typography.labelLarge, color = OpenCodePalette.Text)
                Text(
                    text = when {
                        isReading -> stringResource(R.string.server_private_key_reading)
                        fileName != null -> fileName
                        else -> stringResource(R.string.server_private_key_file_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenCodePalette.MutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeSecondaryButton(
                    text = if (fileName == null) stringResource(R.string.action_choose_file) else stringResource(R.string.action_change_file),
                    onClick = onChooseFile,
                    modifier = Modifier.weight(1f),
                    enabled = !isReading,
                )
                if (fileName != null) {
                    OpenCodeSecondaryButton(
                        text = stringResource(R.string.server_private_key_cancel_selection),
                        onClick = onClearFile,
                        modifier = Modifier.weight(1f),
                        enabled = !isReading,
                    )
                }
            }
        }
    }
}

private data class PrivateKeyDocumentMetadata(
    val fileName: String,
    val sizeBytes: Long?,
) {
    override fun toString(): String =
        "PrivateKeyDocumentMetadata(fileName=<redacted>, sizeBytes=$sizeBytes)"
}

private suspend fun queryPrivateKeyDocumentMetadata(
    contentResolver: ContentResolver,
    uri: Uri,
    defaultFileName: String,
): PrivateKeyDocumentMetadata = withContext(Dispatchers.IO) {
    var fileName: String? = null
    var sizeBytes: Long? = null
    contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let { fileName = cursor.getString(it) }
            cursor.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let { sizeBytes = cursor.getLong(it).takeIf { size -> size >= 0L } }
        }
    }
    PrivateKeyDocumentMetadata(
        fileName = fileName?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: defaultFileName,
        sizeBytes = sizeBytes,
    )
}

@Composable
private fun SecurityHint(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = OpenCodePalette.PanelMuted,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = OpenCodePalette.MutedText,
        )
    }
}

@Composable
private fun TwoColumnRow(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 330.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                first()
                second()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) { first() }
                Column(modifier = Modifier.weight(1f)) { second() }
            }
        }
    }
}

@Composable
private fun ServerField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType? = null,
) {
    OutlinedTextField(
        modifier = modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(
            keyboardType = resolveServerFieldKeyboardType(password, keyboardType),
        ),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = openCodeOutlinedTextFieldColors(),
    )
}

internal fun resolveServerFieldKeyboardType(
    password: Boolean,
    keyboardType: KeyboardType?,
): KeyboardType = keyboardType ?: if (password) KeyboardType.Password else KeyboardType.Text

private val SshAuthMethod.usesPassword: Boolean
    get() = this == SshAuthMethod.Password || this == SshAuthMethod.PasswordAndPrivateKey

private val SshAuthMethod.usesPrivateKey: Boolean
    get() = this == SshAuthMethod.PrivateKey || this == SshAuthMethod.PasswordAndPrivateKey
