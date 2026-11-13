package com.sphinx.peel.call

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.sphinx.peel.push.Notifications

/// The call's lifecycle anchor (docs/phases/15-call-background.md): a
/// foreground service that keeps the process, and so the LiveKit room
/// `CallController` owns, alive while Peel is in the background or the screen
/// is locked. It owns no call itself; it reads what to show from
/// [CallController.snapshot] and sends Leave and Mute straight back to it.
///
/// Started once, at call start, while Peel is on screen. It is never promoted
/// from the background: if Android refuses the start (or the type's
/// permission is missing) that is reported to the controller, which shows it.
/// Types per call: [ForegroundTypes]. A swipe from Recents ([onTaskRemoved])
/// ends the call. Holds a partial wake lock and a Wi-Fi lock for its life.
class CallService : Service() {
    private var type = ForegroundTypes.NONE
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CallController.leaveAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MUTE -> {
                CallController.toggleMute()
                return START_NOT_STICKY
            }
        }
        val snapshot = CallController.snapshot()
        if (snapshot == null) {
            // Nothing to keep alive (a restart with no call): satisfy the start, then go.
            runCatching {
                Notifications.createChannels(this)
                ServiceCompat.startForeground(this, Notifications.ONGOING_CALL_NOTIFICATION_ID, notification(null), 0)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        Notifications.createChannels(this)
        val next = typeFor(snapshot)
        if (next == ForegroundTypes.NONE) {
            CallController.serviceRefused("Peel needs the microphone to keep a call running in the background.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ONGOING_CALL_NOTIFICATION_ID, notification(snapshot), next)
            type = next
        } catch (e: Exception) {
            // Android 12+ refuses a start from the background, Android 14+ a type
            // whose permission is not held: said, never swallowed.
            CallController.serviceRefused(refusal(e))
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    /// Swiped away from Recents: the call ends.
    override fun onTaskRemoved(rootIntent: Intent?) {
        CallController.leaveAll()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        releaseLocks()
        super.onDestroy()
    }

    /// The notification (clock, Mute label) and, when it changed, the types
    /// follow the call. Adding the camera type is only tried while Peel is on
    /// screen (the system refuses it otherwise).
    private fun refresh() {
        val snapshot = CallController.snapshot() ?: return
        val notification = notification(snapshot)
        val next = typeFor(snapshot)
        val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (next != ForegroundTypes.NONE && next != type && (visible || !ForegroundTypes.addsCamera(type, next))) {
            try {
                ServiceCompat.startForeground(this, Notifications.ONGOING_CALL_NOTIFICATION_ID, notification, next)
                type = next
                return
            } catch (e: Exception) {
                CallController.serviceRefused(refusal(e))
            }
        }
        Notifications.updateOngoingCall(this, notification)
    }

    private fun typeFor(snapshot: CallSnapshot) = ForegroundTypes.select(
        micGranted = granted(Manifest.permission.RECORD_AUDIO),
        wantsCamera = snapshot.wantsCamera,
        cameraGranted = granted(Manifest.permission.CAMERA),
    )

    private fun notification(snapshot: CallSnapshot?) = Notifications.ongoingCall(
        this, snapshot?.title ?: "Peel", snapshot?.chat, snapshot?.startedAtMs ?: System.currentTimeMillis(), snapshot?.micOn,
    )

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /// A partial wake lock (the CPU keeps running with the screen off) and a
    /// Wi-Fi lock (low latency where the system has it), for the call's length.
    @android.annotation.SuppressLint("WakelockTimeout") // released in onDestroy; the timeout is only a backstop
    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val power = getSystemService(PowerManager::class.java)
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "peel:call").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_BACKSTOP_MS)
                }
            }
        }
        runCatching {
            if (wifiLock == null) {
                val wifi = applicationContext.getSystemService(WifiManager::class.java)
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wifi.createWifiLock(mode, "peel:call").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        const val ACTION_STOP = "com.sphinx.peel.call.STOP"
        const val ACTION_MUTE = "com.sphinx.peel.call.MUTE"
        private const val WAKE_BACKSTOP_MS = 8L * 60 * 60 * 1000

        @Volatile private var instance: CallService? = null

        /// Asks the running service (if any) to bring its notification and types up to date.
        fun refresh() {
            instance?.refresh()
        }

        /// Starts the foreground service; call only while Peel is on screen.
        /// Returns why it could not start, or null.
        fun start(context: Context): String? = try {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
            null
        } catch (e: Exception) {
            refusal(e)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        internal fun refusal(e: Exception): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                "Android would not let Peel keep this call running in the background."
            } else if (e is SecurityException) {
                "Peel is not allowed to keep this call running in the background (a permission is missing)."
            } else {
                "Peel could not keep this call running in the background."
            }
    }
}
