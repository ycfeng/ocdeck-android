package io.github.ycfeng.ocdeck.core.network

import java.io.IOException

internal object InboundPayloadLimits {
    private const val MEBIBYTE_BYTES = 1024L * 1024L

    const val RETROFIT_RESPONSE_BYTES = 16L * MEBIBYTE_BYTES

    // A 32 MiB SSE budget accommodates the Base64 echo of one 20 MiB raw attachment plus framing.
    const val SSE_EVENT_DATA_BYTES = 32L * MEBIBYTE_BYTES
    const val SSE_LINE_BYTES = 32L * MEBIBYTE_BYTES

    // Session history can contain several attachment echoes; cap the complete response peak at 64 MiB.
    const val SESSION_MESSAGES_RESPONSE_BYTES = 64L * MEBIBYTE_BYTES

    const val READ_BUFFER_BYTES = 8 * 1024
}

internal open class InboundPayloadTooLargeException : IOException()

internal class RetrofitInboundResponseTooLargeException : InboundPayloadTooLargeException()

internal class RetrofitInboundResponsePolicyMissingException : IOException()

internal class SessionMessagesResponseTooLargeException : InboundPayloadTooLargeException()

internal class SessionMessagesHttpException(val statusCode: Int) : IOException()

internal sealed class SseInboundPayloadTooLargeException : InboundPayloadTooLargeException()

internal class SseLineTooLargeException : SseInboundPayloadTooLargeException()

internal class SseEventDataTooLargeException : SseInboundPayloadTooLargeException()

internal sealed class SseProtocolException : IOException()

internal class SseUnexpectedHttpStatusException(val statusCode: Int) : SseProtocolException()

internal class SseContentTypeException : SseProtocolException()
