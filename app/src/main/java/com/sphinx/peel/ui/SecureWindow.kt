package com.sphinx.peel.ui

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

internal fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/// Marks the window FLAG_SECURE while the composable is in the tree: no
/// screenshots, no screen recording, and a blank thumbnail in Recents.
/// Reference-counted, because the lock screen can be replaced by a screen
/// that also asks for it in the same frame (the flag must not flicker off).
///
/// Decision (docs/phases/17-lock-hardening.md): FLAG_SECURE on every screen
/// that shows or takes a secret — lock/PIN, onboarding (words, PIN, restore),
/// Twelve words, Change PIN, biometric setup. NOT on the whole app: chats are
/// often screenshotted on purpose; instead the Recents thumbnail alone is
/// hidden app-wide on API 33+ (`MainActivity`).
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
