package io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

internal const val FRP_RELAY_BUFFER_SIZE = 16 * 1024

internal class FrpRelayBufferPool(
    private val bufferSize: Int,
    capacity: Int,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val borrowCursor = AtomicInteger(0)
    private val releaseCursor = AtomicInteger(0)
    private val buffers = AtomicReferenceArray<ByteArray?>(capacity)

    init {
        require(bufferSize > 0)
        require(capacity > 0)
    }

    suspend fun <T> withBuffer(block: suspend (ByteArray) -> T): T {
        val buffer = borrow()
        try {
            return block(buffer)
        } finally {
            release(buffer)
        }
    }

    internal val retainedCount: Int
        get() = (0 until buffers.length()).count { buffers.get(it) != null }

    internal val isClosed: Boolean
        get() = closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (index in 0 until buffers.length()) {
            buffers.getAndSet(index, null)
        }
    }

    override fun toString(): String =
        "FrpRelayBufferPool(closed=${closed.get()}, retainedCount=$retainedCount, capacity=${buffers.length()})"

    internal fun borrow(): ByteArray {
        if (!closed.get()) {
            val start = Math.floorMod(borrowCursor.getAndIncrement(), buffers.length())
            repeat(buffers.length()) { offset ->
                val index = (start + offset) % buffers.length()
                val buffer = buffers.get(index) ?: return@repeat
                if (buffers.compareAndSet(index, buffer, null)) return buffer
            }
        }
        return ByteArray(bufferSize)
    }

    internal fun release(buffer: ByteArray) {
        buffer.fill(0)
        if (buffer.size != bufferSize || closed.get()) return

        val start = Math.floorMod(releaseCursor.getAndIncrement(), buffers.length())
        repeat(buffers.length()) { offset ->
            val index = (start + offset) % buffers.length()
            if (!buffers.compareAndSet(index, null, buffer)) return@repeat
            if (closed.get()) buffers.compareAndSet(index, buffer, null)
            return
        }
    }
}
