package io.github.ycfeng.ocdeck.ui.component

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState

internal fun LazyListState.findVisibleReorderItem(key: Any): LazyListItemInfo? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

internal fun LazyListState.findVerticalReorderTarget(
    draggedKey: Any,
    draggedTop: Float,
    draggedBottom: Float,
    reorderableKeys: Set<Any>,
): LazyListItemInfo? {
    val draggedCenter = (draggedTop + draggedBottom) / 2f
    return layoutInfo.visibleItemsInfo.firstOrNull { item ->
        item.key != draggedKey &&
            item.key in reorderableKeys &&
            draggedCenter >= item.offset.toFloat() &&
            draggedCenter <= (item.offset + item.size).toFloat()
    }
}

internal fun LazyListItemInfo.draggedOffsetAfterVerticalMove(
    draggedItem: LazyListItemInfo,
    movingDown: Boolean,
): Float = if (movingDown) {
    offset.toFloat() + size.toFloat() - draggedItem.size.toFloat()
} else {
    offset.toFloat()
}

internal fun LazyListState.calculateVerticalReorderAutoScroll(
    draggedTop: Float,
    draggedBottom: Float,
    thresholdPx: Float,
    stepPx: Float,
): Float = calculateVerticalReorderAutoScroll(
    viewportStart = layoutInfo.viewportStartOffset.toFloat(),
    viewportEnd = layoutInfo.viewportEndOffset.toFloat(),
    draggedTop = draggedTop,
    draggedBottom = draggedBottom,
    thresholdPx = thresholdPx,
    stepPx = stepPx,
)

internal fun calculateVerticalReorderAutoScroll(
    viewportStart: Float,
    viewportEnd: Float,
    draggedTop: Float,
    draggedBottom: Float,
    thresholdPx: Float,
    stepPx: Float,
): Float {
    val effectiveThreshold = thresholdPx.coerceIn(
        minimumValue = 0f,
        maximumValue = ((viewportEnd - viewportStart) / 2f).coerceAtLeast(0f),
    )
    val topOverflow = viewportStart + effectiveThreshold - draggedTop
    val bottomOverflow = draggedBottom - (viewportEnd - effectiveThreshold)
    return when {
        topOverflow <= 0f && bottomOverflow <= 0f -> 0f
        topOverflow > bottomOverflow -> -stepPx
        bottomOverflow > topOverflow -> stepPx
        (draggedTop + draggedBottom) / 2f < (viewportStart + viewportEnd) / 2f -> -stepPx
        (draggedTop + draggedBottom) / 2f > (viewportStart + viewportEnd) / 2f -> stepPx
        else -> 0f
    }
}

internal fun <T> List<T>.moveItem(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) return this
    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
