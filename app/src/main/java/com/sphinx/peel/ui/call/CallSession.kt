package com.sphinx.peel.ui.call

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.CallLink
import com.sphinx.peel.engine.CallParticipant
import com.sphinx.peel.engine.CallServer
import com.sphinx.peel.engine.CallTicket
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.collect
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

/// One person in the grid. Names and photos are what each joiner sent — not
/// an identity. Ported from PeelCore's `IOSCallSession.Tile`, trimmed: no
/// screen-share tile (this round has none to share) and so no `isScreen`/
/// focus to go with it.
data class CallTile(
    val identity: String,
    val person: CallParticipant,
    val isYou: Boolean,
    val speaking: Boolean,
    val muted: Boolean,
    val video: VideoTrack?,
)

/// The call this device is on: owns the LiveKit room for one call, its
/// microphone and camera, and what the call screen shows of it. Ported from
/// PeelCore's `IOSCallSession`, trimmed: no screen sharing, no device-menu
/// camera/mic picker, no audio-session management (LiveKit's own Android
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
    private val room: Room = LiveKit.create(appContext, RoomOptions(adaptiveStream = true, dynacast = true))

    var state by mutableStateOf(CallConnectionState.CONNECTING)
        private set

    /// When the room first connected: the running time counts from here.
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

    /// Something that went wrong mid-call, said quietly under the bar.
    var deviceNotice by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var leaving = false
    private var switchingMic = false
    private var switchingCamera = false
    private var failed = false

    init {
        scope.launch { room.events.collect { refresh() } }
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
        val next = everyone.mapNotNull { (participant, isYou) -> tile(participant, isYou) }
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
                if (connectedAtMs == null) connectedAtMs = System.currentTimeMillis()
                CallConnectionState.CONNECTED
            }
            Room.State.RECONNECTING -> CallConnectionState.RECONNECTING
            Room.State.CONNECTING -> if (connectedAtMs == null) CallConnectionState.CONNECTING else CallConnectionState.RECONNECTING
            Room.State.DISCONNECTED -> if (connectedAtMs == null && !failed) CallConnectionState.CONNECTING else CallConnectionState.LOST
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

    /// For rendering: a renderer attaches to this (and detaches with
    /// [Room.initVideoRenderer] not needed again per-renderer — every
    /// `SurfaceViewRenderer` a tile creates calls it once itself).
    fun initVideoRenderer(renderer: SurfaceViewRenderer) {
        room.initVideoRenderer(renderer)
    }
}
