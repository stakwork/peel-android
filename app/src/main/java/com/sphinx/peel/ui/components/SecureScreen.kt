package com.sphinx.peel.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/// Counts how many composables currently want the window secure, so that
/// nested or overlapping uses (a PIN prompt inside the words screen) don't
/// clear the flag while another still needs it. Pure: no Android types.
class SecureRefCount {
    private var count = 0

    /// Returns true when the flag must now be set.
    @Synchronized
    fun acquire(): Boolean = (++count == 1)

    /// Returns true when the flag must now be cleared.
    @Synchronized
    fun release(): Boolean {
        if (count == 0) return false
        return (--count == 0)
    }

    val active: Int @Synchronized get() = count
}

private val refCount = SecureRefCount()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/// Marks the window FLAG_SECURE while this is in the composition: blocks
/// screenshots, screen recording and the recents thumbnail for the screens
/// that show or take the twelve words or the PIN. Ref-counted.
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            if (refCount.acquire()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (window != null && refCount.release()) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/// Wraps sensitive content in [SecureWindow].
@Composable
fun SecureScreen(content: @Composable () -> Unit) {
    SecureWindow()
    content()
}
