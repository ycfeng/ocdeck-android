package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.network.OpenCodeOperationRejectionReason

sealed interface SseFailureReason {
    data class HttpStatus(val code: Int) : SseFailureReason
    data object NetworkUnavailable : SseFailureReason
    data object Timeout : SseFailureReason
    data object InvalidResponse : SseFailureReason
    data object ResponseTooLarge : SseFailureReason
    data object TransportChanged : SseFailureReason
    data class OperationRejected(
        val reason: OpenCodeOperationRejectionReason = OpenCodeOperationRejectionReason.Unspecified,
    ) : SseFailureReason
    data object StreamClosed : SseFailureReason
    data object Unknown : SseFailureReason
}

sealed interface SseConnectionStatus {
    data object Connecting : SseConnectionStatus
    data object Open : SseConnectionStatus
    data class Retrying(
        val attempt: Int,
        val delayMillis: Long,
        val reason: SseFailureReason,
    ) : SseConnectionStatus
    data class Failed(val reason: SseFailureReason) : SseConnectionStatus
    data object Closed : SseConnectionStatus
}
