package com.sphinx.peel.ui.voice

import android.Manifest
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import com.sphinx.peel.engine.SpokenText
import com.sphinx.peel.engine.VoiceModeInteraction
import com.sphinx.peel.engine.VoiceModePrefs
import com.sphinx.peel.engine.VoiceModeReply
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
    var listening by remember { mutableStateOf(false) }
    val prefs = remember { VoiceModePrefs.of(context) }
    var interaction by remember { mutableStateOf(prefs.interaction) }
    val subtitles = remember { prefs.subtitles }
    var replyText by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }

    // Speech out: Android's TextToSpeech, made once for the screen and shut down with it.
    val tts = remember { arrayOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var engineReady: TextToSpeech? = null
        engineReady = TextToSpeech(context) { status -> if (status != TextToSpeech.SUCCESS) { engineReady?.shutdown(); tts[0] = null } }
        tts[0] = engineReady
        onDispose { tts[0]?.stop(); tts[0]?.shutdown(); tts[0] = null }
    }
    androidx.compose.runtime.LaunchedEffect(replyText, interaction) {
        val reply = replyText ?: return@LaunchedEffect
        if (interaction.speaksReply) {
            // Markdown cleaned to plain sentences and capped (docs/phases/55-voice-mode.md): code/tables/links become a short
            // phrase and a long reply is cut with a spoken note, rather than TextToSpeech reading raw markdown verbatim.
            val chunks = SpokenText(reply).chunks
            chunks.forEachIndexed { i, chunk ->
                tts[0]?.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "vm_reply_$i")
            }
        } else tts[0]?.stop()
    }

    // The agent's reply: what the engine receives in its DM (`Engine.incoming`, the same feed the notifier reads).
    androidx.compose.runtime.LaunchedEffect(engine) {
        engine.incoming.collect { rows ->
            rows.firstNotNullOfOrNull { VoiceModeReply.text(it, engine.agentPubkey) }?.let { replyText = it; waiting = false }
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
                waiting = true
                context.str(R.string.voice_sent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                context.str(R.string.voice_send_failed, e.message ?: "")
            }
        }
    }

    val recognizer = remember { Recognizer(context) }
    DisposableEffect(Unit) { onDispose { recognizer.destroy() } }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { notice = context.str(R.string.voice_unavailable); return }
        notice = null
        listening = true
        recognizer.start(
            onResult = { text ->
                listening = false
                if (text == null) notice = context.str(R.string.voice_heard_nothing) else send(text)
            },
            onFailure = { listening = false; notice = context.str(R.string.voice_recognition_failed) },
        )
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else notice = context.str(R.string.voice_permission_refused)
    }

    fun toggleMic() {
        if (listening) { recognizer.stop(); return }
        tts[0]?.stop()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else askMic.launch(Manifest.permission.RECORD_AUDIO)
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
                    MicButton(listening, onClick = ::toggleMic)
                    Text(
                        if (listening) stringResource(R.string.voice_listening) else stringResource(R.string.voice_tap_to_talk),
                        style = MaterialTheme.typography.bodyMedium, color = inkColor().copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
            }
            Box(Modifier.height(64.dp).padding(horizontal = 32.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = inkColor().copy(alpha = 0.75f), textAlign = TextAlign.Center) }
            }
            // Text out: the reply as a card (or that it is awaited).
            if (!interaction.speaksReply && (replyText != null || waiting)) {
                ReplyCard(replyText ?: stringResource(R.string.voice_reply_waiting), Modifier.padding(horizontal = 24.dp))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).imePadding().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Speech out with subtitles: the spoken words as text.
            AnimatedVisibility(interaction.speaksReply && subtitles && replyText != null, enter = fadeIn(), exit = fadeOut()) {
                ReplyCard(replyText.orEmpty(), Modifier.padding(bottom = 12.dp))
            }
            TypeBox(Modifier, onSend = ::send)
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
