package io.github.ycfeng.ocdeck.frpcstcpvisitor.interop

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal object AndroidTunnelProbe {
    fun health(port: Int, concurrentTraffic: Boolean = false) {
        withDeadlineSocket(port) { socket ->
            val output = socket.getOutputStream()
            val path = if (concurrentTraffic) "/global/health/traffic" else "/global/health"
            writeRequestHead(output, "GET", path, contentLength = null)
            output.flush()
            val input = socket.getInputStream()
            val response = readResponseHead(input)
            if (response.status != 200 || !response.contentType.startsWith("application/json")) {
                throw AndroidTunnelProbeException()
            }
            val body = readExactBody(
                input,
                response.contentLength ?: throw AndroidTunnelProbeException(),
                MAXIMUM_SMALL_RESPONSE_BYTES,
            )
            if (String(body, StandardCharsets.UTF_8) != HEALTH_BODY) throw AndroidTunnelProbeException()
            expectEndOfStream(input)
        }
    }

    fun openSseSession(port: Int, path: String, expectedData: String): AndroidLiveSseSession {
        if (path != "/global/event" && path != "/event") throw AndroidTunnelProbeException()
        val connection = AndroidDeadlineSocket.open(port, LIVE_SSE_HARD_TIMEOUT_MILLIS)
        try {
            val socket = connection.socket
            val output = socket.getOutputStream()
            writeRequestHead(output, "GET", path, contentLength = null)
            output.flush()
            val response = readResponseHead(socket.getInputStream())
            if (response.status != 200 || !response.contentType.startsWith("text/event-stream") ||
                response.contentLength != null
            ) {
                throw AndroidTunnelProbeException()
            }
            return AndroidLiveSseSession(connection).also { session ->
                session.requireInitialReady(expectedData)
            }
        } catch (failure: CancellationException) {
            closeAfterFailedOpen(connection)
            throw failure
        } catch (failure: Error) {
            closeAfterFailedOpen(connection)
            throw failure
        } catch (failure: AndroidTunnelProbeException) {
            closeAfterFailedOpen(connection)
            throw failure
        } catch (_: Exception) {
            closeAfterFailedOpen(connection)
            throw AndroidTunnelProbeException()
        }
    }

    suspend fun concurrentFlowControlTraffic(
        port: Int,
        globalSse: AndroidLiveSseSession,
        projectSse: AndroidLiveSseSession,
    ) {
        val payload = deterministicBytes(ECHO_PAYLOAD_BYTES, ECHO_PAYLOAD_SEED)
        try {
            coroutineScope {
                listOf(
                    async(Dispatchers.IO) { health(port, concurrentTraffic = true) },
                    async(Dispatchers.IO) { echo(port, payload) },
                    async(Dispatchers.IO) { echo(port, payload) },
                    async(Dispatchers.IO) { largeDownload(port) },
                    async(Dispatchers.IO) { largeDownload(port) },
                ).awaitAll()
            }
            val checkpoints = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { globalSse.awaitCheckpoint(SSE_CHECKPOINT_TIMEOUT_MILLIS) },
                    async(Dispatchers.IO) { projectSse.awaitCheckpoint(SSE_CHECKPOINT_TIMEOUT_MILLIS) },
                ).awaitAll()
            }
            if (checkpoints.size != 2 || checkpoints[0] <= 0 || checkpoints[1] != checkpoints[0]) {
                throw AndroidTunnelProbeException()
            }
        } finally {
            payload.fill(0)
        }
    }

    private fun echo(port: Int, payload: ByteArray) {
        if (payload.size > MAXIMUM_ECHO_REQUEST_BYTES) throw AndroidTunnelProbeException()
        withDeadlineSocket(port) { socket ->
            val output = socket.getOutputStream()
            writeRequestHead(output, "POST", "/echo", payload.size.toLong())
            var offset = 0
            while (offset < payload.size) {
                val count = minOf(WRITE_CHUNK_BYTES, payload.size - offset)
                output.write(payload, offset, count)
                offset += count
            }
            output.flush()
            val input = socket.getInputStream()
            val response = readResponseHead(input)
            if (response.status != 200 || !response.contentType.startsWith("text/plain")) {
                throw AndroidTunnelProbeException()
            }
            val body = readExactBody(
                input,
                response.contentLength ?: throw AndroidTunnelProbeException(),
                MAXIMUM_SMALL_RESPONSE_BYTES,
            )
            val expected = "${payload.size}\n${sha256(payload)}\n"
            if (String(body, StandardCharsets.US_ASCII) != expected) throw AndroidTunnelProbeException()
            expectEndOfStream(input)
        }
    }

    private fun largeDownload(port: Int) {
        withDeadlineSocket(port) { socket ->
            val output = socket.getOutputStream()
            writeRequestHead(output, "GET", "/large", contentLength = null)
            output.flush()
            val input = socket.getInputStream()
            val response = readResponseHead(input)
            if (response.status != 200 || !response.contentType.startsWith("application/octet-stream") ||
                response.contentLength != LARGE_BODY_BYTES.toLong()
            ) {
                throw AndroidTunnelProbeException()
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BODY_BUFFER_BYTES)
            var remaining = response.contentLength
            try {
                while (remaining > 0L) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw AndroidTunnelProbeException()
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
                if (!digest.digest().contentEquals(EXPECTED_LARGE_BODY_SHA256)) {
                    throw AndroidTunnelProbeException()
                }
                expectEndOfStream(input)
            } finally {
                buffer.fill(0)
            }
        }
    }

    private fun writeRequestHead(output: OutputStream, method: String, path: String, contentLength: Long?) {
        val head = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Accept-Encoding: identity\r\n")
            append("Connection: close\r\n")
            if (contentLength != null) {
                append("Content-Type: application/octet-stream\r\n")
                append("Content-Length: $contentLength\r\n")
            }
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(head)
    }

    private fun readResponseHead(input: InputStream): HttpResponseHead {
        var headerBytes = 0
        val statusLine = readStrictCrlfLine(input, MAXIMUM_STATUS_LINE_BYTES).also {
            headerBytes += it.length + 2
        }
        val statusParts = statusLine.split(' ', limit = 3)
        if (statusParts.size < 2 || statusParts[0] != "HTTP/1.1") throw AndroidTunnelProbeException()
        val status = statusParts[1].toIntOrNull() ?: throw AndroidTunnelProbeException()
        val headers = LinkedHashMap<String, String>()
        repeat(MAXIMUM_HEADER_COUNT + 1) { index ->
            val line = readStrictCrlfLine(input, MAXIMUM_HEADER_LINE_BYTES)
            headerBytes += line.length + 2
            if (headerBytes > MAXIMUM_HEADER_BYTES) throw AndroidTunnelProbeException()
            if (line.isEmpty()) {
                if (headers.containsKey("transfer-encoding")) throw AndroidTunnelProbeException()
                val contentLength = headers["content-length"]?.let(::parseContentLength)
                val contentType = headers["content-type"]?.lowercase(Locale.ROOT)
                    ?: throw AndroidTunnelProbeException()
                return HttpResponseHead(status, contentType, contentLength)
            }
            if (index == MAXIMUM_HEADER_COUNT || line[0] == ' ' || line[0] == '\t') {
                throw AndroidTunnelProbeException()
            }
            val separator = line.indexOf(':')
            if (separator <= 0) throw AndroidTunnelProbeException()
            val name = line.substring(0, separator).lowercase(Locale.ROOT)
            if (!HTTP_HEADER_NAME.matches(name) || headers.containsKey(name)) {
                throw AndroidTunnelProbeException()
            }
            val value = line.substring(separator + 1).trim()
            if (value.any { it.code < 0x20 && it != '\t' }) throw AndroidTunnelProbeException()
            headers[name] = value
        }
        throw AndroidTunnelProbeException()
    }

    private fun readExactBody(input: InputStream, length: Long, maximumBytes: Long): ByteArray {
        if (length < 0L || length > maximumBytes || length > Int.MAX_VALUE) {
            throw AndroidTunnelProbeException()
        }
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) throw AndroidTunnelProbeException()
            offset += count
        }
        return body
    }

    private fun expectEndOfStream(input: InputStream) {
        if (input.read() != -1) throw AndroidTunnelProbeException()
    }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { byte -> "%02x".format(byte) }

    private data class HttpResponseHead(
        val status: Int,
        val contentType: String,
        val contentLength: Long?,
    )

    private inline fun <T> withDeadlineSocket(port: Int, block: (Socket) -> T): T = try {
        AndroidDeadlineSocket.open(port, PROBE_HARD_TIMEOUT_MILLIS).use { connection ->
            block(connection.socket)
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: AndroidTunnelProbeException) {
        throw failure
    } catch (_: Exception) {
        throw AndroidTunnelProbeException()
    }

    private fun deterministicBytes(size: Int, seed: Long): ByteArray =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun deterministicSha256(size: Int, seed: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val random = Random(seed)
        val buffer = ByteArray(BODY_BUFFER_BYTES)
        var remaining = size
        try {
            while (remaining > 0) {
                random.nextBytes(buffer)
                val count = minOf(buffer.size, remaining)
                digest.update(buffer, 0, count)
                remaining -= count
            }
            return digest.digest()
        } finally {
            buffer.fill(0)
        }
    }

    private fun closeAfterFailedOpen(connection: AndroidDeadlineSocket) {
        try {
            connection.close()
        } catch (_: Exception) {
            // The original open failure remains authoritative.
        }
    }

    private val HTTP_HEADER_NAME = Regex("[a-z0-9!#$%&'*+.^_`|~-]+")
    private const val HEALTH_BODY = "{\"healthy\":true,\"version\":\"interop\"}"
    private const val PROBE_HARD_TIMEOUT_MILLIS = 30_000L
    private const val LIVE_SSE_HARD_TIMEOUT_MILLIS = 240_000L
    private const val SSE_CHECKPOINT_TIMEOUT_MILLIS = 30_000L
    private const val MAXIMUM_STATUS_LINE_BYTES = 4 * 1024
    private const val MAXIMUM_HEADER_LINE_BYTES = 8 * 1024
    private const val MAXIMUM_HEADER_BYTES = 32 * 1024
    private const val MAXIMUM_HEADER_COUNT = 64
    private const val MAXIMUM_SMALL_RESPONSE_BYTES = 64L * 1024L
    private const val MAXIMUM_ECHO_REQUEST_BYTES = 2 * 1024 * 1024
    private const val WRITE_CHUNK_BYTES = 16 * 1024
    private const val BODY_BUFFER_BYTES = 16 * 1024
    private const val ECHO_PAYLOAD_BYTES = 768 * 1024 + 37
    private const val ECHO_PAYLOAD_SEED = 0x5354435056495349L
    private const val LARGE_BODY_BYTES = 768 * 1024 + 17
    private const val LARGE_BODY_SEED = 0x4f434445434bL
    private val EXPECTED_LARGE_BODY_SHA256 = deterministicSha256(LARGE_BODY_BYTES, LARGE_BODY_SEED)
}

internal class AndroidLiveSseSession(
    private val connection: AndroidDeadlineSocket,
) : AutoCloseable {
    private var totalLines = 0

    fun requireInitialReady(expectedData: String) {
        val event = readEvent(INITIAL_EVENT_TIMEOUT_MILLIS)
        if (event.name != "ready" || event.data != expectedData) throw AndroidTunnelProbeException()
    }

    fun awaitCheckpoint(timeoutMillis: Long): Int {
        val deadline = AndroidProbeDeadline.afterMillis(timeoutMillis)
        while (!deadline.isExpired()) {
            val event = readEvent(deadline.remainingMillis().coerceAtLeast(1L))
            if (event.name == "checkpoint") return parsePositiveCheckpoint(event.data)
            if (event.name != "message" || event.data.isNotEmpty()) throw AndroidTunnelProbeException()
        }
        throw AndroidTunnelProbeException()
    }

    fun awaitDisconnected(timeoutMillis: Long) {
        val deadline = AndroidProbeDeadline.afterMillis(timeoutMillis)
        val input = connection.socket.getInputStream()
        while (!deadline.isExpired()) {
            connection.throwIfTimedOut()
            connection.socket.soTimeout = minOf(
                SSE_READ_TIMEOUT_MILLIS,
                deadline.remainingMillis().coerceAtLeast(1L).toInt(),
            )
            val line = try {
                readFlexibleLine(input, MAXIMUM_SSE_LINE_BYTES)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (failure: AndroidTunnelProbeException) {
                throw failure
            } catch (_: Exception) {
                connection.throwIfTimedOut()
                return
            }
            if (line == null) return
            recordLine()
        }
        throw AndroidTunnelProbeException()
    }

    override fun close() = connection.close()

    override fun toString(): String = "AndroidLiveSseSession(open=${!connection.isClosed})"

    private fun readEvent(timeoutMillis: Long): SseEvent {
        val deadline = AndroidProbeDeadline.afterMillis(timeoutMillis)
        val input = connection.socket.getInputStream()
        var event = "message"
        val data = StringBuilder()
        while (!deadline.isExpired()) {
            connection.throwIfTimedOut()
            connection.socket.soTimeout = minOf(
                SSE_READ_TIMEOUT_MILLIS,
                deadline.remainingMillis().coerceAtLeast(1L).toInt(),
            )
            val line = try {
                readFlexibleLine(input, MAXIMUM_SSE_LINE_BYTES)
                    ?: throw AndroidTunnelProbeException()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (failure: AndroidTunnelProbeException) {
                throw failure
            } catch (_: Exception) {
                connection.throwIfTimedOut()
                throw AndroidTunnelProbeException()
            }
            recordLine()
            if (line.isEmpty()) return SseEvent(event, data.toString())
            when {
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.substringAfter(':').trimStart()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substringAfter(':').trimStart())
                    if (data.length > MAXIMUM_SSE_DATA_CHARS) throw AndroidTunnelProbeException()
                }
                else -> throw AndroidTunnelProbeException()
            }
        }
        throw AndroidTunnelProbeException()
    }

    private fun recordLine() {
        totalLines += 1
        if (totalLines > MAXIMUM_SSE_TOTAL_LINES) throw AndroidTunnelProbeException()
    }

    private data class SseEvent(
        val name: String,
        val data: String,
    )

    private companion object {
        const val INITIAL_EVENT_TIMEOUT_MILLIS = 30_000L
        const val SSE_READ_TIMEOUT_MILLIS = 500
        const val MAXIMUM_SSE_LINE_BYTES = 8 * 1024
        const val MAXIMUM_SSE_DATA_CHARS = 32 * 1024
        const val MAXIMUM_SSE_TOTAL_LINES = 8_192
    }
}

internal class AndroidTunnelProbeException : Exception()

internal class AndroidDeadlineSocket private constructor(
    val socket: Socket,
    private val timedOut: AtomicBoolean,
    private val watchdog: Thread,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    fun throwIfTimedOut() {
        if (timedOut.get()) throw AndroidTunnelProbeException()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        watchdog.interrupt()
        try {
            socket.close()
        } catch (_: Exception) {
            // Socket ownership has already been invalidated.
        }
        try {
            watchdog.join(WATCHDOG_JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AndroidTunnelProbeException()
        }
        if (watchdog.isAlive) throw AndroidTunnelProbeException()
        throwIfTimedOut()
    }

    companion object {
        fun open(port: Int, hardTimeoutMillis: Long): AndroidDeadlineSocket {
            if (port !in 1..65_535 || hardTimeoutMillis <= 0L) throw AndroidTunnelProbeException()
            val socket = try {
                Socket().apply {
                    tcpNoDelay = true
                    soTimeout = PROBE_READ_TIMEOUT_MILLIS
                    connect(InetSocketAddress(IPV4_LOOPBACK, port), PROBE_CONNECT_TIMEOUT_MILLIS)
                }
            } catch (_: Exception) {
                throw AndroidTunnelProbeException()
            }
            val timedOut = AtomicBoolean(false)
            val watchdog = Thread(
                {
                    try {
                        Thread.sleep(hardTimeoutMillis)
                        timedOut.set(true)
                        try {
                            socket.close()
                        } catch (_: Exception) {
                            // The blocked operation observes socket closure.
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                },
                "frpc-android-interop-socket-deadline",
            ).apply {
                isDaemon = true
                start()
            }
            return AndroidDeadlineSocket(socket, timedOut, watchdog)
        }

        private val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val PROBE_CONNECT_TIMEOUT_MILLIS = 2_000
        private const val PROBE_READ_TIMEOUT_MILLIS = 15_000
        private const val WATCHDOG_JOIN_TIMEOUT_MILLIS = 1_000L
    }
}

private class AndroidProbeDeadline private constructor(
    private val deadlineNanos: Long,
) {
    fun isExpired(): Boolean = System.nanoTime() - deadlineNanos >= 0L

    fun remainingMillis(): Long =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))

    companion object {
        fun afterMillis(timeoutMillis: Long): AndroidProbeDeadline {
            if (timeoutMillis <= 0L) throw AndroidTunnelProbeException()
            val durationNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            return AndroidProbeDeadline(System.nanoTime() + durationNanos)
        }
    }
}

private fun parsePositiveCheckpoint(value: String): Int {
    if (value.isEmpty() || value.length > 10 || value.any { it !in '0'..'9' }) {
        throw AndroidTunnelProbeException()
    }
    return value.toIntOrNull()?.takeIf { it > 0 } ?: throw AndroidTunnelProbeException()
}

private fun parseContentLength(value: String): Long {
    if (value.isEmpty() || value.length > 19 || value.any { it !in '0'..'9' }) {
        throw AndroidTunnelProbeException()
    }
    return value.toLongOrNull()?.takeIf { it >= 0L } ?: throw AndroidTunnelProbeException()
}

private fun readStrictCrlfLine(input: InputStream, maximumBytes: Int): String {
    val output = ByteArrayOutputStream(minOf(maximumBytes, 256))
    var previousWasCr = false
    while (true) {
        val value = input.read()
        if (value < 0) throw AndroidTunnelProbeException()
        if (previousWasCr) {
            if (value != '\n'.code) throw AndroidTunnelProbeException()
            return String(output.toByteArray(), StandardCharsets.US_ASCII)
        }
        if (value == '\r'.code) {
            previousWasCr = true
        } else {
            if (value == '\n'.code || value > 0x7f || output.size() >= maximumBytes) {
                throw AndroidTunnelProbeException()
            }
            output.write(value)
        }
    }
}

private fun readFlexibleLine(input: InputStream, maximumBytes: Int): String? {
    val output = ByteArrayOutputStream(minOf(maximumBytes, 256))
    while (true) {
        val value = input.read()
        if (value < 0) {
            if (output.size() == 0) return null
            return String(output.toByteArray(), StandardCharsets.UTF_8).removeSuffix("\r")
        }
        if (value == '\n'.code) {
            return String(output.toByteArray(), StandardCharsets.UTF_8).removeSuffix("\r")
        }
        if (output.size() >= maximumBytes) throw AndroidTunnelProbeException()
        output.write(value)
    }
}
