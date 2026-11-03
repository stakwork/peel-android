package com.sphinx.peel.account

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/// The real clipboard behind `SensitiveClipboard`, and the process-wide instance.
class AndroidClipboardAccess(context: Context) : SensitiveClipboard.ClipboardAccess {
    private val manager = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun put(label: String, text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        manager.setPrimaryClip(clip)
    }

    override fun currentLabel(): String? = runCatching { manager.primaryClipDescription?.label?.toString() }.getOrNull()

    override fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.clearPrimaryClip()
        else manager.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    /// Clears whatever is on the clipboard iff it currently holds exactly `text`
    /// (readable only in the foreground on API 29+, which is where a paste button runs).
    fun clearIfText(context: Context, text: String) {
        val current = runCatching { manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        if (current == text) clear()
    }

    companion object {
        @Volatile private var instance: SensitiveClipboard? = null

        fun sensitive(context: Context): SensitiveClipboard = instance ?: synchronized(this) {
            instance ?: SensitiveClipboard(
                AndroidClipboardAccess(context),
                { delay, task -> Handler(Looper.getMainLooper()).postDelayed(task, delay) },
            ).also { instance = it }
        }
    }
}
