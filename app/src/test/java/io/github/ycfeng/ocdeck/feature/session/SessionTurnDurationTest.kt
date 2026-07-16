package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTurnDurationTest {
    @Test
    fun usesUserTurnStartAndLatestAssistantCompletion() {
        val baseTime = 1_700_000_000_000L
        val userMessageId = "msg_fixture_001"
        val durations = assistantTurnDurationMillisByMessageId(
            listOf(
                message(id = userMessageId, role = "user", createdAt = baseTime),
                message(
                    id = "msg_fixture_002",
                    role = "assistant",
                    parentId = userMessageId,
                    createdAt = baseTime + 26,
                    completedAt = baseTime + 29_566,
                ),
                message(
                    id = "msg_fixture_003",
                    role = "assistant",
                    parentId = userMessageId,
                    createdAt = baseTime + 29_573,
                    completedAt = baseTime + 43_472,
                ),
                message(
                    id = "msg_fixture_004",
                    role = "assistant",
                    parentId = userMessageId,
                    createdAt = baseTime + 43_478,
                    completedAt = baseTime + 56_810,
                ),
                message(
                    id = "msg_fixture_005",
                    role = "assistant",
                    parentId = userMessageId,
                    createdAt = baseTime + 56_815,
                    completedAt = baseTime + 70_631,
                ),
                message(
                    id = "msg_fixture_006",
                    role = "assistant",
                    parentId = userMessageId,
                    createdAt = baseTime + 70_638,
                    completedAt = baseTime + 149_828,
                ),
                message(
                    id = "msg_fixture_007",
                    role = "assistant",
                    parentId = userMessageId,
                    createdAt = baseTime + 149_836,
                    completedAt = baseTime + 253_729,
                ),
            ),
        )

        assertEquals(253_729L, durations.getValue("msg_fixture_007"))
        assertEquals(253_729L, durations.getValue("msg_fixture_002"))
        assertEquals("4m 14s", formatDurationMillis(durations.getValue("msg_fixture_007")))
    }

    @Test
    fun ignoresMessagesWithoutValidUserTurn() {
        val durations = assistantTurnDurationMillisByMessageId(
            listOf(
                message(id = "msg_user", role = "user", createdAt = 1_000),
                message(id = "msg_no_parent", role = "assistant", completedAt = 2_000),
                message(id = "msg_negative", role = "assistant", parentId = "msg_user", completedAt = 999),
            ),
        )

        assertFalse(durations.containsKey("msg_no_parent"))
        assertFalse(durations.containsKey("msg_negative"))
    }

    @Test
    fun formatsDurationWithWebStyleRounding() {
        assertEquals("59s", formatDurationMillis(59_499))
        assertEquals("1m 0s", formatDurationMillis(59_500))
        assertEquals("4m 14s", formatDurationMillis(253_729))
        assertNull(formatDurationMillis(-1))
    }

    private fun message(
        id: String,
        role: String,
        parentId: String? = null,
        createdAt: Long? = null,
        completedAt: Long? = null,
    ): OpenCodeMessage = OpenCodeMessage(
        id = id,
        sessionId = "ses_fixture_001",
        role = role,
        text = role,
        parentId = parentId,
        createdAt = createdAt,
        completedAt = completedAt,
    )
}
