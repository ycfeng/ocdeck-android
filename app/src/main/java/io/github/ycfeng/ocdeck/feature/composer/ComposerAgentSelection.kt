package io.github.ycfeng.ocdeck.feature.composer

import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage

internal fun composerAgentOptions(agents: List<OpenCodeAgent>): List<OpenCodeAgent> = agents
    .filter { it.id == BUILD_AGENT_ID || it.id == PLAN_AGENT_ID }

internal fun resolveComposerAgent(
    localOverride: String?,
    sessionAgent: String?,
    messages: List<OpenCodeMessage>,
    agents: List<OpenCodeAgent>,
): String? {
    val availableAgents = composerAgentOptions(agents).map(OpenCodeAgent::id).toSet()
    val latestUserAgent = messages.lastOrNull { message ->
        message.role.equals("user", ignoreCase = true)
    }?.agent
    return sequenceOf(localOverride, sessionAgent, latestUserAgent)
        .mapNotNull { it.toComposerAgentOrNull() }
        .firstOrNull { it in availableAgents }
        ?: BUILD_AGENT_ID.takeIf { it in availableAgents }
        ?: PLAN_AGENT_ID.takeIf { it in availableAgents }
}

internal fun String?.toComposerAgentOrNull(): String? = this
    ?.trim()
    ?.lowercase()
    ?.takeIf { it == BUILD_AGENT_ID || it == PLAN_AGENT_ID }

private const val BUILD_AGENT_ID = "build"
private const val PLAN_AGENT_ID = "plan"
