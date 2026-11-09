package com.sphinx.peel.call

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sphinx.peel.push.Notifications

/// Keeps the process a priority the system won't reclaim while a call runs
/// (docs/phases/15-call-background.md's open "no foreground service"): a
/// foreground service of type microphone (and camera, when it is granted),
/// holding the ongoing-call notification with a Leave action. The call
/// itself stays in `MainTabs`' `CallSession`; this only keeps it alive.
/// Started when a call becomes active, stopped when it ends.
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Leave from the notification: `MainTabs` sees the broadcast.
            sendBroadcast(Intent(ACTION_LEFT).setPackage(packageName))
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Peel"
        val chat = intent?.getStringExtra(EXTRA_CHAT)?.ifEmpty { null }
        Notifications.createChannels(this)
        val notification = Notifications.ongoingCall(this, title, chat)
        // The type must be one whose permission is held (Android 14).
        var type = 0
        if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        try {
            if (type == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                startForeground(Notifications.ONGOING_CALL_NOTIFICATION_ID, notification)
            } else {
                ServiceCompat.startForeground(this, Notifications.ONGOING_CALL_NOTIFICATION_ID, notification, type)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_STOP = "com.sphinx.peel.call.STOP"
        /// Broadcast (package-internal) when Leave is tapped in the notification.
        const val ACTION_LEFT = "com.sphinx.peel.call.LEFT"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHAT = "chat"

        fun start(context: Context, title: String, chat: String) {
            val intent = Intent(context, CallService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_CHAT, chat)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
