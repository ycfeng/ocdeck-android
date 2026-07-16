package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionContextUsageTest {
    @Test
    fun buildsTokenTotalAndContextUsagePercentFromLatestAssistantMessage() {
        val baseTime = 1_700_000_000_000L
        val usage = buildSessionContextUsage(
            session = OpenCodeSession(
                id = "ses_fixture_001",
                title = "Fixture session",
                normalizedDirectory = "X:/workspace/sample-project",
                path = null,
                parentId = null,
                agent = "build",
                modelLabel = "provider-alpha/model-fast/xhigh",
                updatedAt = baseTime + 2_000,
                archivedAt = null,
                modelProviderId = "provider-alpha",
                modelId = "model-fast",
                modelVariant = "xhigh",
                createdAt = baseTime,
                cost = 0.0,
                tokens = OpenCodeSessionTokens(
                    input = 3_000,
                    output = 200,
                    reasoning = 100,
                    cacheRead = 4_000,
                    cacheWrite = 0,
                ),
            ),
            messages = listOf(
                OpenCodeMessage(id = "msg_fixture_001", sessionId = "ses_fixture_001", role = "user", text = "one"),
                OpenCodeMessage(id = "msg_fixture_002", sessionId = "ses_fixture_001", role = "user", text = "two"),
                OpenCodeMessage(
                    id = "msg_fixture_003",
                    sessionId = "ses_fixture_001",
                    role = "assistant",
                    text = "three",
                    modelProviderId = "provider-alpha",
                    modelId = "model-fast",
                    createdAt = baseTime + 1_000,
                    tokens = OpenCodeSessionTokens(input = 500, output = 50),
                ),
                OpenCodeMessage(
                    id = "msg_fixture_004",
                    sessionId = "ses_fixture_001",
                    role = "assistant",
                    text = "four",
                    modelProviderId = "provider-alpha",
                    modelId = "model-fast",
                    createdAt = baseTime + 2_000,
                    tokens = OpenCodeSessionTokens(
                        input = 1_000,
                        output = 200,
                        reasoning = 100,
                        cacheRead = 700,
                    ),
                ),
            ),
            models = listOf(
                OpenCodeModel(
                    providerId = "provider-alpha",
                    modelId = "model-fast",
                    name = "Model Fast",
                    providerName = "Provider Alpha",
                    isConnected = true,
                    contextLimit = 10_000,
                ),
            ),
        )

        assertEquals(7_300L, usage.totalTokens)
        assertEquals(2_000L, usage.currentTurnTokens)
        assertEquals(20, usage.usagePercent)
        assertEquals(0.2f, usage.usageRatio ?: 0f, 0.0001f)
        assertEquals("provider-alpha", usage.providerId)
        assertEquals("Model Fast", usage.modelName)
        assertEquals(2, usage.userMessageCount)
        assertEquals(2, usage.assistantMessageCount)
        assertEquals(4, usage.rawMessages.size)
        assertEquals(baseTime + 2_000, usage.updatedAt)
    }
}
