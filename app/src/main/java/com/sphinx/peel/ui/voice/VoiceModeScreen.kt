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
import com.sphinx.peel.engine.SpokenText
import com.sphinx.peel.engine.VoiceAction
import com.sphinx.peel.engine.VoiceCommand
import com.sphinx.peel.engine.VoiceEvent
import com.sphinx.peel.engine.VoiceMachine
import com.sphinx.peel.engine.VoiceModeInteraction
import com.sphinx.peel.engine.VoiceModePrefs
import com.sphinx.peel.engine.VoiceModeReply
import com.sphinx.peel.engine.VoicePhase
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
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/// Voice Mode (docs/phases/55-voice-mode.md): a mic in the middle of a linen canvas, a small "Type a message…" capsule at the bottom
/// that springs up into a text field, and a small grid button at the top right to the standard view. What is said or typed goes to
/// the agent's DM exactly as the chat's composer sends it (`Engine.sendMessage(engine.agentPubkey, …)`); the agent's reply is read
/// in the standard view.
@Composable
fun VoiceModeScreen(engine: Engine, storage: Storage, onStandardView: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasAgent = remember { storage.contact(engine.agentPubkey) != null }
    var notice by remember { mutableStateOf<String?>(null) }
    val prefs = remember { VoiceModePrefs.of(context) }
    var interaction by remember { mutableStateOf(prefs.interaction) }
    val subtitles = remember { prefs.subtitles }
    var replyText by remember { mutableStateOf<String?>(null) }

    // VoiceMachine (docs/phases/55-voice-mode.md, "State machine"), ported from peel's VoiceModeState.swift: the same
    // idle/listening/hearing/sending/waiting/speaking phases, kept in sync with `phase` so Compose recomposes on a change.
    val machine = remember { VoiceMachine() }
    var phase: VoicePhase by remember { mutableStateOf(machine.phase) }
    fun act(event: VoiceEvent): List<VoiceAction> = machine.apply(event).also { phase = machine.phase }

    // Speech out: Android's TextToSpeech, made once for the screen and shut down with it. The utterance id of the reply's
    // last chunk, so the progress listener (below) knows which `onDone` means "the whole reply finished speaking".
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
                    if (utteranceId == lastUtteranceId) scope.launch { act(VoiceEvent.SpeechEnded); act(VoiceEvent.Stop) }
                }
                @Deprecated("Deprecated in the base class") override fun onError(utteranceId: String?) {
                    if (utteranceId == lastUtteranceId) scope.launch { act(VoiceEvent.SpeechEnded); act(VoiceEvent.Stop) }
                }
            })
        }
        tts[0] = engineReady
        onDispose { tts[0]?.stop(); tts[0]?.shutdown(); tts[0] = null }
    }
    androidx.compose.runtime.LaunchedEffect(replyText, interaction) {
        val reply = replyText ?: return@LaunchedEffect
        if (interaction.speaksReply) {
            // Markdown cleaned to plain sentences and capped (docs/phases/55-voice-mode.md): code/tables/links become a short
            // phrase and a long reply is cut with a spoken note, rather than TextToSpeech reading raw markdown verbatim.
            val chunks = SpokenText(reply).chunks
            if (chunks.isEmpty()) { act(VoiceEvent.ReplyReady(hasReply = false)); act(VoiceEvent.Stop); return@LaunchedEffect }
            act(VoiceEvent.SpeechStarted)
            lastUtteranceId = "vm_reply_${chunks.lastIndex}"
            chunks.forEachIndexed { i, chunk ->
                tts[0]?.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "vm_reply_$i")
            }
        } else tts[0]?.stop()
    }

    // The agent's reply: what the engine receives in its DM (`Engine.incoming`, the same feed the notifier reads).
    androidx.compose.runtime.LaunchedEffect(engine) {
        engine.incoming.collect { rows ->
            rows.firstNotNullOfOrNull { VoiceModeReply.text(it, engine.agentPubkey) }?.let { text ->
                replyText = text
                if (!interaction.speaksReply) {
                    // Nothing to speak: iOS's own controller only ever passes `hasReply: false` to the machine here too --
                    // the phase is about audio timing, not about whether there is text (that is `replyText`, separate). No
                    // live recognizer is running on Android's one-shot model, so settle straight to idle, not "listening".
                    act(VoiceEvent.ReplyReady(hasReply = false))
                    act(VoiceEvent.Stop)
                }
            }
        }
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
                act(VoiceEvent.SendFinished(sent = false))
                // No live recognizer to resume the draft into, unlike iOS's continuous engine: tapping the mic again
                // starts a fresh Start -> Listening cycle (Android's one-shot SpeechRecognizer has no "keep talking").
                act(VoiceEvent.Stop)
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

    val recognizer = remember { Recognizer(context) }
    DisposableEffect(Unit) { onDispose { recognizer.destroy() } }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { notice = context.str(R.string.voice_unavailable); return }
        notice = null
        act(VoiceEvent.Start)
        recognizer.start(
            onResult = { text ->
                if (text == null) {
                    act(VoiceEvent.Stop)
                    notice = context.str(R.string.voice_heard_nothing)
                } else {
                    act(VoiceEvent.Transcript(text))
                    // The one-shot recognizer already decided the utterance is over, same as iOS's auto-send-on-silence.
                    val toSend = act(VoiceEvent.Silence).filterIsInstance<VoiceAction.Send>().firstOrNull()?.text
                    if (toSend != null) send(toSend) else act(VoiceEvent.Stop)
                }
            },
            onFailure = {
                act(VoiceEvent.Failure("recognition failed"))
                notice = context.str(R.string.voice_recognition_failed)
            },
        )
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else notice = context.str(R.string.voice_permission_refused)
    }

    fun toggleMic() {
        when (machine.phase) {
            VoicePhase.Listening -> recognizer.stop()
            VoicePhase.Idle, is VoicePhase.Failed, VoicePhase.Speaking -> {
                tts[0]?.stop()
                if (machine.phase == VoicePhase.Speaking) { act(VoiceEvent.SpeechEnded); act(VoiceEvent.Stop) }
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (granted) startListening() else askMic.launch(Manifest.permission.RECORD_AUDIO)
            }
            // Hearing/Sending/Waiting: busy with the previous utterance or turn; the tap is ignored rather than starting a
            // second recognition session on top of it (a gap in the ad hoc booleans this phase model replaces).
            else -> {}
        }
    }

    Box(Modifier.fillMaxSize()) {
        LinenBackground(Modifier.fillMaxSize())

        IconButton(
            onClick = onStandardView,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp),
        ) {
            Icon(Icons.Filled.GridView, contentDescription = stringResource(R.string.voice_standard_view), tint = inkColor().copy(alpha = 0.55f), modifier = Modifier.size(22.dp))
        }

        ModePicker(interaction, Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 56.dp, end = 56.dp)) {
            interaction = it
            prefs.interaction = it
        }

        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(interaction.usesMic, enter = fadeIn(), exit = fadeOut()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MicButton(phase == VoicePhase.Listening, onClick = ::toggleMic)
                    Text(
                        if (phase == VoicePhase.Listening) stringResource(R.string.voice_listening) else stringResource(R.string.voice_tap_to_talk),
                        style = MaterialTheme.typography.bodyMedium, color = inkColor().copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
            }
            Box(Modifier.height(64.dp).padding(horizontal = 32.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
                // notice (an error or "Sent to Peel") wins; otherwise the phase itself, for sending/waiting/speaking, which
                // the mic's own "Listening…/Tap to talk" line above does not cover and which matters for every mode, not
                // only the ones with a mic.
                val phaseLabel = when (phase) {
                    VoicePhase.Sending -> stringResource(R.string.voice_sending)
                    VoicePhase.Waiting -> stringResource(R.string.voice_thinking)
                    VoicePhase.Speaking -> stringResource(R.string.voice_speaking)
                    else -> null
                }
                (notice ?: phaseLabel)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = inkColor().copy(alpha = 0.75f), textAlign = TextAlign.Center) }
            }
            // Text out: the reply as a card (or that it is awaited).
            if (!interaction.speaksReply && (replyText != null || phase == VoicePhase.Waiting)) {
                ReplyCard(replyText ?: stringResource(R.string.voice_reply_waiting), Modifier.padding(horizontal = 24.dp))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).imePadding().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Speech out with subtitles: the spoken words as text.
            AnimatedVisibility(interaction.speaksReply && subtitles && replyText != null, enter = fadeIn(), exit = fadeOut()) {
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

    fun start(onResult: (String?) -> Unit, onFailure: () -> Unit) {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(VoiceModeText::clean))
            }
            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) onResult(null) else onFailure()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                // Prefer the device's on-device model; avoids sending audio to the cloud (docs/phases/55-voice-mode.md).
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false),
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
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.primary
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
                .clickable(onClick = onClick),
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
