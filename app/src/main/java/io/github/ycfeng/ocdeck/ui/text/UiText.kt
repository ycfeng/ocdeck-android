package io.github.ycfeng.ocdeck.ui.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

sealed interface UiText {
    data class Raw(val value: String) : UiText {
        override fun toString(): String = "UiText.Raw(<redacted>)"
    }

    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun toString(): String = "UiText.Resource(id=$id, argumentCount=${args.size})"
    }
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Resource -> stringResource(id, *args.toTypedArray())
}
