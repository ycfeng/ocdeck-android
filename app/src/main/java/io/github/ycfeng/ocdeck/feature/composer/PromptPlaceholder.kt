package io.github.ycfeng.ocdeck.feature.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import io.github.ycfeng.ocdeck.R
import kotlin.random.Random
import kotlinx.coroutines.delay

private const val PromptPlaceholderRotationMillis = 6_500L

@Composable
internal fun rememberPromptPlaceholderText(
    showExamples: Boolean,
    rotateExamples: Boolean,
): String {
    val examples = stringArrayResource(R.array.prompt_examples)
    var exampleIndex by remember(examples.size) {
        mutableIntStateOf(if (examples.isEmpty()) 0 else Random.nextInt(examples.size))
    }

    LaunchedEffect(showExamples, rotateExamples, examples.size) {
        if (!showExamples || !rotateExamples || examples.size <= 1) return@LaunchedEffect
        while (true) {
            delay(PromptPlaceholderRotationMillis)
            exampleIndex = (exampleIndex + 1) % examples.size
        }
    }

    if (!showExamples || examples.isEmpty()) {
        return stringResource(R.string.prompt_placeholder_simple)
    }

    return stringResource(
        R.string.prompt_placeholder_normal,
        examples[exampleIndex.coerceIn(0, examples.lastIndex)],
    )
}
