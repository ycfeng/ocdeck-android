package io.github.ycfeng.ocdeck.core.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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

fun interface SessionMessagesTransport {
    suspend fun load(
        sessionId: String,
        directory: String,
        workspace: String?,
    ): List<MessageWithPartsDto>

    companion object {
        val Unavailable: SessionMessagesTransport = object : SessionMessagesTransport {
            override suspend fun load(
                sessionId: String,
                directory: String,
                workspace: String?,
            ): List<MessageWithPartsDto> = throw SessionMessagesTransportUnavailableException()
        }
    }
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
    ): List<MessageWithPartsDto> {
        val urlBuilder = baseUrl.newBuilder()
            .addPathSegment("session")
            .addPathSegment(sessionId)
            .addPathSegment("message")
            .addQueryParameter("directory", directory)
        workspace?.let { urlBuilder.addQueryParameter("workspace", it) }
        urlBuilder.addQueryParameter("limit", SESSION_MESSAGE_LIMIT.toString())
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
    private val continuation: CancellableContinuation<List<MessageWithPartsDto>>,
    private val json: Json,
    private val maxBytes: Long,
) : Callback {
    private val callbackClaimed = AtomicBoolean()
    private val completed = AtomicBoolean()
    private val cancelledByOwner = AtomicBoolean()
    private val activeBody = AtomicReference<ResponseBody?>()

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
            val messages = response.use { currentResponse ->
                if (!currentResponse.isSuccessful) {
                    throw SessionMessagesHttpException(currentResponse.code)
                }
                val body = currentResponse.body
                activeBody.set(body)
                if (completed.get()) throw OwnerCancelledSessionMessagesCallException()
                try {
                    body.readSessionMessages(json, maxBytes)
                } finally {
                    activeBody.compareAndSet(body, null)
                }
            }
            completeSuccess(messages)
        } catch (_: OwnerCancelledSessionMessagesCallException) {
            // Coroutine cancellation owns completion and has already cancelled the call.
        } catch (failure: Throwable) {
            completeFailure(failure)
        }
    }

    fun cancelByOwner() {
        cancelledByOwner.set(true)
        if (completed.compareAndSet(false, true)) {
            call.cancel()
            activeBody.getAndSet(null)?.close()
        }
    }

    fun onEnqueueFailure(failure: Throwable) {
        callbackClaimed.compareAndSet(false, true)
        completeFailure(failure)
    }

    private fun completeSuccess(messages: List<MessageWithPartsDto>) {
        if (completed.compareAndSet(false, true)) continuation.resume(messages)
    }

    private fun completeFailure(failure: Throwable) {
        if (cancelledByOwner.get()) return
        if (completed.compareAndSet(false, true)) continuation.resumeWithException(failure)
    }
}

private class OwnerCancelledSessionMessagesCallException : IOException()
