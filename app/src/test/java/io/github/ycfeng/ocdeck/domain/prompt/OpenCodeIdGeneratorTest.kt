package io.github.ycfeng.ocdeck.domain.prompt

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeIdGeneratorTest {
    @Test
    fun messageIdsUseOpenCodeAscendingShape() {
        val first = OpenCodeIdGenerator.messageId()
        val second = OpenCodeIdGenerator.messageId()

        assertTrue(first.matches(Regex("^msg_[0-9a-f]{12}[0-9A-Za-z]{14}$")))
        assertTrue(second.matches(Regex("^msg_[0-9a-f]{12}[0-9A-Za-z]{14}$")))
        assertNotEquals(first, second)
        assertTrue(first < second)
    }

    @Test
    fun partIdsUseOpenCodeAscendingShape() {
        val first = OpenCodeIdGenerator.partId()
        val second = OpenCodeIdGenerator.partId()

        assertTrue(first.matches(Regex("^prt_[0-9a-f]{12}[0-9A-Za-z]{14}$")))
        assertTrue(second.matches(Regex("^prt_[0-9a-f]{12}[0-9A-Za-z]{14}$")))
        assertNotEquals(first, second)
        assertTrue(first < second)
    }
}
