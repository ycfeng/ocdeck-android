package io.github.ycfeng.ocdeck.feature.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFilePreviewLimitsTest {
    @Test
    fun acceptsOrdinaryText() {
        assertTrue(isProjectFileTextPreviewable("fun main() {\n    println(\"hello\")\n}"))
    }

    @Test
    fun rejectsExcessiveCharactersLinesAndLineLength() {
        assertFalse(isProjectFileTextPreviewable("a".repeat(500_001)))
        assertFalse(isProjectFileTextPreviewable("\n".repeat(20_000)))
        assertFalse(isProjectFileTextPreviewable("a".repeat(20_001)))
    }
}
