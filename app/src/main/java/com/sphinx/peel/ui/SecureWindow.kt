package com.sphinx.peel.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

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
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        SecureWindowFlag.acquire(window)
        onDispose { SecureWindowFlag.release(window) }
    }
}

internal object SecureWindowFlag {
    private var holders = 0

    fun acquire(window: Window) {
        holders++
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

internal fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
