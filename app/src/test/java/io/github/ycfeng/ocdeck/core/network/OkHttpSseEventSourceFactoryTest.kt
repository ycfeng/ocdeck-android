package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.core.store.SseFailureReason
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.KClass

class OkHttpSseEventSourceFactoryTest {
    @Test
    fun connectionsUseDistinctAuthenticatedClientsWithSharedTransportResources() {
        val clients = mutableListOf<OkHttpClient>()
        val handles = mutableListOf<SseEventSource>()
        val factory = OkHttpSseEventSourceFactory(
            redactor = Redactor(),
            callFactory = { client, request ->
                clients += client
                FakeCall(request) { call, callback -> call.callback = callback }
            },
        )

        repeat(2) { index ->
            handles += factory.open(
                connection = testSseConnection().copy(password = "password-$index"),
                request = SseRequest("http://127.0.0.1/event/$index"),
                listener = RecordingListener(),
            )
        }

        assertEquals(2, clients.size)
        assertFalse(clients[0] === clients[1])
        assertSame(clients[0].dispatcher, clients[1].dispatcher)
        assertSame(clients[0].connectionPool, clients[1].connectionPool)
        assertEquals(64, clients[0].dispatcher.maxRequests)
        assertEquals(64, clients[0].dispatcher.maxRequestsPerHost)
        assertEquals(2, clients[0].interceptors.size)
        assertEquals(2, clients[1].interceptors.size)
        handles.forEach(SseEventSource::cancel)
    }

    @Test
    fun capacityRejectsImmediatelyAndWaitsForCancelledCallTermination() {
        val calls = mutableListOf<FakeCall>()
        val factory = factory { request ->
            FakeCall(
                requestValue = request,
                enqueueAction = { call, callback -> call.callback = callback },
                notifyFailureOnCancel = false,
            ).also(calls::add)
        }
        val handles = List(64) { index ->
            factory.open(
                testSseConnection(),
                SseRequest("http://127.0.0.1/event/$index"),
                RecordingListener(),
            )
        }
        val rejected = RecordingListener()

        factory.open(
            testSseConnection(),
            SseRequest("http://127.0.0.1/event/rejected"),
            rejected,
        )

        assertEquals(64, calls.size)
        assertEquals(
            SseFailureReason.OperationRejected(),
            rejected.failures.single().reason,
        )

        handles.first().cancel()
        val stillRejected = RecordingListener()
        factory.open(
            testSseConnection(),
            SseRequest("http://127.0.0.1/event/cancel-pending"),
            stillRejected,
        )

        assertEquals(64, calls.size)
        assertEquals(
            SseFailureReason.OperationRejected(),
            stillRejected.failures.single().reason,
        )

        calls.first().callback?.onFailure(calls.first(), IOException("Canceled"))
        val admitted = RecordingListener()
        val replacement = factory.open(
            testSseConnection(),
            SseRequest("http://127.0.0.1/event/replacement"),
            admitted,
        )

        assertEquals(65, calls.size)
        assertTrue(admitted.failures.isEmpty())
        handles.drop(1).forEach(SseEventSource::cancel)
        replacement.cancel()
    }

    @Test
    fun terminalCallbacksReleaseCapacity() {
        val calls = mutableListOf<FakeCall>()
        val factory = factory { request ->
            FakeCall(request) { call, callback -> call.callback = callback }.also(calls::add)
        }
        val handles = List(64) { index ->
            factory.open(
                testSseConnection(),
                SseRequest("http://127.0.0.1/event/$index"),
                RecordingListener(),
            )
        }

        calls.first().callback?.onFailure(calls.first(), IOException("network unavailable"))
        val afterFailure = factory.open(
            testSseConnection(),
            SseRequest("http://127.0.0.1/event/after-failure"),
            RecordingListener(),
        )
        calls[1].callback?.onResponse(
            calls[1],
            response(calls[1].request(), 200, "OK", TrackingResponseBody("")),
        )
        val afterCleanClose = factory.open(
            testSseConnection(),
            SseRequest("http://127.0.0.1/event/after-close"),
            RecordingListener(),
        )

        assertEquals(66, calls.size)
        handles.drop(2).forEach(SseEventSource::cancel)
        afterFailure.cancel()
        afterCleanClose.cancel()
    }

    @Test
    fun concurrentAdmissionNeverCreatesMoreThanTheBound() {
        val calls = Collections.synchronizedList(mutableListOf<FakeCall>())
        val handles = Collections.synchronizedList(mutableListOf<SseEventSource>())
        val rejectionCount = AtomicInteger()
        val start = CountDownLatch(1)
        val factory = factory { request ->
            FakeCall(request) { call, callback -> call.callback = callback }.also(calls::add)
        }
        val threads = List(128) { index ->
            Thread {
                start.await()
                handles += factory.open(
                    testSseConnection(),
                    SseRequest("http://127.0.0.1/event/concurrent-$index"),
                    object : RecordingListener() {
                        override fun onFailure(failure: SseFailure) {
                            super.onFailure(failure)
                            if (failure.reason == SseFailureReason.OperationRejected()) {
                                rejectionCount.incrementAndGet()
                            }
                        }
                    },
                )
            }.apply(Thread::start)
        }

        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(64, calls.size)
        assertEquals(64, rejectionCount.get())
        handles.forEach(SseEventSource::cancel)
    }

    @Test
    fun synchronousEnqueueErrorReleasesCapacityAndPropagates() {
        val attempts = AtomicInteger()
        val calls = mutableListOf<FakeCall>()
        val factory = factory { request ->
            FakeCall(request) { call, callback ->
                call.callback = callback
                if (attempts.getAndIncrement() == 0) throw AssertionError("enqueue failed")
            }.also(calls::add)
        }

        assertThrows(AssertionError::class.java) {
            factory.open(
                testSseConnection(),
                SseRequest("http://127.0.0.1/event/failing-enqueue"),
                RecordingListener(),
            )
        }
        val handles = List(64) { index ->
            factory.open(
                testSseConnection(),
                SseRequest("http://127.0.0.1/event/after-enqueue-error-$index"),
                RecordingListener(),
            )
        }
        val rejected = RecordingListener()
        factory.open(
            testSseConnection(),
            SseRequest("http://127.0.0.1/event/after-capacity"),
            rejected,
        )

        assertEquals(65, calls.size)
        assertEquals(SseFailureReason.OperationRejected(), rejected.failures.single().reason)
        handles.forEach(SseEventSource::cancel)
    }

    @Test
    fun transportValueSummariesDoNotExposeUrlsCredentialsOrCauses() {
        val secret = "sse-summary-secret"
        val connection = testSseConnection().copy(
            password = secret,
            effectiveBaseUrl = "https://example.test/$secret",
        )
        val request = SseRequest("https://example.test/event?directory=$secret")
        val failure = SseFailure.from(IllegalStateException(secret))

        assertFalse(connection.toString().contains(secret))
        assertFalse(request.toString().contains(secret))
        assertFalse(failure.toString().contains(secret))
    }

    @Test
    fun explicitCancelIsThreadSafeIdempotentAndSuppressesCancelFailure() {
        lateinit var call: FakeCall
        val listener = RecordingListener()
        val factory = factory { request ->
            FakeCall(request) { fakeCall, callback ->
                fakeCall.callback = callback
            }.also { call = it }
        }
        val handle = factory.open(testSseConnection(), SseRequest("http://127.0.0.1/event"), listener)
        val start = CountDownLatch(1)
        val threads = List(8) {
            Thread {
                start.await()
                handle.cancel()
            }.apply(Thread::start)
        }

        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, call.cancelCount.get())
        assertTrue(call.isCanceled())
        assertEquals("identity", call.request().header("Accept-Encoding"))
        assertTrue(listener.failures.isEmpty())
        assertEquals(0, listener.closedCount)
    }

    @Test
    fun nonSuccessfulHttpResponseReportsTypedStatusWithoutReadingBody() {
        listOf(201, 401, 503).forEach { code ->
            lateinit var body: TrackingResponseBody
            val listener = RecordingListener()
            val factory = factory { request ->
                FakeCall(request) { call, callback ->
                    body = TrackingResponseBody("denied")
                    callback.onResponse(call, response(request, code, "status", body))
                }
            }

            factory.open(testSseConnection(), SseRequest("http://127.0.0.1/event"), listener)

            val failure = listener.failures.single()
            assertEquals(SseFailureReason.HttpStatus(code), failure.reason)
            assertEquals(0, listener.openCount)
            assertEquals(0, body.trackingSource.readCount.get())
            assertTrue(body.trackingSource.closed)
        }
    }

    @Test
    fun successfulStreamAcceptsCharsetMimeDropsPendingEofEventAndClosesBody() {
        lateinit var body: TrackingResponseBody
        val callbacks = mutableListOf<String>()
        val listener = object : SseEventListener {
            override fun onOpen() {
                callbacks += "open"
            }

            override fun onEvent(id: String?, type: String?, data: String) {
                callbacks += "event:$data"
            }

            override fun onFailure(failure: SseFailure) {
                callbacks += "failure"
            }

            override fun onClosed() {
                callbacks += "closed"
            }
        }
        val factory = factory { request ->
            FakeCall(request) { call, callback ->
                body = TrackingResponseBody(
                    "data: value",
                    "text/event-stream; charset=utf-8".toMediaType(),
                )
                callback.onResponse(
                    call,
                    response(request, 200, "OK", body, contentEncoding = "identity"),
                )
            }
        }

        factory.open(testSseConnection(), SseRequest("http://127.0.0.1/event"), listener)

        assertEquals(listOf("open", "closed"), callbacks)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun nonIdentityContentEncodingFailsBeforeOpenWithoutReadingBody() {
        listOf("gzip", "br", "identity, gzip").forEach { contentEncoding ->
            lateinit var body: TrackingResponseBody
            val listener = RecordingListener()
            val factory = factory { request ->
                FakeCall(request) { call, callback ->
                    body = TrackingResponseBody("data: hidden\n\n")
                    callback.onResponse(
                        call,
                        response(request, 200, "OK", body, contentEncoding),
                    )
                }
            }

            factory.open(testSseConnection(), SseRequest("http://127.0.0.1/event"), listener)

            assertEquals(SseFailureReason.InvalidResponse, listener.failures.single().reason)
            assertEquals(0, listener.openCount)
            assertEquals(0, body.trackingSource.readCount.get())
            assertTrue(body.trackingSource.closed)
        }
    }

    @Test
    fun missingOrWrongContentTypeFailsBeforeOpenWithoutReadingBody() {
        listOf<MediaType?>(null, "application/json".toMediaType()).forEach { contentType ->
            lateinit var body: TrackingResponseBody
            val listener = RecordingListener()
            val factory = factory { request ->
                FakeCall(request) { call, callback ->
                    body = TrackingResponseBody("data: hidden\n\n", contentType)
                    callback.onResponse(call, response(request, 200, "OK", body))
                }
            }

            factory.open(testSseConnection(), SseRequest("http://127.0.0.1/event"), listener)

            assertEquals(SseFailureReason.InvalidResponse, listener.failures.single().reason)
            assertEquals(0, listener.openCount)
            assertEquals(0, body.trackingSource.readCount.get())
            assertTrue(body.trackingSource.closed)
        }
    }

    @Test
    fun noContentIsTerminalProtocolFailureBeforeOpenWithoutReadingBody() {
        lateinit var body: TrackingResponseBody
        val listener = RecordingListener()
        val factory = factory { request ->
            FakeCall(request) { call, callback ->
                body = TrackingResponseBody("data: hidden\n\n")
                callback.onResponse(call, response(request, 204, "No Content", body))
            }
        }

        factory.open(testSseConnection(), SseRequest("http://127.0.0.1/event"), listener)

        val failure = listener.failures.single()
        assertEquals(SseFailureReason.HttpStatus(204), failure.reason)
        assertEquals(0, listener.openCount)
        assertEquals(0, body.trackingSource.readCount.get())
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun oversizedLineCancelsCallClosesBodyAndReportsTypedFailure() {
        lateinit var call: FakeCall
        lateinit var body: TrackingResponseBody
        val listener = RecordingListener()
        val factory = factory(
            reader = BoundedSseReader(maxLineBytes = 4L, maxEventDataBytes = 16L),
        ) { request ->
            FakeCall(request) { fakeCall, callback ->
                body = TrackingResponseBody("12345")
                callback.onResponse(fakeCall, response(request, 200, "OK", body))
            }.also { call = it }
        }

        factory.open(testSseConnection(), SseRequest("http://127.0.0.1/event"), listener)

        assertEquals(1, call.cancelCount.get())
        assertEquals(SseFailureReason.ResponseTooLarge, listener.failures.single().reason)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun eventCallbackExceptionStillClosesBodyBeforeFailureCallback() {
        lateinit var body: TrackingResponseBody
        val expected = IllegalStateException("callback failed")
        val listener = object : SseEventListener {
            var failure: SseFailure? = null

            override fun onOpen() = Unit

            override fun onEvent(id: String?, type: String?, data: String) {
                throw expected
            }

            override fun onFailure(failure: SseFailure) {
                assertTrue(body.trackingSource.closed)
                this.failure = failure
            }

            override fun onClosed() = Unit
        }
        val factory = factory { request ->
            FakeCall(request) { call, callback ->
                body = TrackingResponseBody("data: value\n\n")
                callback.onResponse(call, response(request, 200, "OK", body))
            }
        }

        factory.open(testSseConnection(), SseRequest("http://127.0.0.1/event"), listener)

        assertEquals(SseFailureReason.Unknown, listener.failure?.reason)
        assertTrue(listener.failure.toString().contains("callback failed").not())
    }

    @Test
    fun eventCallbackCancellationExceptionPropagatesAndClosesBody() {
        lateinit var body: TrackingResponseBody
        val expected = CancellationException("callback cancelled")
        val listener = object : SseEventListener {
            val failures = mutableListOf<SseFailure>()

            override fun onOpen() = Unit

            override fun onEvent(id: String?, type: String?, data: String) {
                throw expected
            }

            override fun onFailure(failure: SseFailure) {
                failures += failure
            }

            override fun onClosed() = Unit
        }
        val factory = factory { request ->
            FakeCall(request) { call, callback ->
                body = TrackingResponseBody("data: value\n\n")
                callback.onResponse(call, response(request, 200, "OK", body))
            }
        }

        try {
            factory.open(testSseConnection(), SseRequest("http://127.0.0.1/event"), listener)
            fail("CancellationException was not propagated")
        } catch (failure: CancellationException) {
            assertTrue(failure === expected)
        }

        assertTrue(listener.failures.isEmpty())
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun eventCallbackErrorPropagatesAfterClosingBody() {
        lateinit var body: TrackingResponseBody
        val expected = AssertionError("fatal")
        val factory = factory { request ->
            FakeCall(request) { call, callback ->
                body = TrackingResponseBody("data: value\n\n")
                callback.onResponse(call, response(request, 200, "OK", body))
            }
        }
        val listener = object : SseEventListener {
            override fun onOpen() = Unit

            override fun onEvent(id: String?, type: String?, data: String) {
                throw expected
            }

            override fun onFailure(failure: SseFailure) = Unit

            override fun onClosed() = Unit
        }

        try {
            factory.open(testSseConnection(), SseRequest("http://127.0.0.1/event"), listener)
            fail("Error was not propagated")
        } catch (failure: AssertionError) {
            assertTrue(failure === expected)
            assertTrue(body.trackingSource.closed)
        }
    }

    private fun factory(
        reader: BoundedSseReader = BoundedSseReader(maxLineBytes = 128L, maxEventDataBytes = 128L),
        createCall: (Request) -> Call,
    ): OkHttpSseEventSourceFactory = OkHttpSseEventSourceFactory(
        redactor = Redactor(),
        callFactory = { _, request -> createCall(request) },
        reader = reader,
    )

    private fun response(
        request: Request,
        code: Int,
        message: String,
        body: ResponseBody,
        contentEncoding: String? = null,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .body(body)
        .apply {
            contentEncoding?.let { header("Content-Encoding", it) }
        }
        .build()

    private open class RecordingListener : SseEventListener {
        var openCount = 0
            private set
        val failures = mutableListOf<SseFailure>()
        var closedCount = 0
            private set

        override fun onOpen() {
            openCount += 1
        }

        override fun onEvent(id: String?, type: String?, data: String) = Unit

        override fun onFailure(failure: SseFailure) {
            failures += failure
        }

        override fun onClosed() {
            closedCount += 1
        }
    }

    private class FakeCall(
        private val requestValue: Request,
        private val notifyFailureOnCancel: Boolean = true,
        private val enqueueAction: (FakeCall, Callback) -> Unit,
    ) : Call {
        val cancelCount = AtomicInteger()
        private val executed = AtomicBoolean()
        private val cancelled = AtomicBoolean()
        var callback: Callback? = null

        override fun request(): Request = requestValue

        override fun execute(): Response = throw UnsupportedOperationException()

        override fun enqueue(responseCallback: Callback) {
            executed.set(true)
            callback = responseCallback
            enqueueAction(this, responseCallback)
        }

        override fun cancel() {
            cancelCount.incrementAndGet()
            if (cancelled.compareAndSet(false, true) && notifyFailureOnCancel) {
                callback?.onFailure(this, IOException("Canceled"))
            }
        }

        override fun isExecuted(): Boolean = executed.get()

        override fun isCanceled(): Boolean = cancelled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun addEventListener(eventListener: EventListener) = Unit

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        override fun clone(): Call = FakeCall(requestValue, notifyFailureOnCancel, enqueueAction)
    }

    private class TrackingResponseBody(
        text: String,
        private val mediaType: MediaType? = "text/event-stream".toMediaType(),
    ) : ResponseBody() {
        val trackingSource = TrackingSource(Buffer().writeUtf8(text))
        private val bufferedSource = trackingSource.buffer()

        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = bufferedSource
    }

    private class TrackingSource(delegate: Source) : ForwardingSource(delegate) {
        val readCount = AtomicInteger()
        var closed = false
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            readCount.incrementAndGet()
            return super.read(sink, byteCount)
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
