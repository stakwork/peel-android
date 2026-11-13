package com.sphinx.peel.ui.talk

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.TalkAgentState
import com.sphinx.peel.engine.TalkArtifacts
import com.sphinx.peel.engine.TalkCardAnchor
import com.sphinx.peel.engine.TalkEncryption
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.TalkLine
import com.sphinx.peel.engine.TalkPhase
import com.sphinx.peel.engine.TalkTicket
import com.sphinx.peel.engine.TalkTranscript
import com.sphinx.peel.engine.TalkVoice
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/// One artifact Jamie sent on the call, shown where its anchor puts it in the transcript.
data class TalkCard(val id: String, val card: TalkArtifacts.Card, val anchor: TalkCardAnchor)

/// A Talk call's room (iOS `TalkSession`): the LiveKit room an org's home
/// named for a call with its agent, the microphone, and what the call screen
/// shows of it, the agent's state and the live text ([TalkPhase],
/// [TalkTranscript]). The ticket's token and key go to LiveKit and are not kept.
///
/// The room is end-to-end encrypted with the call's key, the audio both ways
/// and the live text: the LiveKit server forwards what it cannot read.
///
/// [askMicPermission] is `CallController`'s (not an Activity's: this session
/// outlives the Activity); the microphone was already asked for before the
/// call started, so it is normally granted at once.
class TalkSession(
    appContext: Context,
    ticket: TalkTicket,
    val home: TalkHome,
    private val askMicPermission: suspend () -> Boolean,
) {
    private val identity = ticket.identity
    private val agent = ticket.agent
    private val room: Room = LiveKit.create(appContext, options(ticket.key))

    var phase by mutableStateOf<TalkPhase>(TalkPhase.Connecting)
        private set
    var transcript by mutableStateOf(TalkTranscript())
        private set
    /// The cards Jamie sent, in the order they came, each with where it goes in the transcript.
    var cards by mutableStateOf<List<TalkCard>>(emptyList())
        private set
    var micOn by mutableStateOf(false)
        private set
    /// The microphone has been asked for once the room connected: until then it is neither on nor muted.
    var micReady by mutableStateOf(false)
        private set
    /// The system refused the microphone: the agent cannot hear the member.
    var micRefused by mutableStateOf(false)
        private set
    /// Something that went wrong with the microphone, said quietly.
    var notice by mutableStateOf<String?>(null)
        private set
    /// The call's encryption failing ([TalkEncryption]): said before anything else.
    var encryptionNotice by mutableStateOf<String?>(null)
        private set

    /// Told when the phase or the microphone changes: `CallController`'s hook
    /// (the notification's Mute label, a call that ended in the background).
    var onStatus: () -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var encryption = TalkEncryption()
    private val seenArtifacts = mutableSetOf<String>()
    private var failed = false
    private var connectedOnce = false
    private var agentSeen = false
    private var leaving = false
    private var switchingMic = false
    /// Jamie's voice the agent has been told (the home's answer), and the one the member wants.
    private var toldVoice: TalkVoice? = ticket.voice
    private var wantedVoice: TalkVoice? = null

    init {
        // Before the room connects, so no line is missed.
        runCatching {
            room.registerTextStreamHandler(TalkTranscript.TOPIC) { reader, sender ->
                val stream = reader.info.id
                val attributes = reader.info.attributes
                scope.launch {
                    try {
                        reader.flow.collect { chunk -> received(chunk, stream, sender.value, attributes) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A stream that ends badly keeps what came of it.
                    }
                }
            }
            room.registerTextStreamHandler(TalkArtifacts.TOPIC) { reader, _ ->
                scope.launch {
                    try {
                        receivedArtifacts(reader.readAll().joinToString(""))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Not a payload: nothing to show.
                    }
                }
            }
        }
        scope.launch {
            room.events.collect { event ->
                if (event is RoomEvent.TrackE2EEStateEvent) encryptionChanged(event)
                refresh()
            }
        }
        scope.launch {
            try {
                room.connect(ticket.url, ticket.token)
            } catch (e: Exception) {
                failed = true
                refresh()
                return@launch
            }
            refresh()
            // Not awaited by `leave`: a microphone prompt nobody answers must
            // not hold the call open.
            launch {
                setMicrophone(true)
                micReady = true
            }
            // A voice switched while connecting.
            tellVoice()
        }
        refresh()
    }

    /// Hangs up: leaves the room, synchronously.
    fun leave() {
        if (leaving) return
        leaving = true
        runCatching { room.unregisterTextStreamHandler(TalkTranscript.TOPIC) }
        runCatching { room.unregisterTextStreamHandler(TalkArtifacts.TOPIC) }
        room.disconnect()
        room.release()
        scope.cancel()
    }

    fun toggleMicrophone() {
        scope.launch { setMicrophone(!micOn) }
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
                notice = null
            } catch (e: Exception) {
                if (on) notice = "Could not turn the microphone on"
            }
        } finally {
            switchingMic = false
        }
        refresh()
    }

    // MARK: - Jamie's voice

    /// Asks for [voice] from the agent's next sentence: the member's own
    /// participant attribute `voice`, once the room is connected (until then
    /// `connect` does it). The member's token allows it. LiveKit's attribute
    /// update has no reply here, so a failed one is not said.
    fun setVoice(voice: TalkVoice) {
        wantedVoice = voice
        tellVoice()
    }

    private fun tellVoice() {
        val wanted = wantedVoice ?: return
        if (leaving || room.state != Room.State.CONNECTED || wanted == toldVoice) return
        room.localParticipant.updateAttributes(mapOf(TalkVoice.ATTRIBUTE to wanted.wire))
        toldVoice = wanted
    }

    // MARK: - The text

    private fun received(chunk: String, stream: String, sender: String, attributes: Map<String, String>) {
        if (leaving) return
        val tracks = room.localParticipant.trackPublications.keys.toSet()
        transcript = transcript.receive(chunk, stream, sender, attributes, you = identity, yourTracks = tracks)
    }

    /// One turn's artifacts, each a card anchored in the transcript so far. A
    /// payload seen before (by its id) adds nothing.
    private fun receivedArtifacts(text: String) {
        if (leaving || text.toByteArray(Charsets.UTF_8).size > TalkArtifacts.MAX_PAYLOAD) return
        val batch = TalkArtifacts.batch(text) ?: return
        if (!seenArtifacts.add(batch.id)) return
        val anchor = TalkCardAnchor.of(transcript)
        val next = cards + batch.artifacts.mapIndexed { i, card -> TalkCard("${batch.id}/$i", card, anchor) }
        cards = next.takeLast(TalkArtifacts.MAX_CARDS)
    }

    // MARK: - Encryption

    private fun encryptionChanged(event: RoomEvent.TrackE2EEStateEvent) {
        if (leaving) return
        val failedState = event.state in setOf(E2EEState.MISSING_KEY, E2EEState.ENCRYPTION_FAILED, E2EEState.DECRYPTION_FAILED, E2EEState.INTERNAL_ERROR)
        encryption = encryption.track(event.publication.sid, local = event.participant === room.localParticipant, failed = failedState)
        if (encryption.notice != encryptionNotice) encryptionNotice = encryption.notice
    }

    // MARK: - The waveform

    /// How loud [speaker] is now, 0…1: what the call screen's waveform samples
    /// on its own clock. LiveKit's own speaker level (a few updates a second,
    /// not the audio buffers iOS meters), and zero for the member while muted.
    fun level(speaker: TalkLine.Speaker): Double = when (speaker) {
        TalkLine.Speaker.AGENT -> room.remoteParticipants.values.firstOrNull { it.identity?.value == agent }?.audioLevel?.toDouble() ?: 0.0
        TalkLine.Speaker.YOU -> if (micOn) room.localParticipant.audioLevel.toDouble() else 0.0
    }.coerceIn(0.0, 1.0)

    // MARK: - The room

    private fun refresh() {
        if (leaving) return
        val connection = when (room.state) {
            Room.State.CONNECTED -> TalkPhase.Connection.CONNECTED
            Room.State.RECONNECTING -> TalkPhase.Connection.RECONNECTING
            Room.State.CONNECTING -> TalkPhase.Connection.CONNECTING
            Room.State.DISCONNECTED -> TalkPhase.Connection.DISCONNECTED
        }
        if (connection == TalkPhase.Connection.CONNECTED) connectedOnce = true
        // Not connected yet and not failed: still on the way.
        val notYet = connection == TalkPhase.Connection.DISCONNECTED && !connectedOnce && !failed
        val participant = room.remoteParticipants.values.firstOrNull { it.identity?.value == agent }
        if (participant != null) agentSeen = true
        val next = TalkPhase.of(
            if (notYet) TalkPhase.Connection.CONNECTING else connection, connectedOnce,
            participant?.let { it.attributes[TalkAgentState.ATTRIBUTE] ?: "" }, agentSeen,
        )
        val phaseChanged = next != phase
        if (phaseChanged) phase = next
        val micPublication = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        val mic = micPublication != null && !micPublication.muted
        val micChanged = mic != micOn
        if (micChanged) micOn = mic
        if (phaseChanged || micChanged) onStatus()
    }

    companion object {
        /// The room's options for a call's key:
        /// - `e2eeOptions` with a shared-key [BaseKeyProvider]: this SDK
        ///   (livekit-android 2.29.2) encrypts the data channel and text streams
        ///   with the same options as the media (iOS needs `encryptionOptions`
        ///   for that; Android has no deprecated variant). The key is the base64
        ///   string as the home has it: the SDK keys with its UTF-8 bytes
        ///   (`String.toByteArray()`), and so does the agent.
        /// - The microphone without Opus RED, which the server can't repackage
        ///   for an encrypted track, and without DTX, so packets keep a steady
        ///   rate through silence.
        internal fun options(key: String): RoomOptions = RoomOptions(
            e2eeOptions = E2EEOptions(keyProvider = BaseKeyProvider(enableSharedKey = true).also { it.setSharedKey(key) }),
            audioTrackPublishDefaults = AudioTrackPublishDefaults(dtx = false, red = false),
        )
    }
}
