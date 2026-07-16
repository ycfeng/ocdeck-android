package io.github.ycfeng.ocdeck.domain.prompt

import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection

interface PromptGateway {
    suspend fun createSession(
        serverId: String,
        directory: String,
        agent: String,
        model: PromptModelSelection,
        workspace: String?,
    ): Result<OpenCodeSession>

    suspend fun sendPromptAsync(
        serverId: String,
        directory: String,
        sessionId: String,
        messageId: String,
        partId: String,
        text: String,
        attachments: List<PromptAttachment>,
        projectFileContexts: List<ProjectFileContext>,
        agent: String,
        model: PromptModelSelection,
        variant: String?,
        workspace: String?,
    ): Result<Unit>

    suspend fun sendCommandAsync(
        serverId: String,
        directory: String,
        sessionId: String,
        messageId: String,
        command: String,
        arguments: String?,
        attachments: List<PromptAttachment>,
        projectFileContexts: List<ProjectFileContext>,
        agent: String,
        model: PromptModelSelection,
        variant: String?,
        workspace: String?,
    ): Result<Unit>

    suspend fun abortSession(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String?,
    ): Result<Unit>
}
