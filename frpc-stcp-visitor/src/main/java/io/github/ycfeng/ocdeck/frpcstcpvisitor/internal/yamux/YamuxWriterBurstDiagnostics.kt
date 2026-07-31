package io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.yamux

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object YamuxWriterBurstDiagnostics {
    private val enabled = AtomicBoolean(false)
    private val dataSelections = AtomicLong(0L)
    private val dataSelectionsWithQueuedSuccessor = AtomicLong(0L)
    private val dataWireBytes = AtomicLong(0L)
    private val controlSelections = AtomicLong(0L)
    private val terminalSelections = AtomicLong(0L)

    val isEnabled: Boolean
        get() = enabled.get()

    fun enableAndSnapshot(): Map<String, Long> {
        enabled.set(true)
        return snapshot()
    }

    fun recordDataSelection(hasQueuedSuccessor: Boolean, wireBytes: Long) {
        dataSelections.incrementAndGet()
        if (hasQueuedSuccessor) dataSelectionsWithQueuedSuccessor.incrementAndGet()
        dataWireBytes.addAndGet(wireBytes)
    }

    fun recordControlSelection() {
        controlSelections.incrementAndGet()
    }

    fun recordTerminalSelection() {
        terminalSelections.incrementAndGet()
    }

    internal fun snapshot(): Map<String, Long> = linkedMapOf(
        DATA_SELECTIONS_KEY to dataSelections.get(),
        DATA_SELECTIONS_WITH_QUEUED_SUCCESSOR_KEY to dataSelectionsWithQueuedSuccessor.get(),
        DATA_WIRE_BYTES_KEY to dataWireBytes.get(),
        CONTROL_SELECTIONS_KEY to controlSelections.get(),
        TERMINAL_SELECTIONS_KEY to terminalSelections.get(),
    )

    internal fun resetForTest(enabled: Boolean = false) {
        dataSelections.set(0L)
        dataSelectionsWithQueuedSuccessor.set(0L)
        dataWireBytes.set(0L)
        controlSelections.set(0L)
        terminalSelections.set(0L)
        this.enabled.set(enabled)
    }

    internal const val DATA_SELECTIONS_KEY = "kotlin.yamux.writer.data.selections"
    internal const val DATA_SELECTIONS_WITH_QUEUED_SUCCESSOR_KEY =
        "kotlin.yamux.writer.data.selections_with_queued_successor"
    internal const val DATA_WIRE_BYTES_KEY = "kotlin.yamux.writer.data.wire_bytes"
    internal const val CONTROL_SELECTIONS_KEY = "kotlin.yamux.writer.control.selections"
    internal const val TERMINAL_SELECTIONS_KEY = "kotlin.yamux.writer.terminal.selections"
}
