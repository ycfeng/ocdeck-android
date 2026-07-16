package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.core.store.SseFailureReason
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerTransportIdentity
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

data class SseConnection(
    val server: ServerConfig,
    val password: String?,
    val effectiveBaseUrl: String,
    val transportIdentity: ServerTransportIdentity,
) {
    override fun toString(): String =
        "SseConnection(serverId=<redacted>, effectiveBaseUrl=<redacted>, " +
            "transportIdentity=$transportIdentity, " +
            "password=${if (password == null) "null" else "<redacted>"})"
}

fun interface SseConnectionProvider {
    suspend fun getSseConnection(serverId: String): SseConnection
}

data class SseRequest(val url: String) {
    override fun toString(): String = "SseRequest(url=<redacted>)"
}

data class SseFailure(
    val reason: SseFailureReason,
) {
    override fun toString(): String = "SseFailure(reason=$reason)"

    companion object {
        fun from(throwable: Throwable): SseFailure = SseFailure(throwable.toSseFailureReason())
    }
}

interface SseEventListener {
    fun onOpen()
    fun onEvent(id: String?, type: String?, data: String)
    fun onFailure(failure: SseFailure)
    fun onClosed()
}

fun interface SseEventSource {
    fun cancel()
}

fun interface SseEventSourceFactory {
    fun open(connection: SseConnection, request: SseRequest, listener: SseEventListener): SseEventSource
}

internal class OkHttpSseEventSourceFactory(
    private val redactor: Redactor,
    private val callFactory: (OkHttpClient, Request) -> Call = { client, request -> client.newCall(request) },
    private val reader: BoundedSseReader = BoundedSseReader(),
) : SseEventSourceFactory {
    override fun open(
        connection: SseConnection,
        request: SseRequest,
        listener: SseEventListener,
    ): SseEventSource {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(connection.server.username, connection.password))
            .addInterceptor(RedactingInterceptor(redactor))
            .build()
        val okHttpRequest = Request.Builder()
            .url(request.url)
            .header("Accept", "text/event-stream")
            .header("Accept-Encoding", "identity")
            .build()
        val source = OkHttpCallSseEventSource(
            call = callFactory(client, okHttpRequest),
            listener = listener,
            reader = reader,
        )
        source.start()
        return source
    }
}

private class OkHttpCallSseEventSource(
    private val call: Call,
    private val listener: SseEventListener,
    private val reader: BoundedSseReader,
) : SseEventSource, Callback {
    private val cancelledByOwner = AtomicBoolean()
    private val cancellingForReportedFailure = AtomicBoolean()
    private val terminalCallbackDelivered = AtomicBoolean()

    fun start() {
        call.enqueue(this)
    }

    override fun cancel() {
        if (cancelledByOwner.compareAndSet(false, true)) call.cancel()
    }

    override fun onFailure(call: Call, e: IOException) {
        if (cancelledByOwner.get() || cancellingForReportedFailure.get()) return
        deliverFailure(SseFailure.from(e))
    }

    override fun onResponse(call: Call, response: Response) {
        if (cancelledByOwner.get()) {
            response.close()
            return
        }
        if (response.code != HTTP_OK) {
            val responseCode = response.code
            response.close()
            deliverFailure(
                SseFailure(SseFailureReason.HttpStatus(responseCode)),
            )
            return
        }
        if (response.hasNonIdentityContentEncoding()) {
            response.close()
            deliverFailure(SseFailure.from(SseContentEncodingException()))
            return
        }
        val contentType = response.body.contentType()
        if (contentType?.type != "text" || contentType.subtype != "event-stream") {
            response.close()
            deliverFailure(
                SseFailure(SseFailureReason.InvalidResponse),
            )
            return
        }

        var cleanEof = false
        var streamFailure: SseFailure? = null
        try {
            response.use { currentResponse ->
                if (!deliverOpen()) return
                reader.read(currentResponse.body.source()) { event ->
                    if (cancelledByOwner.get()) throw ExplicitSseCancellationException()
                    listener.onEvent(event.id, event.type, event.data)
                }
                cleanEof = !cancelledByOwner.get()
            }
        } catch (_: ExplicitSseCancellationException) {
            // Owner cancellation is terminal for this transport but is not a retryable stream failure.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (tooLarge: SseInboundPayloadTooLargeException) {
            cancellingForReportedFailure.set(true)
            call.cancel()
            streamFailure = SseFailure(SseFailureReason.ResponseTooLarge)
        } catch (exception: Exception) {
            if (!cancelledByOwner.get()) {
                streamFailure = SseFailure.from(exception)
            }
        }

        when {
            streamFailure != null -> deliverFailure(streamFailure)
            cleanEof -> deliverClosed()
        }
    }

    private fun deliverOpen(): Boolean {
        if (cancelledByOwner.get() || terminalCallbackDelivered.get()) return false
        listener.onOpen()
        return !cancelledByOwner.get() && !terminalCallbackDelivered.get()
    }

    private fun deliverFailure(failure: SseFailure) {
        if (cancelledByOwner.get()) return
        if (terminalCallbackDelivered.compareAndSet(false, true)) listener.onFailure(failure)
    }

    private fun deliverClosed() {
        if (cancelledByOwner.get()) return
        if (terminalCallbackDelivered.compareAndSet(false, true)) listener.onClosed()
    }

    private companion object {
        const val HTTP_OK = 200
    }
}

private class ExplicitSseCancellationException : IOException()

private fun Response.hasNonIdentityContentEncoding(): Boolean =
    headers.values("Content-Encoding")
        .flatMap { value -> value.split(',') }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .any { encoding -> !encoding.equals("identity", ignoreCase = true) }

internal fun Throwable.toSseFailureReason(): SseFailureReason {
    val causes = generateSequence(this) { it.cause }.toList()
    val failure = OpenCodeFailureClassifier.classify(this)
    if (failure == OpenCodeFailure.Unknown && causes.any { it is IllegalArgumentException }) {
        return SseFailureReason.InvalidResponse
    }
    return when (failure) {
        is OpenCodeFailure.HttpStatus -> SseFailureReason.HttpStatus(failure.code)
        OpenCodeFailure.NetworkUnavailable -> SseFailureReason.NetworkUnavailable
        OpenCodeFailure.Timeout -> SseFailureReason.Timeout
        OpenCodeFailure.InvalidResponse -> SseFailureReason.InvalidResponse
        OpenCodeFailure.ResponseTooLarge -> SseFailureReason.ResponseTooLarge
        OpenCodeFailure.TransportChanged -> SseFailureReason.TransportChanged
        is OpenCodeFailure.OperationRejected -> SseFailureReason.OperationRejected(failure.reason)
        OpenCodeFailure.Unknown -> SseFailureReason.Unknown
    }
}
