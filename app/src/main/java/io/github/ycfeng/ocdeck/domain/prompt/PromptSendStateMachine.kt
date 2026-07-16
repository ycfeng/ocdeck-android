package io.github.ycfeng.ocdeck.domain.prompt

data class PromptSendInput(
    val text: String,
    val hasAttachments: Boolean = false,
    val hasContext: Boolean = false,
    val isWorking: Boolean = false,
) {
    override fun toString(): String =
        "PromptSendInput(textLength=${text.length}, hasAttachments=$hasAttachments, " +
            "hasContext=$hasContext, isWorking=$isWorking)"
}

sealed interface PromptSendAction {
    data object Disabled : PromptSendAction
    data object SendPrompt : PromptSendAction
    data object Stop : PromptSendAction
}

class PromptSendStateMachine {
    fun evaluate(input: PromptSendInput): PromptSendAction {
        val hasPayload = input.text.isNotBlank() || input.hasAttachments || input.hasContext
        if (input.isWorking && !hasPayload) return PromptSendAction.Stop
        if (!hasPayload) return PromptSendAction.Disabled
        return PromptSendAction.SendPrompt
    }
}
