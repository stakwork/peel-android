package com.sphinx.peel.diagnostics

import android.content.Context
import android.content.Intent
import com.sphinx.peel.account.AndroidClipboardAccess
import com.sphinx.peel.account.SensitiveClipboard

/// Copy and Share for the Diagnostics screen and the problem record (iOS: `Clipboard.copy` and
/// `ShareLink`). Both are the person's own act; nothing is sent automatically.
object DiagnosticsExport {
    /// The record is redacted but still personal: it goes through `SensitiveClipboard` (hidden from the
    /// clipboard preview on API 33+) and comes off again after [CLEAR_AFTER_MS], long enough to paste.
    const val CLEAR_AFTER_MS = 5 * 60_000L

    fun copy(context: Context, text: String) {
        AndroidClipboardAccess.sensitive(context).copy(text, CLEAR_AFTER_MS)
    }

    /// The system share sheet with the text (iOS's `ShareLink(item: text)`).
    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Peel diagnostics")
        }
        val chooser = Intent.createChooser(send, null)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /// The Diagnostics screen's text: the time, how the last run ended, then the newest `limit` events.
    fun screenText(limit: Int = EVENT_LIMIT, now: Long = System.currentTimeMillis(), report: DiagnosticsReport = Diagnostics.snapshot()): String =
        "Now: ${DiagnosticEvent.isoTime(now)}\n" + report.rendered(limit)

    const val EVENT_LIMIT = 500
}
