package com.sphinx.peel.ui.voice

import android.Manifest
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import com.sphinx.peel.engine.AgentRequest
import com.sphinx.peel.engine.ControlPlan
import com.sphinx.peel.engine.EchoFilter
import com.sphinx.peel.engine.SpokenText
import com.sphinx.peel.engine.VoiceAction
import com.sphinx.peel.engine.VoiceCommand
import com.sphinx.peel.engine.VoiceCommandMatch
import com.sphinx.peel.engine.VoiceCommandParser
import com.sphinx.peel.engine.VoiceContext
import com.sphinx.peel.engine.VoiceEvent
import com.sphinx.peel.engine.VoiceMachine
import com.sphinx.peel.engine.VoiceModeInteraction
import com.sphinx.peel.engine.VoiceModePrefs
import com.sphinx.peel.engine.VoiceModeReply
import com.sphinx.peel.engine.VoicePhase
import com.sphinx.peel.engine.accepts
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sphinx.peel.R
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.Linen
import com.sphinx.peel.engine.VoiceModeText
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/// Voice Mode (docs/phases/55-voice-mode.md): a mic in the middle of a linen canvas, a small "Type a message…" capsule at the bottom
/// that springs up into a text field, and a small grid button at the top right that turns the Voice Mode setting off (the standard
/// view comes back; turning the setting on again, from here or from You, reopens this screen). What is said or typed goes to the
/// agent's DM exactly as the chat's composer sends it (`Engine.sendMessage(engine.agentPubkey, …)`); the reply (`VoiceModeReply`) is
/// read fresh from the chat's own stored messages, not only from whatever happened to arrive while this screen was collecting.
@Composable
fun VoiceModeScreen(engine: Engine, storage: Storage, onStandardView: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasAgent = remember { storage.contact(engine.agentPubkey) != null }
    var notice by remember { mutableStateOf<String?>(null) }
    val prefs = remember { VoiceModePrefs.of(context) }
    var interaction by remember { mutableStateOf(prefs.interaction) }
    val subtitles = remember { prefs.subtitles }
    val autoSend = remember { prefs.autoSend }
    val autoSendSeconds = remember { prefs.autoSendSeconds }
    var replyText by remember { mutableStateOf<String?>(null) }

    // VoiceMachine (docs/phases/55-voice-mode.md, "State machine"), ported from peel's VoiceModeState.swift: the same
    // idle/listening/hearing/sending/waiting/speaking phases, kept in sync with `phase`/`isMuted` so Compose recomposes.
    val machine = remember { VoiceMachine() }
    var phase: VoicePhase by remember { mutableStateOf(machine.phase) }
    var isMuted by remember { mutableStateOf(machine.isMuted) }
    var draftText by remember { mutableStateOf(machine.draft) }
    fun act(event: VoiceEvent): List<VoiceAction> =
        machine.apply(event).also { phase = machine.phase; isMuted = machine.isMuted; draftText = machine.draft }
    val commandParser = remember { VoiceCommandParser() }
    // Whether the recognizer is literally capturing audio right now, distinct from the machine's resting `Listening` phase
    // (which Android settles back into between one-shot utterances, with no live capture underneath it).
    var recognizing by remember { mutableStateOf(false) }
    // A manual mic tap while a session is live (docs/phases/55-voice-mode.md, "Silence timeout"): ends continuous
    // listening and sends whatever was gathered, rather than letting the chain keep going.
    var stopRequested by remember { mutableStateOf(false) }

    // Echo (docs/phases/55-voice-mode.md, "Echo"): Android's SpeechRecognizer is a black box with no AudioRecord session
    // of its own to attach AcousticEchoCanceler to, and it never runs while TTS is speaking (no continuous listening to
    // barge in on at all), so the one real risk this filter guards is narrower than iOS's: the brief window right after
    // tapping the mic mid-reply, where `tts.stop()` has been asked for but the speaker may not have gone fully silent yet.
    val echoFilter = remember { EchoFilter() }

    // Speech out: Android's TextToSpeech, made once for the screen and shut down with it. The utterance id of the
    // most recently queued batch's last chunk, so the progress listener (below) knows which `onDone` means "every
    // batch queued so far has finished speaking" -- not just whichever batch happened to be first.
    var lastUtteranceId by remember { mutableStateOf<String?>(null) }
    val tts = remember { arrayOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var engineReady: TextToSpeech? = null
        engineReady = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) { engineReady?.shutdown(); tts[0] = null; return@TextToSpeech }
            // Callbacks run on the synthesis thread (TextToSpeech's own contract), not Compose's: hop back via `scope`.
            tts[0]?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    // SpeechEnded, not Stop: it settles back to Listening (the resting phase, matching iOS), without the
                    // Stop event's "voice mode ended" meaning -- which would also wrongly clear a mute set mid-reply.
                    if (utteranceId == lastUtteranceId) scope.launch { act(VoiceEvent.SpeechEnded); echoFilter.speechEnded(System.currentTimeMillis()) }
                }
                @Deprecated("Deprecated in the base class") override fun onError(utteranceId: String?) {
                    if (utteranceId == lastUtteranceId) scope.launch { act(VoiceEvent.SpeechEnded); echoFilter.speechEnded(System.currentTimeMillis()) }
                }
            })
        }
        tts[0] = engineReady
        onDispose { tts[0]?.stop(); tts[0]?.shutdown(); tts[0] = null }
    }

    // A fresh batch's number, so each speak() call's utterance ids ("vm_reply_<batch>_<i>") stay unique across calls:
    // a later message arriving while an earlier one is still being read (docs/phases/55-voice-mode.md, "A second
    // message waits") queues rather than flushes, and two batches' chunks must never collide under the same id.
    var speechBatch by remember { mutableStateOf(0) }

    /// Queues [chunks] as TTS, one after another; used for a fresh reply, and for the "peel repeat that"/"peel read it
    /// all" commands (plain sentence-sized chunks for the former, the untruncated [SpokenText.full] for the latter).
    /// [flush] interrupts whatever is already playing; false appends after it instead, so a reply that arrives while
    /// an earlier one is still being read is queued, not cut off into overlapping or restarted speech.
    fun speak(chunks: List<String>, flush: Boolean = true): Boolean {
        if (chunks.isEmpty()) return false
        act(VoiceEvent.SpeechStarted)
        echoFilter.speechStarted(chunks, System.currentTimeMillis())
        val batch = speechBatch
        speechBatch = batch + 1
        lastUtteranceId = "vm_reply_${batch}_${chunks.lastIndex}"
        chunks.forEachIndexed { i, chunk ->
            val mode = if (flush && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts[0]?.speak(chunk, mode, null, "vm_reply_${batch}_$i")
        }
        return true
    }

    // Both the reply and a pending approval come from a fresh read of the agent's own stored chat, not from a single
    // live-arriving row (docs/phases/55-voice-mode.md, "What the reply actually is"): a transient feed alone would
    // miss whatever arrived before this screen was collecting (the app closed, or Voice Mode simply wasn't open), and
    // would show only the first of several replies that arrived in the same batch rather than all of them.
    var pendingApproval by remember { mutableStateOf<AgentRequest?>(null) }
    // The replies read aloud so far this turn, by id: so a reply that arrives while an earlier one in the same turn
    // is still being read is only ever spoken once, and only the part not already queued.
    val spokenReplyIds = remember { mutableSetOf<Long>() }
    suspend fun refreshFromStorage() {
        val messages = withContext(Dispatchers.Default) { storage.messages(engine.agentPubkey) }
        // A device request already waiting for your approval in the agent's DM: the phone never answers one itself
        // (ControlPlan.pendingApproval's own decision 9), so the only thing Voice Mode can do here is say so, the way
        // `AgentRequestLine` already does in the standard view.
        pendingApproval = ControlPlan.of(messages, inTribe = false, agentDM = true) { "You" }.pendingApproval(System.currentTimeMillis() / 1000)
        // The agent's texts since the person's own newest message (VoiceModeReply.latest, ported from peel): a typed
        // message gets its reply shown the same as a spoken one, and two replies sent back to back are both shown
        // (joined into one panel/subtitle text) rather than the second silently dropping the first.
        val replies = VoiceModeReply.latest(messages, engine.agentPubkey)
        if (replies.isEmpty()) spokenReplyIds.clear() // a fresh turn: nothing queued yet for it.
        val found = VoiceModeReply.text(replies)
        if (found != replyText) {
            replyText = found
            // Nothing to speak: iOS's own controller only ever passes `hasReply: false` to the machine here too -- the
            // phase is about audio timing, not about whether there is text (that is `replyText`, separate).
            if (found != null && !interaction.speaksReply) act(VoiceEvent.ReplyReady(hasReply = false))
        }
        if (interaction.speaksReply) {
            val fresh = replies.filter { it.id !in spokenReplyIds }
            val text = VoiceModeReply.text(fresh)
            if (text != null) {
                // Markdown cleaned to plain sentences and capped (docs/phases/55-voice-mode.md): code/tables/links
                // become a short phrase and a long reply is cut with a spoken note, instead of TTS reading raw
                // markdown. Queued (not flushed) while something from this same turn is already being read.
                if (speak(SpokenText(text).chunks, flush = phase != VoicePhase.Speaking)) spokenReplyIds += fresh.map { it.id }
            }
        }
    }
    // A mode switch to a speech-out one picks up whatever was already shown but never read aloud.
    androidx.compose.runtime.LaunchedEffect(interaction) { if (interaction.speaksReply) refreshFromStorage() }
    // Polled too (ConversationScreen's own pattern), since a pending approval can pass its own expiry with no new
    // message ever arriving -- a push-only refresh would never notice that.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { refreshFromStorage(); delay(5_000) }
    }

    // `engine.incoming` (the same feed the notifier reads) is only the trigger to re-read storage, not the data
    // itself -- see the comment above `refreshFromStorage`.
    androidx.compose.runtime.LaunchedEffect(engine) {
        engine.incoming.collect { rows -> if (rows.any { it.chat == engine.agentPubkey }) refreshFromStorage() }
    }

    fun send(text: String) {
        val clean = VoiceModeText.clean(text) ?: return
        if (!hasAgent) { notice = context.str(R.string.voice_no_agent); return }
        scope.launch {
            notice = try {
                engine.sendMessage(engine.agentPubkey, clean, isTribe = false)
                tts[0]?.stop()
                replyText = null
                act(VoiceEvent.SendFinished(sent = true))
                context.str(R.string.voice_sent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Phase -> Hearing, draft kept (the reducer's own rule): no live recognizer to resume it into, unlike
                // iOS's continuous engine, but typing or tapping the mic again still works from there (see `toggleMic`).
                act(VoiceEvent.SendFinished(sent = false))
                context.str(R.string.voice_send_failed, e.message ?: "")
            }
        }
    }

    /// The type box has no recognizer session already in flight, so it drives the machine there itself before `send`.
    fun sendFromTypeBox(text: String) {
        if (VoiceModeText.clean(text) == null) return
        if (!machine.phase.isActive) act(VoiceEvent.Start)
        act(VoiceEvent.Transcript(text))
        act(VoiceEvent.Command(VoiceCommand.SEND))
        send(text)
    }

    fun toggleMute() { act(VoiceEvent.Command(if (machine.isMuted) VoiceCommand.UNMUTE else VoiceCommand.MUTE)) }

    // Auto-send after a pause (docs/phases/55-voice-mode.md, "Silence timeout"), off by default: restarts on every
    // partial result because `draftText` is a key -- Compose cancels and relaunches this effect each time the draft
    // grows, exactly as peel's own controller restarts its timer on every new partial. Never runs while muted, or
    // outside `Hearing` (so not idle, not listening-with-nothing-yet, not sending/waiting/speaking).
    androidx.compose.runtime.LaunchedEffect(phase, draftText, isMuted) {
        if (!autoSend || isMuted || phase != VoicePhase.Hearing) return@LaunchedEffect
        delay((autoSendSeconds * 1000).toLong())
        if (phase == VoicePhase.Hearing && !isMuted) {
            act(VoiceEvent.Silence).filterIsInstance<VoiceAction.Send>().firstOrNull()?.let { send(it.text) }
        }
    }

    val recognizer = remember { Recognizer(context) }
    DisposableEffect(Unit) { onDispose { recognizer.destroy() } }

    /// One link of continuous listening (docs/phases/55-voice-mode.md, "Silence timeout"): Android's recognizer is
    /// one-shot, ending itself after its own short, fixed silence detection, so a longer dictation is many of these
    /// chained back to back rather than one continuous capture the way iOS's engine tap is. [base] is the text already
    /// confirmed from earlier links in this chain (empty for the first); captured once per call, read-only within it,
    /// so a session's own partials never double up against themselves.
    fun startListening(base: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { notice = context.str(R.string.voice_unavailable); return }
        notice = null
        if (!machine.phase.isActive) act(VoiceEvent.Start)
        recognizing = true

        fun combined(piece: String?) = listOfNotNull(base.takeIf { it.isNotBlank() }, piece?.takeIf { it.isNotBlank() }).joinToString(" ")
        fun chainContinues(stopping: Boolean) = !stopping && (machine.phase == VoicePhase.Listening || machine.phase == VoicePhase.Hearing) && !machine.isMuted
        // A chunk ended with nothing new to add (silence, an echo, or a manual stop): a manual stop with something
        // already gathered sends it now; otherwise the chain either continues or, for the very first chunk with
        // nothing at all, ends quietly with a notice.
        fun finishChunk(stopping: Boolean) {
            when {
                stopping && machine.draft.isNotBlank() ->
                    act(VoiceEvent.Command(VoiceCommand.SEND)).filterIsInstance<VoiceAction.Send>().firstOrNull()?.let { send(it.text) }
                chainContinues(stopping) -> startListening(base)
                else -> {}
            }
        }

        recognizer.start(
            onPartial = { partial -> combined(partial).takeIf { it.isNotBlank() }?.let { act(VoiceEvent.Transcript(it)) } },
            onResult = { text ->
                recognizing = false
                val stopping = stopRequested
                stopRequested = false
                when {
                    text == null -> {
                        if (base.isBlank() && !stopping) notice = context.str(R.string.voice_heard_nothing)
                        finishChunk(stopping)
                    }
                    echoFilter.isEcho(text, System.currentTimeMillis()) -> {
                        // Caught Peel's own reply bleeding through the mic right after an interrupt (see `echoFilter`
                        // above); not real speech, so it is never parsed as a command or folded into the draft.
                        if (base.isBlank() && !stopping) notice = context.str(R.string.voice_echo_ignored)
                        finishChunk(stopping)
                    }
                    else -> when (
                        // The command vocabulary (docs/phases/55-voice-mode.md): the one-shot recognizer only ever
                        // produces a transcript in the "dictating" context (there is no live mic while speaking to hear
                        // a barge-in command in, and no wake listener armed outside a chat -- all Android reaches today).
                        val match = commandParser.parse(text, VoiceContext.DICTATING.accepts)
                    ) {
                        is VoiceCommandMatch.Command -> {
                            combined(match.textBefore).takeIf { it.isNotBlank() }?.let { act(VoiceEvent.Transcript(it)) }
                            val actions = act(VoiceEvent.Command(match.command))
                            val toSend = actions.filterIsInstance<VoiceAction.Send>().firstOrNull()?.text
                            when {
                                toSend != null -> send(toSend)
                                match.command == VoiceCommand.DEACTIVATE -> onStandardView()
                                match.command == VoiceCommand.REPEAT_REPLY -> replyText?.let { speak(SpokenText(it).chunks) }
                                match.command == VoiceCommand.READ_ALL -> replyText?.let { speak(SpokenText(it).full) }
                                // DISCARD/MUTE/UNMUTE already took effect in the reducer (draft cleared / isMuted
                                // flipped); STOP_SPEAKING/SKIP/STOP_AGENT are recognized but have no handler yet -- they
                                // need a live mic while speaking (real barge-in) or an engine hook this screen lacks.
                                else -> if (chainContinues(stopping)) startListening(machine.draft)
                            }
                        }
                        // Plain dictation: folded into the running draft, then the chain continues (or sends, on a
                        // manual stop) -- not an immediate auto-send the way a single one-shot chunk used to mean.
                        VoiceCommandMatch.None -> { act(VoiceEvent.Transcript(combined(text))); finishChunk(stopping) }
                    }
                }
            },
            onFailure = {
                recognizing = false
                stopRequested = false
                act(VoiceEvent.Failure("recognition failed"))
                notice = context.str(R.string.voice_recognition_failed)
            },
        )
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening("") else notice = context.str(R.string.voice_permission_refused)
    }

    fun toggleMic() {
        if (machine.isMuted) return
        if (recognizing) { stopRequested = true; recognizer.stop(); return }
        // Sending/Waiting: a turn is in flight; the tap is ignored rather than starting a second recognition session on
        // top of it (a gap the old ad hoc booleans had).
        if (machine.phase == VoicePhase.Sending || machine.phase == VoicePhase.Waiting) return
        tts[0]?.stop()
        if (machine.phase == VoicePhase.Speaking) { act(VoiceEvent.SpeechEnded); echoFilter.speechEnded(System.currentTimeMillis()) }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening("") else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    Box(Modifier.fillMaxSize()) {
        LinenBackground(Modifier.fillMaxSize())

        IconButton(
            onClick = onStandardView,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp),
        ) {
            Icon(Icons.Filled.GridView, contentDescription = stringResource(R.string.voice_turn_off), tint = inkColor().copy(alpha = 0.55f), modifier = Modifier.size(22.dp))
        }

        // Below the grid button, not beside it: four chips plus the grid icon never fit one row at once width-wise,
        // the last chip squeezed down to a sliver that wrapped its own glyphs onto several lines.
        ModePicker(interaction, Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 16.dp, end = 16.dp)) {
            // Switching away from a speech-out mode stops whatever it was reading right away, rather than leaving it
            // playing under a screen that no longer looks like it is speaking.
            if (!it.speaksReply && phase == VoicePhase.Speaking) { tts[0]?.stop(); act(VoiceEvent.SpeechEnded); echoFilter.speechEnded(System.currentTimeMillis()) }
            interaction = it
            prefs.interaction = it
        }

        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(interaction.usesMic, enter = fadeIn(), exit = fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MicButton(recognizing, onClick = ::toggleMic, dimmed = isMuted)
                    Text(
                        if (recognizing) stringResource(R.string.voice_listening) else stringResource(R.string.voice_tap_to_talk),
                        style = MaterialTheme.typography.bodyMedium, color = inkColor().copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    // The tappable alternative to saying "peel mute"/"peel unmute" (peel's iOS panel has the same button).
                    Text(
                        stringResource(if (isMuted) R.string.voice_unmute else R.string.voice_mute),
                        style = MaterialTheme.typography.labelMedium, color = inkColor().copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 10.dp).clickable(onClick = ::toggleMute),
                    )
                }
            }
            Box(Modifier.height(64.dp).padding(horizontal = 32.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
                // Muted wins over everything else -- it is a deliberate, standing choice, not a passing status. Then
                // notice (an error or "Sent to Peel"). Then a pending approval card (unlocalized, as `AgentRequestLine`'s
                // own "Waiting for your approval on your Mac" already is -- engine/control text keeps its English).
                // Then the phase itself, for sending/waiting/speaking, which the mic's own "Listening…/Tap to talk" line
                // above does not cover and which matters for every mode, not only the ones with a mic.
                val mutedLabel = if (isMuted) stringResource(R.string.voice_muted) else null
                val approvalLabel = pendingApproval?.outcomeElsewhere(System.currentTimeMillis() / 1000)
                val phaseLabel = when (phase) {
                    VoicePhase.Sending -> stringResource(R.string.voice_sending)
                    VoicePhase.Waiting -> stringResource(R.string.voice_thinking)
                    VoicePhase.Speaking -> stringResource(R.string.voice_speaking)
                    else -> null
                }
                (mutedLabel ?: notice ?: approvalLabel ?: phaseLabel)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = inkColor().copy(alpha = 0.75f), textAlign = TextAlign.Center) }
            }
            // Text out: the reply as a card (or that it is awaited).
            if (!interaction.speaksReply && (replyText != null || phase == VoicePhase.Waiting)) {
                ReplyCard(replyText ?: stringResource(R.string.voice_reply_waiting), Modifier.padding(horizontal = 24.dp))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).imePadding().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Speech out with subtitles: the spoken words as text, only while they are actually being read -- not
            // the previous reply, left behind once TTS finishes and phase settles back to Listening/Idle.
            AnimatedVisibility(interaction.speaksReply && subtitles && phase == VoicePhase.Speaking && replyText != null, enter = fadeIn(), exit = fadeOut()) {
                ReplyCard(replyText.orEmpty(), Modifier.padding(bottom = 12.dp))
            }
            TypeBox(Modifier, onSend = ::sendFromTypeBox)
        }
    }
}

/// The four modes as small chips, each a pair of glyphs (input → output) with a spoken label for TalkBack.
@Composable
private fun ModePicker(selected: VoiceModeInteraction, modifier: Modifier, onPick: (VoiceModeInteraction) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        for (mode in VoiceModeInteraction.entries) {
            val label = stringResource(mode.labelRes())
            FilterChip(
                selected = mode == selected, onClick = { onPick(mode) },
                label = { Text(mode.glyphs(), style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}

private fun VoiceModeInteraction.glyphs(): String = when (this) {
    VoiceModeInteraction.SPEECH_IN_SPEECH_OUT -> "\uD83C\uDF99 \u2192 \uD83D\uDD0A"
    VoiceModeInteraction.TEXT_IN_TEXT_OUT -> "\u2328 \u2192 \uD83D\uDCC4"
    VoiceModeInteraction.SPEECH_IN_TEXT_OUT -> "\uD83C\uDF99 \u2192 \uD83D\uDCC4"
    VoiceModeInteraction.TEXT_IN_SPEECH_OUT -> "\u2328 \u2192 \uD83D\uDD0A"
}

@androidx.annotation.StringRes
internal fun VoiceModeInteraction.labelRes(): Int = when (this) {
    VoiceModeInteraction.SPEECH_IN_SPEECH_OUT -> R.string.voice_interaction_speech_in_speech_out
    VoiceModeInteraction.TEXT_IN_TEXT_OUT -> R.string.voice_interaction_text_in_text_out
    VoiceModeInteraction.SPEECH_IN_TEXT_OUT -> R.string.voice_interaction_speech_in_text_out
    VoiceModeInteraction.TEXT_IN_SPEECH_OUT -> R.string.voice_interaction_text_in_speech_out
}

@Composable
private fun ReplyCard(text: String, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.voice_reply_label)
    Box(
        modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)).padding(16.dp)
            .semantics { contentDescription = label },
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.verticalScroll(rememberScrollState()))
    }
}

/// One `SpeechRecognizer` for the screen, made on first use (it must be used on the main thread) and kept until the screen leaves.
private class Recognizer(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    /// [onPartial] fires as the user keeps talking, growing, before this one-shot session ends on its own and [onResult]
    /// gives the final text (continuous listening chains several of these -- docs/phases/55-voice-mode.md, "Silence timeout").
    fun start(onPartial: (String?) -> Unit, onResult: (String?) -> Unit, onFailure: () -> Unit) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(VoiceModeText::clean))
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) onResult(null) else onFailure()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                onPartial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(VoiceModeText::clean))
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                // Prefer the device's on-device model; avoids sending audio to the cloud (docs/phases/55-voice-mode.md).
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // On, so the draft can be shown and auto-sent against as it grows, not only once this chunk ends.
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    /// Ends the capture; the recognizer then answers with what it heard.
    fun stop() { recognizer?.stopListening() }

    fun destroy() { recognizer?.destroy(); recognizer = null }
}

@Composable
private fun inkColor(): Color = if (isSystemInDarkTheme()) Color(0xFFEDE6DA) else Color(0xFF4A4034)

/// The mic in a softly glowing circle; while listening, rings pulse outward and the icon is a stop.
@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit, dimmed: Boolean = false) {
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = if (dimmed) 0.4f else 1f)
    val transition = rememberInfiniteTransition(label = "mic")
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "rings",
    )
    val breathe by transition.animateFloat(
        0.97f, 1.03f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe",
    )
    val label = stringResource(if (listening) R.string.voice_mic_stop else R.string.voice_mic_start)
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2, size.height / 2)
            val base = 60.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = if (dark) 0.35f else 0.28f), Color.Transparent), centre, base * 2.1f), base * 2.1f, centre)
            if (listening) for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                drawCircle(accent.copy(alpha = (1f - p) * 0.55f), base + p * 62.dp.toPx(), centre, style = Stroke(2.dp.toPx()))
            }
        }
        Box(
            Modifier.size(120.dp).scale(if (listening) 1f else breathe).clip(CircleShape)
                .background(accent).semantics { contentDescription = label }
                .clickable(enabled = !dimmed, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(52.dp))
        }
    }
}

/// The small capsule that springs up into a multi-line field with a send button; it folds back when focus leaves it (or Back) while empty.
@Composable
private fun TypeBox(modifier: Modifier, onSend: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    val ink = inkColor()
    val fill = if (isSystemInDarkTheme()) Color(0xFF2B2926) else Color(0xFFFBF7EE)

    if (expanded) BackHandler { focus.clearFocus(); if (text.isEmpty()) expanded = false }
    androidx.compose.runtime.LaunchedEffect(expanded) { if (expanded) { requester.requestFocus(); keyboard?.show() } }

    Box(
        modifier.animateContentSize(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (!expanded) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(fill.copy(alpha = 0.9f))
                    .clickable { expanded = true }.padding(horizontal = 28.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.voice_type_hint), style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.6f)) }
        } else {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(fill),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f).focusRequester(requester).onFocusChanged {
                        // Losing focus while empty folds it back (not the first report, before focus arrived).
                        if (hadFocus && !it.isFocused && text.isEmpty()) expanded = false
                        if (it.isFocused) hadFocus = true
                    },
                    placeholder = { Text(stringResource(R.string.voice_type_hint)) },
                    minLines = 1, maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                val ready = VoiceModeText.clean(text) != null
                IconButton(
                    onClick = { onSend(text); text = ""; focus.clearFocus(); expanded = false },
                    enabled = ready, modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.voice_send), tint = MaterialTheme.colorScheme.primary.copy(alpha = if (ready) 1f else 0.35f))
                }
            }
        }
    }
}

/// A woven linen cloth: a warm base, fine weft and warp lines, a few flecks of thread (`Linen.flecks`, a fixed seed) and a soft
/// vignette. Charcoal in dark mode. Drawn once per size (cached by `drawWithCache`-like remember on the flecks).
@Composable
fun LinenBackground(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val flecks = remember { Linen.flecks() }
    Canvas(modifier) {
        val base = if (dark) Color(0xFF232220) else Color(0xFFE9E0CE)
        val line = if (dark) Color.White else Color(0xFF6B5A3E)
        val lineAlpha = if (dark) 0.035f else 0.07f
        drawRect(base)
        val step = 3.dp.toPx()
        val hair = 0.6.dp.toPx()
        var y = 0f
        var i = 0
        while (y < size.height) {
            drawLine(line.copy(alpha = lineAlpha * (if (i % 4 == 0) 1.8f else 1f)), Offset(0f, y), Offset(size.width, y), hair); y += step; i++
        }
        var x = 0f
        i = 0
        while (x < size.width) {
            drawLine(line.copy(alpha = lineAlpha * (if (i % 5 == 0) 1.8f else 1f)), Offset(x, 0f), Offset(x, size.height), hair); x += step; i++
        }
        drawFlecks(flecks, dark)
        drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.45f else 0.22f)),
                center = Offset(size.width / 2, size.height / 2), radius = max(size.width, size.height) * 0.75f,
            ),
        )
    }
}

private fun DrawScope.drawFlecks(flecks: List<com.sphinx.peel.engine.LinenFleck>, dark: Boolean) {
    val light = if (dark) Color(0xFFB8AE9C) else Color(0xFFFFFBF0)
    val darkThread = if (dark) Color(0xFF14130F) else Color(0xFF8A7556)
    for (f in flecks) {
        val a = Math.toRadians(f.angle.toDouble())
        val len = f.length * size.minDimension * 2.2f
        val from = Offset(f.x * size.width, f.y * size.height)
        val to = Offset(from.x + (cos(a) * len).toFloat(), from.y + (sin(a) * len).toFloat())
        drawLine((if (f.shade < 0.5f) light else darkThread).copy(alpha = 0.45f), from, to, f.width.dp.toPx(), StrokeCap.Round)
    }
}
