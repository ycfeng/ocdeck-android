package io.github.ycfeng.ocdeck.ui.component

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val LocalPlatformActionModeActive = staticCompositionLocalOf { false }

internal class ActiveSourceTracker<T : Any> {
    private val sources = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private val mutableIsActive = MutableStateFlow(false)

    val isActive: StateFlow<Boolean> = mutableIsActive.asStateFlow()

    fun begin(source: T) {
        if (sources.add(source)) mutableIsActive.value = true
    }

    fun end(source: T) {
        if (sources.remove(source)) mutableIsActive.value = sources.isNotEmpty()
    }
}
