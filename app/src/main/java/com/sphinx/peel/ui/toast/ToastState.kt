package com.sphinx.peel.ui.toast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ToastType { Info, Success, Warning, Error }

/// One toast on screen. `id` is unique per `show`, so showing the same text
/// twice restarts the auto-dismiss timer.
data class ToastMessage(
    val id: Long,
    val message: String,
    val type: ToastType,
    /// null = stays until the X is tapped.
    val durationMs: Long?,
)

/// What any screen calls to show a toast (`LocalToast.current.show(...)`).
/// One toast at a time: `show` replaces the current one. `ToastHost` (placed
/// once at the root) draws it and dismisses it after `durationMs`.
/// Ported in look from peel's iOS toast.
class ToastState {
    var current by mutableStateOf<ToastMessage?>(null)
        private set
    private var nextId = 0L

    fun show(message: String, type: ToastType = ToastType.Warning, durationMs: Long? = DEFAULT_DURATION_MS) {
        current = ToastMessage(nextId++, message, type, durationMs)
    }

    fun dismiss() {
        current = null
    }

    /// Dismisses `id` only if it is still the one showing, so a timer for a
    /// replaced toast can't close its successor.
    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }

    companion object {
        const val DEFAULT_DURATION_MS = 5000L
    }
}

val LocalToast = compositionLocalOf { ToastState() }

@Composable
fun rememberToastState(): ToastState = androidx.compose.runtime.remember { ToastState() }
