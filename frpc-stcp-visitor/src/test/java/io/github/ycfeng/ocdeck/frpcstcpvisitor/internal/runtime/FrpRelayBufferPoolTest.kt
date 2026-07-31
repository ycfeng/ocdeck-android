package io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.runtime

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpRelayBufferPoolTest {
    @Test
    fun emptyAndExhaustedPoolReturnFixedBuffersWithoutWaiting() = runBlocking {
        val pool = FrpRelayBufferPool(bufferSize = TEST_BUFFER_SIZE, capacity = 1)
        lateinit var first: ByteArray
        lateinit var second: ByteArray

        pool.withBuffer { borrowedFirst ->
            first = borrowedFirst
            pool.withBuffer { borrowedSecond ->
                second = borrowedSecond
                assertEquals(TEST_BUFFER_SIZE, borrowedSecond.size)
                assertNotSame(borrowedFirst, borrowedSecond)
            }
        }

        assertEquals(TEST_BUFFER_SIZE, first.size)
        assertEquals(1, pool.retainedCount)
    }

    @Test
    fun releaseClearsEveryByteBeforeReborrow() = runBlocking {
        val pool = FrpRelayBufferPool(bufferSize = TEST_BUFFER_SIZE, capacity = 1)
        lateinit var released: ByteArray

        pool.withBuffer { buffer ->
            released = buffer
            buffer.fill(0x5a)
        }

        assertTrue(released.all { it == 0.toByte() })
        pool.withBuffer { buffer ->
            assertSame(released, buffer)
            assertTrue(buffer.all { it == 0.toByte() })
        }
    }

    @Test
    fun fullAndClosedPoolClearDiscardedBuffers() = runBlocking {
        val pool = FrpRelayBufferPool(bufferSize = TEST_BUFFER_SIZE, capacity = 1)
        lateinit var outer: ByteArray
        lateinit var inner: ByteArray

        pool.withBuffer { first ->
            outer = first
            first.fill(0x11)
            pool.withBuffer { second ->
                inner = second
                second.fill(0x22)
            }
        }

        assertTrue(outer.all { it == 0.toByte() })
        assertTrue(inner.all { it == 0.toByte() })
        pool.close()
        assertEquals(0, pool.retainedCount)

        lateinit var afterClose: ByteArray
        pool.withBuffer { buffer ->
            afterClose = buffer
            buffer.fill(0x33)
        }
        assertTrue(afterClose.all { it == 0.toByte() })
        assertEquals(0, pool.retainedCount)
    }

    @Test
    fun concurrentBorrowersNeverShareOneBuffer() = runBlocking {
        val pool = FrpRelayBufferPool(bufferSize = TEST_BUFFER_SIZE, capacity = 2)
        val release = CompletableDeferred<Unit>()
        val allBorrowed = CompletableDeferred<Unit>()
        val borrowerCount = java.util.concurrent.atomic.AtomicInteger(0)
        val borrowed = Collections.synchronizedSet(
            Collections.newSetFromMap(IdentityHashMap<ByteArray, Boolean>()),
        )
        val tasks = List(8) {
            async(Dispatchers.Default) {
                pool.withBuffer { buffer ->
                    assertTrue(borrowed.add(buffer))
                    if (borrowerCount.incrementAndGet() == 8) allBorrowed.complete(Unit)
                    try {
                        release.await()
                    } finally {
                        assertTrue(borrowed.remove(buffer))
                    }
                }
            }
        }

        withTimeout(5_000L) { allBorrowed.await() }
        release.complete(Unit)
        tasks.awaitAll()

        assertTrue(borrowed.isEmpty())
        assertEquals(2, pool.retainedCount)
    }

    @Test
    fun closeIsIdempotentAndToStringIsStructural() = runBlocking {
        val pool = FrpRelayBufferPool(bufferSize = TEST_BUFFER_SIZE, capacity = 2)
        pool.withBuffer { it.fill(0x6b) }

        pool.close()
        pool.close()

        assertTrue(pool.isClosed)
        assertEquals(0, pool.retainedCount)
        assertFalse(pool.toString().contains("kkkk"))
        assertTrue(pool.toString().contains("closed=true"))
        assertTrue(pool.toString().contains("capacity=2"))
    }

    private companion object {
        const val TEST_BUFFER_SIZE = 128
    }
}
