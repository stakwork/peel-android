package com.sphinx.peel.push

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.storage.Storage
import org.json.JSONObject

/// FCM entry point. `onNewToken` re-registers the token with the server
/// (`PushRegistrar`); `onMessageReceived` is the push itself.
///
/// The server sends a data-only, high-priority message (no `notification`
/// block): `child` (the contact/tribe child index sealed under this install's
/// push key), `title` "Peel" and `body` "You have new messages". Nothing
/// shows by itself, so this service does: it resolves `child` to a chat name
/// through the sealed name directory (`PushNames`, iOS' notification
/// extension port; works while locked, no engine needed) and posts it. With
/// an account open it also reconnects (`resume`) so the real message arrives
/// over MQTT, and `MessageNotifier`'s notification for that chat replaces
/// this one (same per-chat id): never two for one message (`PushPlan`).
/// The token, push key and child are never logged.
class PeelMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushRegistrar.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = applicationContext
        val engine = PushRegistrar.activeEngine()
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val plan = PushPlan.decide(engineOpen = engine != null, connected = engine?.let { it.isOnline || it.connectionState.value == ConnectionState.Connecting } == true, foreground = foreground)
        if (plan.reconnect) engine?.resume()
        if (!plan.postResolved) return
        val data = message.data
        val content = runCatching {
            PushNames.resolve(encryptedChild(data), PushKeyStore.existing(app), PushNameStore.forContext(app).read())
        }.getOrDefault(PushContent.GENERIC)
        // The server's own title/body only matter for the generic fallback.
        val shown = if (content.route == null && content.body == PushContent.GENERIC_BODY) {
            PushContent(data["title"]?.takeIf { it.isNotBlank() } ?: content.title, data["body"]?.takeIf { it.isNotBlank() } ?: content.body, null)
        } else {
            content
        }
        // A muted chat (any mute level, as `MessageNotifier`) stays quiet.
        val chat = shown.route?.chat
        if (chat != null && runCatching { Storage(app).muteLevel(chat) }.getOrDefault(0) != 0) return
        Notifications.showPush(app, shown)
    }

    internal companion object {
        /// The child index a push carries, from FCM's flat data map
        /// (`child`) or an APNs-shaped `custom_data` JSON object.
        fun encryptedChild(data: Map<String, String>): String? {
            data["child"]?.takeIf { it.isNotEmpty() }?.let { return it }
            val custom = data["custom_data"]?.let { runCatching { JSONObject(it) }.getOrNull() }
            return custom?.optString("child")?.takeIf { it.isNotEmpty() }
        }
    }
}
