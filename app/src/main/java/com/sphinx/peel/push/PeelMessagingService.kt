package com.sphinx.peel.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/// FCM entry point. `onNewToken` re-registers the token with the server
/// (`PushRegistrar`); `onMessageReceived` is the push itself, which here as
/// on iOS carries no message text, only `custom_data.child`, the sender's
/// child index sealed under the push key (`aps.custom_data.child` in
/// `PushNotificationResolver`). So it is a wake-up: with an account open it
/// reconnects (a connected engine receives the message over MQTT and
/// `MessageNotifier` shows it); with none it shows iOS's generic line,
/// "You have new messages". Naming the chat (iOS' `PushNameDirectory`:
/// child index -> name) is not ported: this port keeps no child-index
/// map yet.
class PeelMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushRegistrar.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val engine = PushRegistrar.activeEngine()
        if (engine != null) {
            // An open account: reconnect and catch up if the connection is
            // not already up; what arrives is notified by `MessageNotifier`.
            if (!engine.isConnected) engine.resume()
            return
        }
        // The system already shows a push that carries a `notification`
        // block while the app is in the background.
        if (message.notification == null) Notifications.showGeneric(applicationContext)
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
