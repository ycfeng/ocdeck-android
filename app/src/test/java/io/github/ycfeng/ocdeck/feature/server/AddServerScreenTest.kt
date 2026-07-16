package io.github.ycfeng.ocdeck.feature.server

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test

class AddServerScreenTest {
    @Test
    fun passwordFieldDefaultsToPasswordKeyboardType() {
        assertEquals(
            KeyboardType.Password,
            resolveServerFieldKeyboardType(password = true, keyboardType = null),
        )
    }

    @Test
    fun explicitKeyboardTypeTakesPrecedence() {
        assertEquals(
            KeyboardType.NumberPassword,
            resolveServerFieldKeyboardType(
                password = true,
                keyboardType = KeyboardType.NumberPassword,
            ),
        )
    }

    @Test
    fun regularFieldDefaultsToTextKeyboardType() {
        assertEquals(
            KeyboardType.Text,
            resolveServerFieldKeyboardType(password = false, keyboardType = null),
        )
    }
}
