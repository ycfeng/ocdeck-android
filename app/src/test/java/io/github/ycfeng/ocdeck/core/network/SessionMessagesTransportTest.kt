package io.github.ycfeng.ocdeck.core.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.KClass

class SessionMessagesTransportTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun nonSuccessfulResponsesCloseHugeAndInfiniteBodiesWithoutReading() = runTest {
        listOf(404, 503).forEach { code ->
            lateinit var body: SourceResponseBody
            val harness = harness(maxBytes = 2L) { call, callback ->
                body = SourceResponseBody(
                    source = if (code == 404) FailOnReadSource() else InfiniteSource(),
                    declaredLength = if (code == 404) Long.MAX_VALUE else -1L,
                )
                callback.onResponse(call, response(call.request(), code, body))
            }

            val failure = runCatching {
                harness.transport.load("session", "E:/work/app", null)
            }.exceptionOrNull()

            assertTrue(failure is SessionMessagesHttpException)
            assertEquals(code, (failure as SessionMessagesHttpException).statusCode)
            assertTrue(failure.message == null)
            assertEquals(0, body.trackingSource.readCount.get())
            assertTrue(body.trackingSource.closed.get())
        }
    }

    @Test
    fun twoHundredResponseAllowsExactWireLimit() = runTest {
        lateinit var body: ByteArrayResponseBody
        val harness = harness(maxBytes = 2L) { call, callback ->
            body = ByteArrayResponseBody("[]".encodeToByteArray(), declaredLength = 2L)
            callback.onResponse(call, response(call.request(), 200, body))
        }

        val messages = harness.transport.load("session", "E:/work/app", null)

        assertTrue(messages.isEmpty())
        assertTrue(body.trackingSource.closed.get())
    }

    @Test
    fun twoHundredResponseRejectsUnknownLengthAtMaxPlusOne() = runTest {
        lateinit var body: ByteArrayResponseBody
        val harness = harness(maxBytes = 2L) { call, callback ->
            body = ByteArrayResponseBody("[] ".encodeToByteArray(), declaredLength = -1L)
            callback.onResponse(call, response(call.request(), 200, body))
        }

        val failure = runCatching {
            harness.transport.load("session", "E:/work/app", null)
        }.exceptionOrNull()

        assertTrue(failure is SessionMessagesResponseTooLargeException)
        assertEquals(3L, body.trackingSource.bytesRead.get())
        assertTrue(body.trackingSource.closed.get())
    }

    @Test
    fun twoHundredResponseRejectsOversizedDeclaredLengthWithoutReading() = runTest {
        lateinit var body: SourceResponseBody
        val harness = harness(maxBytes = 2L) { call, callback ->
            body = SourceResponseBody(FailOnReadSource(), declaredLength = 3L)
            callback.onResponse(call, response(call.request(), 200, body))
        }

        val failure = runCatching {
            harness.transport.load("session", "E:/work/app", null)
        }.exceptionOrNull()

        assertTrue(failure is SessionMessagesResponseTooLargeException)
        assertEquals(0, body.trackingSource.readCount.get())
        assertTrue(body.trackingSource.closed.get())
    }

    @Test
    fun understatedContentLengthCannotBypassStreamingDecode() = runTest {
        lateinit var body: ByteArrayResponseBody
        val harness = harness(maxBytes = 4_096L) { call, callback ->
            body = ByteArrayResponseBody(messageJson.encodeToByteArray(), declaredLength = 1L)
            callback.onResponse(call, response(call.request(), 200, body))
        }

        val messages = harness.transport.load("session", "E:/work/app", null)

        assertEquals("message-1", messages.single().info.id)
        assertTrue(body.trackingSource.bytesRead.get() > 1L)
        assertTrue(body.trackingSource.closed.get())
    }

    @Test
    fun unknownLengthDecodesAndBuildsEncodedRequestWithFixedLimit() = runTest {
        lateinit var body: ByteArrayResponseBody
        val harness = harness(maxBytes = 4_096L) { call, callback ->
            body = ByteArrayResponseBody(messageJson.encodeToByteArray(), declaredLength = -1L)
            callback.onResponse(call, response(call.request(), 200, body))
        }

        val messages = harness.transport.load("session/id", "E:/work with space/app", "workspace/one")
        val request = harness.factory.calls.single().request()

        assertEquals("message-1", messages.single().info.id)
        assertEquals("/api/session/session%2Fid/message", request.url.encodedPath)
        assertEquals("E:/work with space/app", request.url.queryParameter("directory"))
        assertEquals("workspace/one", request.url.queryParameter("workspace"))
        assertEquals("200", request.url.queryParameter("limit"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals(4_096L, request.tag(EncodedResponseLimit::class.java)?.maxBytes)
    }

    @Test
    fun cancellationDuringBlockedReadClosesBodyOnCallbackThreadWithoutReplacingCancellation() = runBlocking {
        val blockingSource = BlockingSource()
        lateinit var body: SourceResponseBody
        val callbackFinished = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread?>()
        val cancellingThread = Thread.currentThread()
        val harness = harness(maxBytes = 4_096L) { call, callback ->
            body = SourceResponseBody(blockingSource, declaredLength = -1L)
            call.onCancel = blockingSource::cancel
            Thread {
                callbackThread.set(Thread.currentThread())
                try {
                    callback.onResponse(call, response(call.request(), 200, body))
                } finally {
                    callbackFinished.countDown()
                }
            }.start()
        }
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            harness.transport.load("session", "E:/work/app", null)
        }

        assertTrue(blockingSource.readStarted.await(5, TimeUnit.SECONDS))
        deferred.cancel()
        try {
            deferred.await()
            fail("CancellationException was not propagated")
        } catch (_: CancellationException) {
        }

        assertTrue(blockingSource.closed.await(5, TimeUnit.SECONDS))
        assertTrue(callbackFinished.await(5, TimeUnit.SECONDS))
        assertEquals(1, harness.factory.calls.single().cancelCount.get())
        assertTrue(harness.factory.calls.single().isCanceled())
        assertTrue(body.trackingSource.closed.get())
        assertEquals(callbackThread.get(), body.trackingSource.closedBy.get())
        assertFalse(body.trackingSource.closedBy.get() === cancellingThread)
    }

    @Test
    fun competingCallbacksCompleteContinuationOnceAndCloseLosingResponse() = runTest {
        lateinit var losingBody: ByteArrayResponseBody
        val expected = IOException("first")
        val harness = harness(maxBytes = 16L) { call, callback ->
            callback.onFailure(call, expected)
            losingBody = ByteArrayResponseBody("[]".encodeToByteArray(), declaredLength = 2L)
            callback.onResponse(call, response(call.request(), 200, losingBody))
        }

        val failure = runCatching {
            harness.transport.load("session", "E:/work/app", null)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(expected.message, failure?.message)
        assertEquals(0, losingBody.trackingSource.readCount.get())
        assertTrue(losingBody.trackingSource.closed.get())

        val lateFailure = IOException("late")
        val successHarness = harness(maxBytes = 2L) { call, callback ->
            callback.onResponse(
                call,
                response(call.request(), 200, ByteArrayResponseBody("[]".encodeToByteArray(), 2L)),
            )
            callback.onFailure(call, lateFailure)
        }

        assertTrue(successHarness.transport.load("session", "E:/work/app", null).isEmpty())
    }

    private fun harness(
        maxBytes: Long,
        enqueueAction: (FakeCall, Callback) -> Unit,
    ): Harness {
        val factory = FakeCallFactory { request -> FakeCall(request, enqueueAction) }
        return Harness(
            transport = OkHttpSessionMessagesTransport(
                baseUrl = "http://127.0.0.1:4096/api",
                callFactory = factory,
                json = json,
                maxDecodedBytes = maxBytes,
                maxEncodedBytes = maxBytes,
            ),
            factory = factory,
        )
    }

    private fun response(request: Request, code: Int, body: ResponseBody): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status")
        .body(body)
        .build()

    private class Harness(
        val transport: OkHttpSessionMessagesTransport,
        val factory: FakeCallFactory,
    )

    private class FakeCallFactory(
        private val createCall: (Request) -> FakeCall,
    ) : Call.Factory {
        val calls = mutableListOf<FakeCall>()

        override fun newCall(request: Request): Call = createCall(request).also(calls::add)
    }

    private class FakeCall(
        private val requestValue: Request,
        private val enqueueAction: (FakeCall, Callback) -> Unit,
    ) : Call {
        val cancelCount = AtomicInteger()
        private val executed = AtomicBoolean()
        private val cancelled = AtomicBoolean()
        private var callback: Callback? = null
        var onCancel: () -> Unit = {}

        override fun request(): Request = requestValue

        override fun execute(): Response = throw UnsupportedOperationException()

        override fun enqueue(responseCallback: Callback) {
            executed.set(true)
            callback = responseCallback
            enqueueAction(this, responseCallback)
        }

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                cancelCount.incrementAndGet()
                onCancel()
                callback?.onFailure(this, IOException("cancelled"))
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

        override fun clone(): Call = FakeCall(requestValue, enqueueAction)
    }

    private open class SourceResponseBody(
        source: Source,
        private val declaredLength: Long,
    ) : ResponseBody() {
        val trackingSource = TrackingSource(source)
        private val bufferedSource = trackingSource.buffer()

        override fun contentType(): MediaType = "application/json".toMediaType()

        override fun contentLength(): Long = declaredLength

        override fun source(): BufferedSource = bufferedSource
    }

    private class ByteArrayResponseBody(
        bytes: ByteArray,
        declaredLength: Long,
    ) : SourceResponseBody(Buffer().write(bytes), declaredLength)

    private class TrackingSource(delegate: Source) : ForwardingSource(delegate) {
        val readCount = AtomicInteger()
        val bytesRead = java.util.concurrent.atomic.AtomicLong()
        val closed = AtomicBoolean()
        val closedBy = AtomicReference<Thread?>()

        override fun read(sink: Buffer, byteCount: Long): Long {
            readCount.incrementAndGet()
            return super.read(sink, byteCount).also { count ->
                if (count > 0L) bytesRead.addAndGet(count)
            }
        }

        override fun close() {
            closed.set(true)
            closedBy.compareAndSet(null, Thread.currentThread())
            super.close()
        }
    }

    private class FailOnReadSource : Source {
        override fun read(sink: Buffer, byteCount: Long): Long = throw AssertionError("Response body was read")

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    private class InfiniteSource : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            sink.writeByte(0)
            return 1L
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    private class BlockingSource : Source {
        val readStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val cancelled = CountDownLatch(1)

        override fun read(sink: Buffer, byteCount: Long): Long {
            readStarted.countDown()
            if (!cancelled.await(5, TimeUnit.SECONDS)) throw IOException("cancel timed out")
            throw IOException("cancelled")
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed.countDown()
        }

        fun cancel() {
            cancelled.countDown()
        }
    }

    private companion object {
        val messageJson = """
            [{
              "info": {
                "id": "message-1",
                "sessionID": "session-1",
                "role": "user",
                "futureInfo": true
              },
              "parts": [{
                "id": "part-1",
                "messageID": "message-1",
                "sessionID": "session-1",
                "type": "text",
                "text": "hello",
                "futurePart": 1
              }],
              "futureEnvelope": {}
            }]
        """.trimIndent()
    }
}
