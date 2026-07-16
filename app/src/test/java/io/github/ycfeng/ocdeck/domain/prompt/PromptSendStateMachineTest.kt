package io.github.ycfeng.ocdeck.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptSendStateMachineTest {
    private val stateMachine = PromptSendStateMachine()

    @Test
    fun emptyPromptIsDisabledWhenSessionIsIdle() {
        val action = stateMachine.evaluate(PromptSendInput(text = "   ", isWorking = false))

        assertEquals(PromptSendAction.Disabled, action)
    }

    @Test
    fun emptyPromptStopsWhenSessionIsWorking() {
        val action = stateMachine.evaluate(PromptSendInput(text = "", isWorking = true))

        assertEquals(PromptSendAction.Stop, action)
    }

    @Test
    fun nonEmptyPromptSendsEvenWhenSessionIsWorking() {
        val action = stateMachine.evaluate(PromptSendInput(text = "continue", isWorking = true))

        assertEquals(PromptSendAction.SendPrompt, action)
    }

    @Test
    fun attachmentOnlyPromptSendsWhenSessionIsIdle() {
        val action = stateMachine.evaluate(PromptSendInput(text = "", hasAttachments = true, isWorking = false))

        assertEquals(PromptSendAction.SendPrompt, action)
    }

    @Test
    fun attachmentOnlyPromptSendsEvenWhenSessionIsWorking() {
        val action = stateMachine.evaluate(PromptSendInput(text = "", hasAttachments = true, isWorking = true))

        assertEquals(PromptSendAction.SendPrompt, action)
    }

    @Test
    fun contextOnlyPromptSendsWhenSessionIsIdle() {
        val action = stateMachine.evaluate(PromptSendInput(text = "", hasContext = true, isWorking = false))

        assertEquals(PromptSendAction.SendPrompt, action)
    }

    @Test
    fun contextOnlyPromptSendsEvenWhenSessionIsWorking() {
        val action = stateMachine.evaluate(PromptSendInput(text = "", hasContext = true, isWorking = true))

        assertEquals(PromptSendAction.SendPrompt, action)
    }
}
