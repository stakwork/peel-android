package com.sphinx.peel.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sphinx.peel.MainActivity
import com.sphinx.peel.R

/// What a notification is about; one channel each, so the system's own
/// settings can silence them separately (iOS: message, payments thread,
/// "Started a call", `Notifier.swift`).
enum class NotificationKind(val channelId: String, val label: String, val importance: Int) {
    MESSAGES("messages", "Messages", NotificationManager.IMPORTANCE_HIGH),
    PAYMENTS("payments", "Payments", NotificationManager.IMPORTANCE_HIGH),
    CALLS("calls", "Calls", NotificationManager.IMPORTANCE_HIGH),
    /// The quiet, ongoing one a running call holds (`CallService`).
    ONGOING_CALL("ongoing_call", "Ongoing call", NotificationManager.IMPORTANCE_LOW),
}

/// Posts Peel's notifications. Ported from PeelUI's `Notifier.swift` in
/// what it shows (a message, a payment request/sats received, a call
/// someone started) and where a tap goes (its chat; Payments for money);
/// the policy of *what* notifies (past the read marker, not muted, not the
/// chat in view) is `MessageNotifier`.
object Notifications {
    /// Intent extras `MainActivity` reads to open the right place.
    const val EXTRA_CHAT = "com.sphinx.peel.extra.CHAT"
    const val EXTRA_OPEN_PAYMENTS = "com.sphinx.peel.extra.PAYMENTS"
    const val EXTRA_JOIN_CALL = "com.sphinx.peel.extra.JOIN_CALL"

    private val GENERIC_ID = "generic".hashCode()
    const val CALL_NOTIFICATION_ID = 7001
    const val ONGOING_CALL_NOTIFICATION_ID = 7002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        for (kind in NotificationKind.values()) {
            manager.createNotificationChannel(NotificationChannel(kind.channelId, kind.label, kind.importance))
        }
    }

    /// POST_NOTIFICATIONS is a runtime permission from Android 13 on.
    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /// The intent a tap runs: opens `MainActivity` on `chat` (or Payments).
    fun openIntent(
        context: Context,
        chat: String? = null,
        payments: Boolean = false,
        joinCall: Boolean = false,
        requestCode: Int = chat?.hashCode() ?: if (payments) 1 else 0,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            chat?.let { putExtra(EXTRA_CHAT, it) }
            if (payments) putExtra(EXTRA_OPEN_PAYMENTS, true)
            if (joinCall) putExtra(EXTRA_JOIN_CALL, true)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /// A message notification, one per chat (a newer one replaces it).
    fun showMessage(context: Context, chat: String, title: String, text: String) {
        // The real notification replaces a push's per-chat one (same id); a
        // push that could not name its chat posted the generic one: drop it.
        NotificationManagerCompat.from(context).cancel(GENERIC_ID)
        post(context, chat.hashCode(), builder(context, NotificationKind.MESSAGES, title, text)
            .setContentIntent(openIntent(context, chat = chat))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup("messages")
            .build())
    }

    fun showPayment(context: Context, title: String, text: String, chat: String? = null) {
        post(context, "payments".hashCode(), builder(context, NotificationKind.PAYMENTS, title, text)
            .setContentIntent(if (chat != null) openIntent(context, chat = chat) else openIntent(context, payments = true))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build())
    }

    /// A call someone started: full-screen on a locked screen, with a Join
    /// action. Not a ring (iOS shows "Started a call" the same way: no
    /// CallKit), but it can take the whole screen like one.
    fun showIncomingCall(context: Context, chat: String, who: String) {
        val open = openIntent(context, chat = chat, joinCall = true, requestCode = ("call" + chat).hashCode())
        post(context, CALL_NOTIFICATION_ID, builder(context, NotificationKind.CALLS, who, "Started a call")
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "Join", open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setTimeoutAfter(60_000)
            .setAutoCancel(true)
            .build())
    }

    fun cancelIncomingCall(context: Context) =
        NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID)

    /// The notification a push shows when its payload carries nothing but
    /// an encrypted child index: iOS' generic line, "You have new messages".
    fun showGeneric(context: Context, body: String = "You have new messages") {
        showPush(context, PushContent(PushContent.TITLE, body, null))
    }

    /// A push's own notification: the chat's id (and a tap into it) when the
    /// push could be resolved to a chat, so the real MQTT notification for
    /// that chat ([showMessage]) replaces it; else the one generic id.
    fun showPush(context: Context, content: PushContent) {
        val chat = content.route?.chat
        post(context, chat?.hashCode() ?: GENERIC_ID, builder(context, NotificationKind.MESSAGES, content.title, content.body)
            .setContentIntent(if (chat != null) openIntent(context, chat = chat) else openIntent(context))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build())
    }

    fun ongoingCall(context: Context, title: String, chat: String?): Notification {
        val hangUp = PendingIntent.getService(
            context, 3, Intent(context, com.sphinx.peel.call.CallService::class.java).setAction(com.sphinx.peel.call.CallService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder(context, NotificationKind.ONGOING_CALL, title, "Call in progress")
            .setContentIntent(openIntent(context, chat = chat, requestCode = 2))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, "Leave", hangUp)
            .build()
    }

    private fun builder(context: Context, kind: NotificationKind, title: String, text: String) =
        NotificationCompat.Builder(context, kind.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    @android.annotation.SuppressLint("MissingPermission") // `permitted` checks POST_NOTIFICATIONS just below
    private fun post(context: Context, id: Int, notification: Notification) {
        if (!permitted(context)) return
        createChannels(context)
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
