package io.github.ycfeng.ocdeck.frpcstcpvisitor.interop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FrpcAndroidInteropHostTest {
    @Test
    fun reverseRecoveryReturnsOnlyTheMappingAddedByThisRun() {
        val output = """
            device-serial tcp:41001 tcp:7000
            device-serial tcp:41002 tcp:7000
            device-serial tcp:41003 tcp:8000
        """.trimIndent()

        assertEquals(41_002, recoverOwnedReversePort(output, 7_000, setOf(41_001)))
        assertNull(recoverOwnedReversePort(output, 8_000, setOf(41_003)))
    }

    @Test
    fun reverseRecoveryRejectsAmbiguousNewMappings() {
        val output = """
            device-serial tcp:41001 tcp:7000
            device-serial tcp:41002 tcp:7000
        """.trimIndent()

        assertThrows(InteropFailure::class.java) {
            recoverOwnedReversePort(output, 7_000, emptySet())
        }
    }
}
