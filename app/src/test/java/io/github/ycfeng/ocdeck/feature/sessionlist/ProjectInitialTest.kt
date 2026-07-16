package io.github.ycfeng.ocdeck.feature.sessionlist

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectInitialTest {
    @Test
    fun keepsTheFirstUnicodeCharacterIntact() {
        assertEquals("🚀", projectInitial("🚀 launch"))
        assertEquals("É", projectInitial("éclair"))
    }

    @Test
    fun uppercasesLettersAndFallsBackForBlankNames() {
        assertEquals("O", projectInitial("opencode"))
        assertEquals("O", projectInitial("   "))
    }
}
