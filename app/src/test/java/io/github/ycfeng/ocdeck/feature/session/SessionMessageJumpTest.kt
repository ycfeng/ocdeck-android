package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMessageJumpTest {
    @Test
    fun findsFirstUserMessageInRenderedOrderIgnoringRoleCase() {
        val messages = listOf(
            message("assistant"),
            message("USER", text = ""),
            message("user"),
        )

        assertEquals(1, findFirstUserMessageIndex(messages))
    }

    @Test
    fun returnsNullWhenRenderedMessagesHaveNoUserRole() {
        assertNull(findFirstUserMessageIndex(listOf(message("assistant"), message("tool"))))
    }

    @Test
    fun resolvesLatestRenderedMessageWithoutThinkingItem() {
        assertEquals(2, resolveLatestMessageItemIndex(messageCount = 3, showAssistantThinking = false))
    }

    @Test
    fun resolvesThinkingItemAfterRenderedMessages() {
        assertEquals(3, resolveLatestMessageItemIndex(messageCount = 3, showAssistantThinking = true))
        assertEquals(0, resolveLatestMessageItemIndex(messageCount = 0, showAssistantThinking = true))
    }

    @Test
    fun returnsNullForEmptyListWithoutThinkingItem() {
        assertNull(resolveLatestMessageItemIndex(messageCount = 0, showAssistantThinking = false))
    }

    @Test
    fun emptyFilteredTimelineLoadsOneOlderPageAndKeepsFirstUserControlAvailable() {
        assertTrue(shouldLoadOlderMessagesForEmptyTimeline(messageCount = 0, hasOlderMessages = true))
        assertTrue(
            shouldShowMessageJumpControls(
                latestMessageItemIndex = null,
                hasOlderMessages = true,
                isTextSelectionActive = false,
            ),
        )
        assertFalse(
            shouldShowMessageJumpControls(
                latestMessageItemIndex = null,
                hasOlderMessages = true,
                isTextSelectionActive = true,
            ),
        )
    }

    private fun message(role: String, text: String = "message") = OpenCodeMessage(
        id = "msg_$role",
        sessionId = "ses_test",
        role = role,
        text = text,
    )
}
