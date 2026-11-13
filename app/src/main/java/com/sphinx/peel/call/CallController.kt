package com.sphinx.peel.call

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.sphinx.peel.engine.CallLink
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.push.Notifications
import com.sphinx.peel.ui.call.CallConnectionState
import com.sphinx.peel.ui.call.CallPhase
import com.sphinx.peel.ui.call.CallSession
import com.sphinx.peel.ui.call.camera
import com.sphinx.peel.ui.call.chat
import com.sphinx.peel.ui.call.link
import com.sphinx.peel.ui.call.title
import com.sphinx.peel.ui.talk.TalkStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/// What the call service shows: the title, the chat a tap opens, the start of the
/// clock, the microphone's state (null: nothing to mute yet) and whether video is wanted.
data class CallSnapshot(val title: String, val chat: String?, val startedAtMs: Long, val micOn: Boolean?, val wantsCamera: Boolean)

/// A line the call banner says (the call ended in the background, the service could
/// not start): [error] decides its colour.
data class CallNotice(val message: String, val error: Boolean)

/// The call this app is on (docs/phases/15-call-background.md), application-scoped:
/// it owns the group call's [CallPhase] and [CallSession] and Talk to Jamie's
/// [TalkStore]/`TalkSession`, and outlives every Activity — rotation or a recreated
/// Activity never ends a call, and `MainTabs` only observes this. [CallService] is its
/// lifecycle anchor. Ends on Leave, a swipe from Recents, "Forget this account"
/// ([endAll]) or a call lost in the background ([endedInBackground]).
///
/// Main thread only. Compose state here is read by the UI; the sessions call back
/// through hooks (not `snapshotFlow`, which does not run with no Activity composing).
object CallController {
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /// The group call: asked to join, failed, or on. Null: none.
    var phase by mutableStateOf<CallPhase?>(null)
        private set

    /// The full call screen is in front, not just the banner. Held here so
    /// rotation and an Activity restart keep it.
    var callScreenShown by mutableStateOf(false)

    /// A line to show as a banner until dismissed.
    var notice by mutableStateOf<CallNotice?>(null)
        private set

    /// Notifications are off but a call runs: say so once (`MainTabs` toasts it, then [nudgeShown]).
    var notificationNudge by mutableStateOf(false)
        private set

    /// A system permission dialog `MainTabs` should show now (it owns the launcher).
    var permissionRequest by mutableStateOf<String?>(null)
        private set

    private lateinit var talkStore: TalkStore

    /// Talk to Jamie's store, beside the group call, never with it.
    val talk: TalkStore get() = talkStore

    private var callStartedAt = 0L
    private var permissionAnswer: CompletableDeferred<Boolean>? = null
    private val permissionMutex = Mutex()

    private val visible: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /// Called once by `PeelApplication`.
    fun install(application: Application) {
        app = application.applicationContext
        prefs = app.getSharedPreferences("call_background", Context.MODE_PRIVATE)
        talkStore = TalkStore(app).also { store ->
            store.askMicPermission = { askPermission(Manifest.permission.RECORD_AUDIO) }
            store.otherCall = { phase?.title }
            store.preflight = ::talkPreflight
            store.onChanged = ::talkChanged
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                // iOS parity: the camera goes off with the app, the wish stays; the microphone is untouched.
                Lifecycle.Event.ON_STOP -> activeSession()?.pauseCameraForBackground()
                Lifecycle.Event.ON_START -> {
                    activeSession()?.resumeCameraFromBackground()
                    Notifications.cancelCallEnded(app)
                }
                else -> {}
            }
        })
        // The process died during a call (killed, or crashed): the flag is still there.
        prefs.getString(KEY_ACTIVE, null)?.let { title ->
            prefs.edit().remove(KEY_ACTIVE).apply()
            val line = "The call in $title ended when Peel was stopped by the system."
            notice = CallNotice(line, error = true)
            Notifications.callEnded(app, line)
        }
    }

    // MARK: - What the service shows

    /// The call as the service shows it; null with no call to keep alive.
    fun snapshot(): CallSnapshot? {
        val group = phase
        if (group != null && group !is CallPhase.Failed) {
            val session = (group as? CallPhase.Active)?.session
            val camera = session?.wantsCamera ?: (group as CallPhase.Connecting).camera
            return CallSnapshot(group.title, group.chatOrNull(), session?.connectedAtMs ?: callStartedAt, session?.micOn, camera)
        }
        val session = (talkStore.state as? TalkStore.State.On)?.session
        if (talkStore.isOn) {
            return CallSnapshot("Jamie · ${talkStore.home?.name ?: ""}", null, callStartedAt, session?.micOn, wantsCamera = false)
        }
        return null
    }

    private fun CallPhase.chatOrNull(): String? = chat.ifEmpty { null }

    private fun activeSession(): CallSession? = (phase as? CallPhase.Active)?.session

    // MARK: - Group call

    /// Joins or starts a call: the microphone (and, for video, the camera) is asked
    /// for first, then the service starts (Peel is on screen), then the ticket is
    /// fetched and the room joined. Any call already on is left.
    fun connect(engine: Engine, chat: String, title: String, link: CallLink, camera: Boolean) {
        dropGroupSession()
        val requested = CallPhase.Connecting(chat, title, link, camera)
        phase = requested
        callScreenShown = true
        callStartedAt = System.currentTimeMillis()
        scope.launch {
            if (!askPermission(Manifest.permission.RECORD_AUDIO)) {
                if (phase !== requested) return@launch
                phase = CallPhase.Failed(chat, title, link, camera, MIC_NEEDED)
                finishIfIdle()
                return@launch
            }
            val video = camera && askPermission(Manifest.permission.CAMERA)
            if (phase !== requested) return@launch
            startService(title)
            try {
                val ticket = engine.callTicket(link)
                // Stale by the time the ticket came back (left, or moved on).
                if (phase !== requested) return@launch
                val session = CallSession(
                    app, ticket, link, chat, title, video,
                    askMicPermission = { askPermission(Manifest.permission.RECORD_AUDIO) },
                    askCameraPermission = { askPermission(Manifest.permission.CAMERA) },
                )
                session.onStatus = ::callChanged
                phase = CallPhase.Active(session)
                CallService.refresh()
            } catch (e: Exception) {
                if (phase !== requested) return@launch
                phase = CallPhase.Failed(chat, title, link, camera, e.message ?: "Could not join the call.")
                finishIfIdle()
            }
        }
    }

    fun retry(engine: Engine) {
        val failed = phase ?: return
        connect(engine, failed.chat, failed.title, failed.link, failed.camera)
    }

    /// Leaves the group call (Leave on the screen or banner).
    fun leave() {
        dropGroupSession()
        phase = null
        callScreenShown = false
        finishIfIdle()
    }

    /// Hangs up Talk to Jamie.
    fun hangUpTalk() {
        talkStore.hangUp()
    }

    /// Talk to Jamie, picked on Apps.
    fun talkTo(home: TalkHome) {
        talkStore.talk(home)
    }

    /// Leave from the notification: whichever call is on.
    fun leaveAll() {
        if (phase != null) leave()
        if (talkStore.home != null) talkStore.hangUp()
    }

    /// Mute (or unmute) from the notification, or anywhere else.
    fun toggleMute() {
        val session = activeSession()
        if (session != null) session.toggleMicrophone()
        else (talkStore.state as? TalkStore.State.On)?.session?.toggleMicrophone()
    }

    /// Ends everything for good — "Forget this account" (and nothing a rotation does).
    fun endAll() {
        leaveAll()
        notice = null
        cancelPermission()
    }

    private fun dropGroupSession() {
        activeSession()?.leave()
    }

    // MARK: - Talk to Jamie

    private suspend fun talkPreflight(): String? {
        if (!askPermission(Manifest.permission.RECORD_AUDIO)) return MIC_NEEDED
        callStartedAt = System.currentTimeMillis()
        startService("Jamie")
        return null
    }

    private fun talkChanged() {
        if (!::talkStore.isInitialized) return
        val session = (talkStore.state as? TalkStore.State.On)?.session
        val ended = session?.phase?.let { it is com.sphinx.peel.engine.TalkPhase.Ended } == true
        if (ended && !visible) {
            talkStore.hangUp()
            endedInBackground("The call with Jamie ended while Peel was in the background.")
            return
        }
        if (!talkStore.isOn) finishIfIdle() else CallService.refresh()
    }

    // MARK: - The call changed

    private fun callChanged() {
        val session = activeSession() ?: return
        if (session.state == CallConnectionState.LOST) {
            if (visible) {
                // The screen shows "Disconnected" with Rejoin; nothing is left to keep alive.
                CallService.stop(app)
                prefs.edit().remove(KEY_ACTIVE).apply()
            } else {
                val title = session.title
                dropGroupSession()
                phase = null
                callScreenShown = false
                endedInBackground("You were disconnected from the call in $title.")
            }
            return
        }
        CallService.refresh()
    }

    private fun endedInBackground(line: String) {
        finishIfIdle()
        notice = CallNotice(line, error = true)
        Notifications.callEnded(app, line)
    }

    // MARK: - The service and what it needs

    private fun startService(title: String) {
        prefs.edit().putString(KEY_ACTIVE, title).apply()
        Notifications.cancelIncomingCall(app)
        if (!Notifications.permitted(app) && !prefs.getBoolean(KEY_NUDGED, false)) notificationNudge = true
        if (!visible) {
            serviceRefused("Peel was not on screen when the call started, so it cannot keep it running in the background.")
            return
        }
        CallService.start(app)?.let { serviceRefused(it) }
    }

    /// The service could not run: the call carries on while Peel is on screen, and this says why not beyond.
    fun serviceRefused(reason: String) {
        notice = CallNotice("$reason Keep Peel open to stay on the call.", error = true)
        if (!visible) Notifications.callEnded(app, reason)
    }

    private fun finishIfIdle() {
        if (phase != null && phase !is CallPhase.Failed) return
        if (talkStore.isOn) return
        CallService.stop(app)
        prefs.edit().remove(KEY_ACTIVE).apply()
    }

    fun dismissNotice() {
        notice = null
        Notifications.cancelCallEnded(app)
    }

    fun nudgeShown() {
        notificationNudge = false
        prefs.edit().putBoolean(KEY_NUDGED, true).apply()
    }

    // MARK: - Permissions (the launcher is MainTabs', the answer is held here so it survives a recreated Activity)

    /// Whether [permission] is granted, asking first (through `MainTabs`' launcher) if Peel is on screen.
    suspend fun askPermission(permission: String): Boolean {
        if (granted(permission)) return true
        if (!visible) return false
        return permissionMutex.withLock {
            if (granted(permission)) return@withLock true
            val answer = CompletableDeferred<Boolean>()
            permissionAnswer = answer
            permissionRequest = permission
            answer.await()
        }
    }

    /// `MainTabs`' launcher's result.
    fun permissionResult(granted: Boolean) {
        permissionRequest = null
        permissionAnswer?.complete(granted)
        permissionAnswer = null
    }

    private fun cancelPermission() {
        permissionRequest = null
        permissionAnswer?.complete(false)
        permissionAnswer = null
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private const val KEY_ACTIVE = "active_call_title"
    private const val KEY_NUDGED = "notification_nudge_shown"
    private const val MIC_NEEDED = "Peel needs the microphone to take part in a call. Allow it in Settings, then try again."
}
