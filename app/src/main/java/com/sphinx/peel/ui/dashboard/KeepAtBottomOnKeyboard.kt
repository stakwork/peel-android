package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

/// Keeps the latest message visible when the keyboard opens: if the list was
/// at the bottom while the IME was hidden, scroll to [lastIndex] as the IME
/// opens (and again as it animates, since the list viewport keeps shrinking).
/// The "was at bottom" snapshot is only updated while the IME is fully hidden,
/// so the keyboard's own layout shrink can't overwrite it.
@Composable
internal fun KeepAtBottomOnKeyboard(listState: LazyListState, lastIndex: Int) {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val currentLast by rememberUpdatedState(lastIndex)
    LaunchedEffect(listState, ime, density) {
        var wasAtBottom = false
        snapshotFlow { ime.getBottom(density) to !listState.canScrollForward }.collect { (imeBottom, atBottom) ->
            if (imeBottom == 0) {
                wasAtBottom = atBottom
            } else if (wasAtBottom && currentLast >= 0) {
                listState.scrollToItem(currentLast)
            }
        }
    }
}
