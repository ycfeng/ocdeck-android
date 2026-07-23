package io.github.ycfeng.ocdeck.core.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

interface SessionMessagesTransport {
    suspend fun load(
        sessionId: String,
        directory: String,
        workspace: String?,
        before: String? = null,
    ): SessionMessagesPage

    companion object {
        val Unavailable: SessionMessagesTransport = object : SessionMessagesTransport {
            override suspend fun load(
                sessionId: String,
                directory: String,
                workspace: String?,
                before: String?,
            ): SessionMessagesPage = throw SessionMessagesTransportUnavailableException()
        }
    }
}

data class SessionMessagesPage(
    val items: List<MessageWithPartsDto>,
    val nextCursor: String?,
) {
    override fun toString(): String =
        "SessionMessagesPage(itemCount=${items.size}, nextCursorPresent=${nextCursor != null})"
}

class SessionMessagesTransportUnavailableException : IllegalStateException()

internal class OkHttpSessionMessagesTransport(
    baseUrl: String,
    private val callFactory: Call.Factory,
    private val json: Json,
    private val maxDecodedBytes: Long = InboundPayloadLimits.SESSION_MESSAGES_DECODED_RESPONSE_BYTES,
    private val maxEncodedBytes: Long = InboundPayloadLimits.SESSION_MESSAGES_ENCODED_RESPONSE_BYTES,
) : SessionMessagesTransport {
    private val baseUrl = baseUrl.trim().trimEnd('/').toHttpUrl()

    override suspend fun load(
        sessionId: String,
        directory: String,
        workspace: String?,
        before: String?,
    ): SessionMessagesPage {
        val urlBuilder = baseUrl.newBuilder()
            .addPathSegment("session")
            .addPathSegment(sessionId)
            .addPathSegment("message")
            .addQueryParameter("directory", directory)
        workspace?.let { urlBuilder.addQueryParameter("workspace", it) }
        urlBuilder.addQueryParameter("limit", SESSION_MESSAGE_LIMIT.toString())
        before?.let { urlBuilder.addQueryParameter("before", it) }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .tag(EncodedResponseLimit::class.java, EncodedResponseLimit(maxEncodedBytes))
            .get()
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = callFactory.newCall(request)
            val callback = SessionMessagesCallback(call, continuation, json, maxDecodedBytes)
            continuation.invokeOnCancellation { callback.cancelByOwner() }
            try {
                call.enqueue(callback)
            } catch (failure: Throwable) {
                callback.onEnqueueFailure(failure)
            }
        }
    }

    private companion object {
        const val SESSION_MESSAGE_LIMIT = 200
    }
}

private class SessionMessagesCallback(
    private val call: Call,
    private val continuation: CancellableContinuation<SessionMessagesPage>,
    private val json: Json,
    private val maxBytes: Long,
) : Callback {
    private val callbackClaimed = AtomicBoolean()
    private val completed = AtomicBoolean()
    private val cancelledByOwner = AtomicBoolean()

    override fun onFailure(call: Call, e: IOException) {
        if (!callbackClaimed.compareAndSet(false, true)) return
        completeFailure(e)
    }

    override fun onResponse(call: Call, response: Response) {
        if (!callbackClaimed.compareAndSet(false, true)) {
            response.close()
            return
        }
        if (completed.get()) {
            response.close()
            return
        }

        try {
            val page = response.use { currentResponse ->
                if (!currentResponse.isSuccessful) {
                    throw SessionMessagesHttpException(currentResponse.code)
                }
                val body = currentResponse.body
                if (completed.get()) throw OwnerCancelledSessionMessagesCallException()
                SessionMessagesPage(
                    items = body.readSessionMessages(json, maxBytes),
                    nextCursor = currentResponse.header(NEXT_CURSOR_HEADER)?.takeIf(String::isNotBlank),
                )
            }
            completeSuccess(page)
        } catch (_: OwnerCancelledSessionMessagesCallException) {
            // Coroutine cancellation owns completion and has already cancelled the call.
        } catch (failure: Throwable) {
            completeFailure(failure)
        }
    }

    fun cancelByOwner() {
        cancelledByOwner.set(true)
        if (completed.compareAndSet(false, true)) {
            // Let response.use close the body on the callback thread after cancel interrupts its read.
            // Closing here races Okio's active read and may also perform network cleanup on the main thread.
            call.cancel()
        }
    }

    fun onEnqueueFailure(failure: Throwable) {
        callbackClaimed.compareAndSet(false, true)
        completeFailure(failure)
    }

    private fun completeSuccess(page: SessionMessagesPage) {
        if (completed.compareAndSet(false, true)) continuation.resume(page)
    }

    private fun completeFailure(failure: Throwable) {
        if (cancelledByOwner.get()) return
        if (completed.compareAndSet(false, true)) continuation.resumeWithException(failure)
    }
}

private class OwnerCancelledSessionMessagesCallException : IOException()

private const val NEXT_CURSOR_HEADER = "X-Next-Cursor"
