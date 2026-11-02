package com.sphinx.peel.push

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.sphinx.peel.engine.ATTACHMENT_MESSAGE_TYPE
import com.sphinx.peel.engine.CALL_MESSAGE_TYPE
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Storage
import org.json.JSONObject

/// Decides what notifies, from the messages the live MQTT connection
/// receives while the app is in the background (or its screen is off).
/// PeelCore's `NotificationPolicy`, trimmed: someone else's message or
/// attachment, a payment request (type 2), sats received (type 5) and a
/// call someone just started (type 32), each past the chat's read marker
/// and not muted. Not ported: mention-only mute levels (any mute is quiet),
/// the "more than three at once become a summary" gathering, and tribe
/// join requests. Nothing for a restore's history: only messages stamped
/// in the last two minutes notify.
class MessageNotifier(private val context: Context, private val storage: Storage, private val engine: Engine) {
    fun attach() {
        engine.onIncoming = ::onIncoming
    }

    fun detach() {
        engine.onIncoming = null
    }

    private fun onIncoming(rows: List<MessageRow>) {
        if (engine.restoreProgress.value.restoring) return
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        for (row in rows) notify(row)
    }

    private fun notify(row: MessageRow) {
        if (row.fromMe || !fresh(row.timestamp)) return
        val index = row.serverIndex
        if (index != null && index <= (storage.lastRead(row.chat) ?: -1L)) return
        if (storage.muteLevel(row.chat) != 0) return
        val name = storage.contact(row.chat)?.alias?.takeIf { it.isNotBlank() } ?: "Peel"
        val content = row.body?.let { runCatching { JSONObject(it).optString("content") }.getOrNull() }.orEmpty()
        when (row.type) {
            0 -> if (content.isNotBlank()) Notifications.showMessage(context, row.chat, name, content)
            ATTACHMENT_MESSAGE_TYPE ->
                Notifications.showMessage(context, row.chat, name, content.ifBlank { "Sent an attachment" })
            2 -> Notifications.showPayment(context, name, "Requested sats", row.chat)
            5 -> Notifications.showPayment(context, name, "Sent you sats", row.chat)
            CALL_MESSAGE_TYPE -> Notifications.showIncomingCall(context, row.chat, name)
        }
    }

    /// A timestamp in milliseconds or seconds, within the last two minutes.
    private fun fresh(timestamp: Long?): Boolean {
        val ts = timestamp ?: return false
        val ms = if (ts < 100_000_000_000L) ts * 1000 else ts
        return System.currentTimeMillis() - ms in -60_000L..120_000L
    }
}
