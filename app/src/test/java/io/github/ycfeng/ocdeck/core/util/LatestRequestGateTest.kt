package io.github.ycfeng.ocdeck.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRequestGateTest {
    @Test
    fun onlyLatestRequestRemainsCurrent() {
        val gate = LatestRequestGate()
        val first = gate.begin()

        assertTrue(gate.isCurrent(first))

        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
