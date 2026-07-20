package io.github.ycfeng.ocdeck.frpcstcpvisitor.interop

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test

class SyntheticOpenCodeServerTest {
    @Test
    fun checkpointPublishedAfterInitialEventFlushIsNotLost() {
        val published = AtomicBoolean(false)
        lateinit var server: SyntheticOpenCodeServer
        server = SyntheticOpenCodeServer(
            afterInitialSseEventFlushed = {
                if (published.compareAndSet(false, true)) server.publishSseCheckpoint()
            },
        )
        try {
            TunnelHttpProbe.openSseSession(server.port, "/global/event", "{\"scope\":\"global\"}").use { session ->
                session.awaitCheckpoint(expectedCheckpoint = 1, timeoutMillis = 5_000L)
            }
            server.requireHealthy()
        } finally {
            server.close()
        }
    }

    @Test
    fun concurrentHealthParticipatesInTheTrafficGate() {
        val server = SyntheticOpenCodeServer()
        val gate = server.beginConcurrentTraffic(expectedRequests = 1)
        try {
            TunnelHttpProbe.health(server.port, concurrentTraffic = true)
            gate.requireSatisfied()
            server.requireHealthy()
        } finally {
            server.endConcurrentTraffic(gate)
            server.close()
        }
    }
}
