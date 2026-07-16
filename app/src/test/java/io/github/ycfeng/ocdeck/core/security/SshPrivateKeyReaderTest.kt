package io.github.ycfeng.ocdeck.core.security

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SshPrivateKeyReaderTest {
    @Test
    fun declaredOversizeFailsBeforeOpeningStream() = runTest {
        var opened = false

        val failure = runCatching {
            SshPrivateKeyReader.readUtf8(SshPrivateKeyLimits.MAX_BYTES + 1L) {
                opened = true
                ByteArrayInputStream(byteArrayOf())
            }
        }.exceptionOrNull()

        assertTrue(failure is SshPrivateKeyTooLargeException)
        assertFalse(opened)
    }

    @Test
    fun unknownSizeReadsExactLimitAndClosesStream() = runTest {
        val closed = AtomicBoolean(false)
        val bytes = ByteArray(SshPrivateKeyLimits.MAX_BYTES) { 'a'.code.toByte() }

        val result = SshPrivateKeyReader.readUtf8(declaredSizeBytes = null) {
            ClosingByteArrayInputStream(bytes, closed)
        }

        assertEquals(SshPrivateKeyLimits.MAX_BYTES, result.length)
        assertTrue(closed.get())
    }

    @Test
    fun unknownSizeRejectsLimitPlusOneAndClosesStream() = runTest {
        val closed = AtomicBoolean(false)
        val bytes = ByteArray(SshPrivateKeyLimits.MAX_BYTES + 1) { 'a'.code.toByte() }

        val failure = runCatching {
            SshPrivateKeyReader.readUtf8(declaredSizeBytes = null) {
                ClosingByteArrayInputStream(bytes, closed)
            }
        }.exceptionOrNull()

        assertTrue(failure is SshPrivateKeyTooLargeException)
        assertTrue(closed.get())
    }

    @Test
    fun utf8SizeValidationCountsMultibyteCharacters() {
        val exact = "\u00e9".repeat(SshPrivateKeyLimits.MAX_BYTES / 2)

        SshPrivateKeyLimits.requireValidUtf8Size(exact)
        assertThrows(SshPrivateKeyTooLargeException::class.java) {
            SshPrivateKeyLimits.requireValidUtf8Size(exact + "a")
        }
    }

    @Test
    fun cancellationClosesStream() = runTest {
        val closed = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val input = object : InputStream() {
            override fun read(): Int {
                started.set(true)
                Thread.yield()
                return 'a'.code
            }

            override fun close() {
                closed.set(true)
            }
        }
        val reading = async(Dispatchers.Default) {
            SshPrivateKeyReader.readUtf8(declaredSizeBytes = null, dispatcher = Dispatchers.Default) { input }
        }
        while (!started.get()) Thread.yield()

        reading.cancelAndJoin()

        assertTrue(closed.get())
    }

    private class ClosingByteArrayInputStream(
        bytes: ByteArray,
        private val closed: AtomicBoolean,
    ) : ByteArrayInputStream(bytes) {
        override fun close() {
            closed.set(true)
            super.close()
        }
    }
}
