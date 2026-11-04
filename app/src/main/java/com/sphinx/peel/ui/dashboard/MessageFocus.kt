package com.sphinx.peel.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/// How far to scroll so an item's centre meets the viewport's — PeelCore's
/// `scrollTo(id, anchor: .center)`. An item taller than the viewport lines
/// its top up with the viewport's instead, so its start is never cut off.
/// Positive scrolls towards the end of the list.
internal fun centerScrollDelta(itemOffset: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int): Float {
    val viewportSize = viewportEnd - viewportStart
    if (itemSize >= viewportSize) return (itemOffset - viewportStart).toFloat()
    val itemCenter = itemOffset + itemSize / 2f
    val viewportCenter = (viewportStart + viewportEnd) / 2f
    return itemCenter - viewportCenter
}

private val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/// Brings the item at `index` to the middle of the list, animated over
/// 0.25s ease-in-out (`AppModel.focus(on:)`). An item far off screen is
/// jumped to first, so it has a measured size to centre by.
internal suspend fun LazyListState.animateScrollToCenter(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index)
        withFrameNanos { }
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = centerScrollDelta(item.offset, item.size, info.viewportStartOffset, info.viewportEndOffset)
    if (delta != 0f) animateScrollBy(delta, tween(250, easing = EaseInOut))
}

/// The row a focus just landed on: `uuid` is highlighted at once with
/// `alpha` 0.45, holds for 0.5s, then fades out over 1.2s ease-out — the
/// iOS selection flash. A row reads `alpha` only when its uuid matches, so
/// the other rows do not recompose while it animates.
@Stable
internal class MessageHighlight {
    var uuid by mutableStateOf<String?>(null)
        private set
    val alpha = Animatable(0f)

    suspend fun show(target: String) {
        uuid = target
        alpha.snapTo(0.45f)
    }

    suspend fun fade() {
        delay(500)
        alpha.animateTo(0f, tween(1200, easing = EaseOut))
        uuid = null
    }

    /// What a row with this uuid should draw.
    fun alphaFor(rowUuid: String?): Float = if (rowUuid != null && rowUuid == uuid) alpha.value else 0f
}
