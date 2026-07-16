package io.github.ycfeng.ocdeck.core.navigation

import android.net.Uri
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.domain.prompt.OpenCodePromptSender
import kotlinx.serialization.Serializable

@Serializable
data object AboutRoute

object OpenCodeRoutes {
    const val serverList = "servers"
    const val addServer = "servers/add"
    const val editServerPattern = "servers/{serverId}/edit"
    fun editServer(serverId: String) = "servers/${Uri.encode(serverId)}/edit"

    const val noServerSettings = "settings"
    const val noServerGeneralSettings = "settings/general"
    const val noServerBackgroundRunSettings = "settings/background"

    const val projectPickerPattern = "servers/{serverId}/projects"
    fun projectPicker(serverId: String) = "servers/${Uri.encode(serverId)}/projects"

    const val projectShellPattern = "servers/{serverId}/projects/{directory}"
    fun projectShell(serverId: String, directory: String) =
        "servers/${Uri.encode(serverId)}/projects/${Uri.encode(directory)}"

    const val sessionInitialAgentArg = "initialAgent"
    const val sessionInitialProviderArg = "initialProvider"
    const val sessionInitialModelArg = "initialModel"
    const val sessionInitialVariantArg = "initialVariant"
    const val sessionDetailPattern =
        "servers/{serverId}/projects/{directory}/sessions/{sessionId}" +
            "?$sessionInitialAgentArg={$sessionInitialAgentArg}" +
            "&$sessionInitialProviderArg={$sessionInitialProviderArg}" +
            "&$sessionInitialModelArg={$sessionInitialModelArg}" +
            "&$sessionInitialVariantArg={$sessionInitialVariantArg}"

    fun sessionDetail(
        serverId: String,
        directory: String,
        sessionId: String,
        initialAgentId: String? = null,
        initialModelSelection: PromptModelSelection? = null,
    ): String {
        val base = "servers/${Uri.encode(serverId)}/projects/${Uri.encode(directory)}/sessions/${Uri.encode(sessionId)}"
        val query = buildList {
            initialAgentId?.takeIf(String::isNotBlank)?.let {
                add("$sessionInitialAgentArg=${Uri.encode(it)}")
            }
            initialModelSelection
                ?.takeIf { it.providerId.isNotBlank() && it.modelId.isNotBlank() }
                ?.let { selection ->
                    add("$sessionInitialProviderArg=${Uri.encode(selection.providerId)}")
                    add("$sessionInitialModelArg=${Uri.encode(selection.modelId)}")
                    selection.variant?.takeIf(String::isNotBlank)?.let {
                        add("$sessionInitialVariantArg=${Uri.encode(it)}")
                    }
                }
        }
        return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
    }

    const val reviewPattern = "servers/{serverId}/projects/{directory}/review?sessionId={sessionId}"
    fun review(serverId: String, directory: String, sessionId: String? = null): String {
        val base = "servers/${Uri.encode(serverId)}/projects/${Uri.encode(directory)}/review"
        return if (sessionId == null) base else "$base?sessionId=${Uri.encode(sessionId)}"
    }

    const val settingsPattern = "servers/{serverId}/settings"
    fun settings(serverId: String) = "servers/${Uri.encode(serverId)}/settings"

    const val generalSettingsPattern = "servers/{serverId}/settings/general"
    fun generalSettings(serverId: String) = "servers/${Uri.encode(serverId)}/settings/general"

    const val backgroundRunSettingsPattern = "servers/{serverId}/settings/background"
    fun backgroundRunSettings(serverId: String) = "servers/${Uri.encode(serverId)}/settings/background"

    const val providerDirectoryArg = "directory"
    const val providerWorkspaceArg = "workspace"
    const val customProviderIdArg = "providerId"
    const val providerSettingsPattern =
        "servers/{serverId}/settings/providers" +
            "?$providerDirectoryArg={$providerDirectoryArg}" +
            "&$providerWorkspaceArg={$providerWorkspaceArg}"
    fun providerSettings(serverId: String, directory: String? = null, workspace: String? = null): String =
        scopedProviderRoute("servers/${Uri.encode(serverId)}/settings/providers", directory, workspace)

    const val customProviderFormPattern =
        "servers/{serverId}/settings/providers/custom" +
            "?$providerDirectoryArg={$providerDirectoryArg}" +
            "&$providerWorkspaceArg={$providerWorkspaceArg}" +
            "&$customProviderIdArg={$customProviderIdArg}"
    fun customProviderForm(
        serverId: String,
        directory: String? = null,
        workspace: String? = null,
        providerId: String? = null,
    ): String = scopedProviderRoute(
        base = "servers/${Uri.encode(serverId)}/settings/providers/custom",
        directory = directory,
        workspace = workspace,
        providerId = providerId,
    )

    const val modelSettingsPattern = "servers/{serverId}/settings/models"
    fun modelSettings(serverId: String) = "servers/${Uri.encode(serverId)}/settings/models"

    private fun scopedProviderRoute(
        base: String,
        directory: String?,
        workspace: String?,
        providerId: String? = null,
    ): String {
        val query = buildList {
            directory?.takeIf(String::isNotBlank)?.let { add("$providerDirectoryArg=${Uri.encode(it)}") }
            workspace?.takeIf(String::isNotBlank)?.let { add("$providerWorkspaceArg=${Uri.encode(it)}") }
            providerId?.takeIf(String::isNotBlank)?.let { add("$customProviderIdArg=${Uri.encode(it)}") }
        }
        return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
    }
}

internal data class SessionComposerRouteSelection(
    val agentId: String?,
    val modelSelection: PromptModelSelection?,
)

internal fun resolveSessionComposerRouteSelection(
    sessionId: String,
    initialAgentId: String?,
    initialProviderId: String?,
    initialModelId: String?,
    initialVariant: String?,
): SessionComposerRouteSelection {
    if (sessionId != OpenCodePromptSender.NEW_SESSION_ID) {
        return SessionComposerRouteSelection(agentId = null, modelSelection = null)
    }
    val providerId = initialProviderId?.takeIf(String::isNotBlank)
    val modelId = initialModelId?.takeIf(String::isNotBlank)
    val modelSelection = if (providerId != null && modelId != null) {
        PromptModelSelection(
            providerId = providerId,
            modelId = modelId,
            variant = initialVariant?.takeIf(String::isNotBlank),
        )
    } else {
        null
    }
    return SessionComposerRouteSelection(
        agentId = initialAgentId?.takeIf(String::isNotBlank),
        modelSelection = modelSelection,
    )
}
