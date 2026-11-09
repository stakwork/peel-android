package com.sphinx.peel.ui.call

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Intent
import com.sphinx.peel.engine.CallChat
import com.sphinx.peel.engine.CallChatMessage
import com.sphinx.peel.engine.CallLink
import com.sphinx.peel.engine.CallParticipant
import com.sphinx.peel.engine.CallServer
import com.sphinx.peel.engine.CallTicket
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import java.util.UUID
import io.livekit.android.room.Room
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/// Connecting, connected or reconnecting, or the connection is gone (or
/// never came) and LiveKit gave up — PeelCore's own `IOSCallSession.State`.
enum class CallConnectionState { CONNECTING, CONNECTED, RECONNECTING, LOST }

/// A camera or an audio route the person can pick in the call.
data class CallDevice(val id: String, val label: String, val selected: Boolean)

/// One tile in the grid: a person, or (isScreen) what a person is sharing.
/// Names and photos are what each joiner sent — not an identity. Ported from
/// PeelCore's `IOSCallSession.Tile`. [id] is unique per tile (a person who
/// shares has two); [identity] is the person's.
data class CallTile(
    val identity: String,
    val person: CallParticipant,
    val isYou: Boolean,
    val speaking: Boolean,
    val muted: Boolean,
    val video: VideoTrack?,
    val isScreen: Boolean = false,
) {
    val id: String get() = if (isScreen) "$identity#screen" else identity
}

/// The call this device is on: owns the LiveKit room for one call, its
/// microphone, camera and screen share, the in-call chat, and what the call
/// screen shows of it. Ported from PeelCore's `IOSCallSession`, trimmed: no
/// audio-session management (LiveKit's own Android
/// SDK already handles Bluetooth/speaker routing itself, through the same
/// `audioswitch` dependency its own `Room` pulls in — nothing here needs to
/// configure `AudioManager` by hand the way `AVAudioSession` needs asking
/// on iOS).
///
/// The microphone starts on and the camera off; only the person who started
/// a video call (or explicitly turns theirs on) starts with the camera on.
///
/// [askMicPermission]/[askCameraPermission] are owned by the Composable
/// that creates this session — launching a system permission dialog needs
/// an `ActivityResultLauncher`, which only exists at that layer. This class
/// only ever calls them before actually turning a device on, whether that
/// is the automatic "microphone on after connecting" or an explicit tap.
class CallSession(
    appContext: Context,
    val ticket: CallTicket,
    val link: CallLink,
    val chat: String,
    val title: String,
    camera: Boolean,
    private val askMicPermission: suspend () -> Boolean,
    private val askCameraPermission: suspend () -> Boolean,
) {
    private val context = appContext
    private val room: Room = LiveKit.create(appContext, RoomOptions(adaptiveStream = true, dynacast = true))

    var state by mutableStateOf(CallConnectionState.CONNECTING)
        private set

    /// When the first participant joined the room (epoch ms), so everyone
    /// sees the same running time; falls back to the local connect time
    /// when no join time is known. Only ever moves earlier.
    var connectedAtMs by mutableStateOf<Long?>(null)
        private set
    var tiles by mutableStateOf<List<CallTile>>(emptyList())
        private set
    var micOn by mutableStateOf(false)
        private set
    var cameraOn by mutableStateOf(false)
        private set

    /// Whether the call server is recording the call (the room says so).
    var isRecording by mutableStateOf(false)
        private set

    /// Whether you last chose the camera on (or started a video call):
    /// what a rejoin brings back.
    var wantsCamera by mutableStateOf(camera)
        private set

    /// The last attempt to turn the microphone/camera on was refused by
    /// the system (denied permission, or the request simply never
    /// resolved true) — shown as "Can't use the microphone/camera" with
    /// an "Open Settings" link.
    var micRefused by mutableStateOf(false)
        private set
    var cameraRefused by mutableStateOf(false)
        private set

    /// You are sharing your screen, or a camera as a share.
    var sharing by mutableStateOf(false)
        private set

    /// The tile you pinned to the big spot (a tile [CallTile.id]); null: none,
    /// and a screen share takes the spot by itself.
    var pinnedId by mutableStateOf<String?>(null)
        private set

    /// The big tile: the one you pinned, else the first screen share.
    val focusTile: CallTile?
        get() = tiles.firstOrNull { it.id == pinnedId } ?: tiles.firstOrNull { it.isScreen }

    fun togglePin(id: String) {
        pinnedId = if (pinnedId == id) null else id
    }

    /// The in-call chat: lines in order, how many arrived while the panel was
    /// shut, and whether it is open (set by the screen).
    var chatMessages by mutableStateOf<List<CallChatMessage>>(emptyList())
        private set
    var chatUnread by mutableStateOf(0)
        private set
    var chatOpen by mutableStateOf(false)
        private set

    fun showChat(open: Boolean) {
        chatOpen = open
        if (open) chatUnread = 0
    }

    /// Something that went wrong mid-call, said quietly under the bar.
    var deviceNotice by mutableStateOf<String?>(null)
        private set

    /// The screen calls this once it has shown the notice as a toast.
    fun clearDeviceNotice() {
        deviceNotice = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var leaving = false
    private var switchingMic = false
    private var switchingCamera = false
    private var failed = false
    private var everConnected = false

    init {
        scope.launch {
            room.events.collect { event ->
                if (event is RoomEvent.DataReceived && event.topic == CallChat.TOPIC) received(event)
                refresh()
            }
        }
        scope.launch {
            try {
                room.connect(ticket.serverUrl, ticket.token)
            } catch (e: Exception) {
                failed = true
                refresh()
                return@launch
            }
            refresh()
            // Not joined by the caller: a microphone prompt nobody answers
            // must not hold up the rest of connecting.
            launch {
                setMicrophone(true)
                if (camera) setCamera(true)
            }
        }
        // Your own tile from the start, while the room connects.
        refresh()
    }

    /// Whether you are on the call, or on your way.
    val isOn: Boolean get() = state != CallConnectionState.LOST

    /// The running time ("03:18", "1:02:07" past the hour), or what the
    /// connection is doing.
    fun status(nowMs: Long): String = when (state) {
        CallConnectionState.CONNECTING -> "Connecting…"
        CallConnectionState.RECONNECTING -> "Reconnecting…"
        CallConnectionState.LOST -> "Disconnected"
        CallConnectionState.CONNECTED -> {
            val at = connectedAtMs
            if (at == null) "" else formatElapsed(maxOf(0L, nowMs - at) / 1000)
        }
    }

    companion object {
        private const val SHARE_NOTIFICATION_ID = 4107

        /// The call's start: the earliest known join time (epoch ms) of
        /// anyone in the room. Never later than [current] (so it survives
        /// the first participant leaving); with nothing known and no
        /// [current], [fallback] (the local connect time).
        internal fun earliestStart(current: Long?, joinedAts: List<Long?>, fallback: Long): Long {
            val earliest = joinedAts.filterNotNull().minOrNull()
            return when {
                earliest == null -> current ?: fallback
                current == null -> earliest
                else -> minOf(current, earliest)
            }
        }

        /// "03:18", "1:02:07" past the hour — PeelCore's own
        /// `Duration.formatted(.time(...))`, a pure function (no `Date`/
        /// `Room` involved) so it can be tested without a LiveKit session.
        internal fun formatElapsed(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }

    /// Leaves the room, synchronously — `Room.disconnect()`/`release()`
    /// are plain functions on this SDK, not suspend, unlike PeelCore's own
    /// `await room.disconnect()`, so there's nothing to await here at all.
    fun leave() {
        if (leaving) return
        leaving = true
        room.disconnect()
        room.release()
        scope.cancel()
    }

    fun toggleMicrophone() {
        scope.launch { setMicrophone(!micOn) }
    }

    fun toggleCamera() {
        scope.launch { setCamera(!cameraOn) }
    }

    // MARK: - Chat

    private fun received(event: RoomEvent.DataReceived) {
        val participant = event.participant ?: return
        val (id, timestamp, text) = CallChat.decode(event.data) ?: return
        val identity = participant.identity?.value ?: return
        val name = CallServer.fromLiveKitRoom(identity, participant.name, participant.metadata)?.nickname?.ifEmpty { null } ?: "Guest"
        val line = CallChatMessage(
            id = id.ifEmpty { UUID.randomUUID().toString() }, identity = identity, name = name, text = text,
            timestampMs = if (timestamp > 0) timestamp else System.currentTimeMillis(), isYou = false,
        )
        val next = CallChat.append(chatMessages, line)
        if (next !== chatMessages) {
            chatMessages = next
            if (!chatOpen) chatUnread += 1
        }
    }

    /// Sends what was typed to everyone in the room, and shows it as yours.
    fun sendChat(typed: String) {
        val text = CallChat.outgoing(typed) ?: return
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        chatMessages = CallChat.append(
            chatMessages, CallChatMessage(id, ticket.identity, "You", text, now, isYou = true),
        )
        scope.launch {
            val result = room.localParticipant.publishData(CallChat.encode(id, now, text), DataPublishReliability.RELIABLE, CallChat.TOPIC)
            if (result.isFailure) deviceNotice = "Could not send the message"
        }
    }

    // MARK: - Screen share

    private var shareTrack: LocalVideoTrack? = null

    /// Shares the screen: [data] is the system's MediaProjection consent
    /// result, [notification] the one the capture's foreground service shows.
    fun startScreenShare(data: Intent, notification: android.app.Notification) {
        if (sharing || leaving) return
        scope.launch {
            try {
                val params = ScreenCaptureParams(data, SHARE_NOTIFICATION_ID, notification, onStop = { scope.launch { stopShare() } })
                room.localParticipant.setScreenShareEnabled(true, params)
            } catch (e: Exception) {
                deviceNotice = "Could not share the screen"
            }
            refresh()
        }
    }

    /// Shares a camera (not the one that is your own video) as a share tile.
    fun startCameraShare(deviceId: String) {
        if (sharing || leaving) return
        scope.launch {
            if (!askCameraPermission()) {
                cameraRefused = true
                return@launch
            }
            try {
                val track = room.localParticipant.createVideoTrack("camera-share", LocalVideoTrackOptions(deviceId = deviceId))
                track.startCapture()
                shareTrack = track
                room.localParticipant.publishVideoTrack(track, VideoTrackPublishOptions(name = "camera-share", source = Track.Source.SCREEN_SHARE))
            } catch (e: Exception) {
                stopShare()
                deviceNotice = "Could not share the camera"
            }
            refresh()
        }
    }

    fun stopScreenShare() {
        scope.launch { stopShare() }
    }

    private suspend fun stopShare() {
        if (leaving) return
        shareTrack?.let { track ->
            shareTrack = null
            room.localParticipant.unpublishTrack(track, true)
        }
        try { room.localParticipant.setScreenShareEnabled(false) } catch (e: Exception) { /* nothing was being shared */ }
        refresh()
    }

    // MARK: - Devices

    /// The cameras this phone has, the one in use marked.
    fun cameras(): List<CallDevice> {
        val enumerator = livekit.org.webrtc.Camera2Enumerator(context)
        val chosen = selectedCameraId ?: room.videoTrackCaptureDefaults.deviceId
        return enumerator.deviceNames.mapIndexed { i, id ->
            val label = when {
                enumerator.isFrontFacing(id) -> "Front camera"
                enumerator.isBackFacing(id) -> "Back camera"
                else -> "Camera ${i + 1}"
            }
            CallDevice(id, label, selected = id == chosen)
        }.let { list ->
            // Two on the same side get numbers, so they can be told apart.
            list.groupBy { it.label }.flatMap { (label, same) ->
                if (same.size == 1) same else same.mapIndexed { n, d -> d.copy(label = "$label ${n + 1}") }
            }.sortedBy { d -> list.indexOfFirst { it.id == d.id } }
        }
    }

    private var selectedCameraId: String? = null

    /// Uses [id] for your video now (if it is on) and the next time.
    fun selectCamera(id: String) {
        selectedCameraId = id
        room.videoTrackCaptureDefaults = room.videoTrackCaptureDefaults.copy(deviceId = id)
        val track = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
        if (track != null && cameraOn) {
            try { track.switchCamera(deviceId = id) } catch (e: Exception) { deviceNotice = "Could not switch the camera" }
        }
    }

    /// Where the call's audio goes and the microphone comes from (a phone has
    /// one route at a time: the earpiece, speaker, a headset or Bluetooth).
    fun audioRoutes(): List<CallDevice> {
        val handler = room.audioSwitchHandler ?: return emptyList()
        val chosen = handler.selectedAudioDevice
        return handler.availableAudioDevices.map { CallDevice(it.name, it.name, selected = it == chosen) }
    }

    fun selectAudioRoute(id: String) {
        val handler = room.audioSwitchHandler ?: return
        handler.availableAudioDevices.firstOrNull { it.name == id }?.let { handler.selectDevice(it) }
    }

    /// Front and back cameras.
    fun flipCamera() {
        if (!cameraOn) return
        val track = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        track.switchCamera()
    }

    private suspend fun setMicrophone(on: Boolean) {
        if (switchingMic || leaving) return
        switchingMic = true
        try {
            if (on) {
                if (!askMicPermission()) {
                    micRefused = true
                    return
                }
                micRefused = false
            }
            if (leaving) return
            try {
                room.localParticipant.setMicrophoneEnabled(on)
            } catch (e: Exception) {
                if (on) deviceNotice = "Could not turn the microphone on"
            }
        } finally {
            switchingMic = false
        }
        refresh()
    }

    /// Off mutes (not unpublishes) the camera track — this SDK's own
    /// `setCameraEnabled(false)` already releases the capture device for
    /// other apps on mute, the same thing PeelCore's own explicit
    /// unpublish-on-off exists to get.
    private suspend fun setCamera(on: Boolean) {
        if (switchingCamera || leaving) return
        switchingCamera = true
        try {
            if (on) {
                if (!askCameraPermission()) {
                    cameraRefused = true
                    return
                }
                cameraRefused = false
            }
            if (leaving) return
            try {
                room.localParticipant.setCameraEnabled(on)
                if (on) wantsCamera = true else wantsCamera = false
            } catch (e: Exception) {
                if (on) deviceNotice = "Could not turn the camera on"
            }
        } finally {
            switchingCamera = false
        }
        refresh()
    }

    // MARK: - The room

    /// Works the tiles and the state out from the room, after any event.
    private fun refresh() {
        val local = room.localParticipant
        val others = room.remoteParticipants.values.sortedWith(
            compareBy({ it.joinedAt ?: Long.MAX_VALUE }, { it.identity?.value ?: "" }),
        )
        val everyone: List<Pair<Participant, Boolean>> = others.map { it to false } + (local to true)
        val next = everyone.flatMap { (participant, isYou) -> listOfNotNull(tile(participant, isYou), screenTile(participant, isYou)) }
        if (pinnedId != null && next.none { it.id == pinnedId }) pinnedId = null
        val shared = local.getTrackPublication(Track.Source.SCREEN_SHARE)?.let { !it.muted } == true
        if (shared != sharing) sharing = shared
        if (next != tiles) tiles = next

        val micPublication = local.getTrackPublication(Track.Source.MICROPHONE)
        val mic = micPublication != null && !micPublication.muted
        if (mic != micOn) micOn = mic
        val camPublication = local.getTrackPublication(Track.Source.CAMERA)
        val cam = camPublication != null && !camPublication.muted
        if (cam != cameraOn) cameraOn = cam
        if (room.isRecording != isRecording) isRecording = room.isRecording

        val now = when (room.state) {
            Room.State.CONNECTED -> {
                everConnected = true
                val start = earliestStart(
                    connectedAtMs,
                    listOf(local.joinedAt) + others.map { it.joinedAt },
                    System.currentTimeMillis(),
                )
                if (start != connectedAtMs) connectedAtMs = start
                CallConnectionState.CONNECTED
            }
            Room.State.RECONNECTING -> CallConnectionState.RECONNECTING
            Room.State.CONNECTING -> if (!everConnected) CallConnectionState.CONNECTING else CallConnectionState.RECONNECTING
            Room.State.DISCONNECTED -> if (!everConnected && !failed) CallConnectionState.CONNECTING else CallConnectionState.LOST
        }
        if (now != state) state = now
    }

    private fun tile(participant: Participant, isYou: Boolean): CallTile? {
        val identity = if (isYou) ticket.identity else (participant.identity?.value ?: return null)
        val person = CallServer.fromLiveKitRoom(identity, participant.name, participant.metadata) ?: return null
        val micPublication = participant.getTrackPublication(Track.Source.MICROPHONE)
        val muted = micPublication == null || micPublication.muted
        val video = participant.getTrackPublication(Track.Source.CAMERA)
            ?.takeIf { !it.muted }
            ?.track as? VideoTrack
        return CallTile(identity, person, isYou, speaking = participant.isSpeaking, muted = muted, video = video)
    }

    /// What a person is sharing, as its own tile; null when nothing is.
    private fun screenTile(participant: Participant, isYou: Boolean): CallTile? {
        val video = participant.getTrackPublication(Track.Source.SCREEN_SHARE)?.takeIf { !it.muted }?.track as? VideoTrack ?: return null
        val base = tile(participant, isYou) ?: return null
        return base.copy(speaking = false, muted = false, video = video, isScreen = true)
    }

    /// For rendering: a renderer attaches to this (and detaches with
    /// [Room.initVideoRenderer] not needed again per-renderer — every
    /// `SurfaceViewRenderer` a tile creates calls it once itself).
    fun initVideoRenderer(renderer: SurfaceViewRenderer) {
        room.initVideoRenderer(renderer)
    }
}

/// Where a join or a start is, for whatever's showing it — the call
/// screen (`CallScreen`) when it's in front, the banner (`CallBanner`)
/// on every other screen while it isn't. Held above `MainTabs`' own
/// `ConversationScreen`/tab content, not scoped to either, so the call
/// itself outlives navigating away from the screen that started it —
/// `docs/phases/15-call-background.md`'s own replacement for Phase 13's
/// "no persistent background call."
sealed class CallPhase {
    /// A ticket is being fetched (`Engine.callTicket`) — before a real
    /// [CallSession], and so before there's a room to connect at all.
    data class Connecting(val chat: String, val title: String, val link: CallLink, val camera: Boolean) : CallPhase()

    /// The ticket request itself failed — refused, unreachable, an
    /// unreadable reply. Distinct from [CallSession]'s own
    /// [CallConnectionState.LOST] (a session that *had* a ticket and a
    /// room, then lost the connection): there is no session to have lost
    /// anything on yet.
    data class Failed(val chat: String, val title: String, val link: CallLink, val camera: Boolean, val error: String) : CallPhase()

    /// A real session — connecting, connected, reconnecting or lost; see
    /// [CallSession.state] for which.
    data class Active(val session: CallSession) : CallPhase()
}

// Extension properties, not members: `Connecting`/`Failed` already have
// their own `chat`/`title`/`link`/`camera` (constructor properties), and
// an extension never collides with those the way a same-named member on
// the sealed class itself would (Kotlin would then require every
// subclass to mark its own property `override`, for no benefit here).
val CallPhase.chat: String get() = when (this) { is CallPhase.Connecting -> chat; is CallPhase.Failed -> chat; is CallPhase.Active -> session.chat }
val CallPhase.title: String get() = when (this) { is CallPhase.Connecting -> title; is CallPhase.Failed -> title; is CallPhase.Active -> session.title }
val CallPhase.link: CallLink get() = when (this) { is CallPhase.Connecting -> link; is CallPhase.Failed -> link; is CallPhase.Active -> session.link }
val CallPhase.camera: Boolean get() = when (this) { is CallPhase.Connecting -> camera; is CallPhase.Failed -> camera; is CallPhase.Active -> session.wantsCamera }

/// A call waiting on the person's yes to leave the one they are on.
data class PendingCall(val chat: String, val title: String, val link: CallLink, val camera: Boolean)
