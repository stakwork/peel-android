package com.sphinx.peel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/// The one place the app touches the system clipboard. Never use it for the
/// twelve words: the app deliberately has no way to copy them.
object ClipboardUtil {
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    const val DEFAULT_AUTO_CLEAR_MS = 60_000L

    /// Pure decision: clear only if the clipboard still holds what we put there.
    fun shouldClear(current: CharSequence?, copied: String): Boolean =
        current != null && current.toString() == copied

    /// Copies [text] flagged sensitive (hides it from the paste preview on
    /// API 33+) and clears it after [autoClearMs] if nothing else replaced it.
    fun copySensitive(context: Context, label: String, text: String, autoClearMs: Long = DEFAULT_AUTO_CLEAR_MS) {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        if (autoClearMs > 0) {
            Handler(Looper.getMainLooper()).postDelayed({ clearIfUnchanged(app, text) }, autoClearMs)
        }
    }

    /// Clears the clipboard only if it still contains [text].
    fun clearIfUnchanged(context: Context, text: String) {
        val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
        } catch (e: SecurityException) {
            null
        }
        if (shouldClear(current, text)) clear(cm)
    }

    /// Reads the clipboard text (null if none/unreadable).
    fun readText(context: Context): String? {
        val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        } catch (e: SecurityException) {
            null
        }
    }

    /// Unconditionally empties the clipboard.
    fun clear(context: Context) =
        clear(context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)

    private fun clear(cm: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cm.clearPrimaryClip()
        else cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
