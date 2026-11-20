package com.sphinx.peel.ui.lock

import android.content.Context
import com.sphinx.peel.R

/// `LockoutPolicy.describe` in the device language: the same rounding ("30 seconds", "5 minutes", "1 hour",
/// rounded up), the unit a plural resource. The pure English `describe` stays for the store's own messages.
object LockoutText {
    fun describe(context: Context, ms: Long): String {
        val seconds = (ms + 999) / 1000
        val res = context.resources
        return when {
            seconds < 60 -> res.getQuantityString(R.plurals.duration_seconds, seconds.toInt(), seconds.toInt())
            seconds < 3600 -> ((seconds + 59) / 60).toInt().let { res.getQuantityString(R.plurals.duration_minutes, it, it) }
            else -> ((seconds + 3599) / 3600).toInt().let { res.getQuantityString(R.plurals.duration_hours, it, it) }
        }
    }
}
