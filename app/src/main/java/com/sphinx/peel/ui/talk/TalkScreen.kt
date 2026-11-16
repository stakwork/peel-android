package com.sphinx.peel.ui.talk

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sphinx.peel.engine.LevelSmoother
import com.sphinx.peel.engine.TalkLine
import com.sphinx.peel.engine.TalkPhase
import com.sphinx.peel.engine.TalkWave
import com.sphinx.peel.ui.theme.Theme

/// What the transcript list shows, in order: a line, or a card under the reply it came with.
private sealed class TalkRow {
    abstract val key: String
    data class Line(val line: TalkLine) : TalkRow() { override val key get() = "line/${line.id}" }
    data class Card(val card: TalkCard) : TalkRow() { override val key get() = "card/${card.id}" }
}

/// The rows: each card goes after as many lines as its anchor says (`TalkCardAnchor.linesAbove`).
private fun rows(session: TalkSession): List<TalkRow> {
    val lines = session.transcript.lines
    val above = session.cards.map { it to it.anchor.linesAbove(session.transcript) }
    val rows = mutableListOf<TalkRow>()
    for (i in 0..lines.size) {
        above.filter { it.second == i }.forEach { rows += TalkRow.Card(it.first) }
        if (i < lines.size) rows += TalkRow.Line(lines[i])
    }
    return rows
}

/// The Talk call screen (iOS `TalkView`): the waveform circle, what the call
/// is doing in words, the live transcript with Jamie's cards, and Mute, Jamie's
/// voice and Hang up. A pure renderer of [store]; the call itself lives in
/// `MainTabs`' `TalkStore`.
@Composable
fun TalkScreen(store: TalkStore, onClose: () -> Unit) {
    val home = store.home ?: return
    val state = store.state
    val session = (state as? TalkStore.State.On)?.session
    val phase = session?.phase
    val over = state is TalkStore.State.Failed || phase?.isOver == true
    val words = when {
        state is TalkStore.State.Failed -> state.reason
        session == null -> "Calling…"
        else -> session.encryptionNotice ?: session.phase.words
    }
    Column(Modifier.fillMaxSize().background(Theme.colors.background)) {
        Text(
            "Jamie · ${home.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = Theme.colors.text, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            TalkWaveView(session, phase, muted = session?.let { !it.micOn && it.micReady } == true)
        }
        Text(
            words, style = MaterialTheme.typography.bodyLarge,
            color = if (state is TalkStore.State.Failed || session?.encryptionNotice != null) Theme.colors.destructive else Theme.colors.text,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (session != null) {
            if (session.micRefused) Note("Peel can't use the microphone, so Jamie can't hear you. Allow it in Settings.")
            session.notice?.let { Note(it) }
            Transcript(session, Modifier.weight(1f).fillMaxWidth())
        } else {
            Box(Modifier.weight(1f))
        }
        if (over) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = store::tryAgain) { Text("Try again") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                val micOn = session?.micOn == true
                IconButton(
                    onClick = { session?.toggleMicrophone() }, enabled = session != null && session.micReady && !session.micRefused,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Theme.colors.fieldFill),
                ) {
                    Icon(
                        if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff, contentDescription = if (micOn) "Mute" else "Unmute",
                        tint = if (micOn) Theme.colors.text else Theme.colors.secondaryText,
                    )
                }
                IconButton(
                    onClick = store::toggleVoice,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Theme.colors.fieldFill)
                        .semantics { contentDescription = store.voice.accessibilityLabel },
                ) {
                    Text(store.voice.glyph, fontSize = 26.sp, color = Theme.colors.text)
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Theme.colors.destructive),
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = "Hang up", tint = Theme.colors.buttonText)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
    )
}

@Composable
private fun Transcript(session: TalkSession, modifier: Modifier) {
    val list = rows(session)
    val listState = rememberLazyListState()
    LaunchedEffect(list.size, session.transcript.lines.lastOrNull()?.text) {
        if (list.isNotEmpty()) listState.animateScrollToItem(list.size - 1)
    }
    val context = LocalContext.current
    LazyColumn(modifier, state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.key }) { row ->
            when (row) {
                is TalkRow.Line -> {
                    val mine = row.line.speaker == TalkLine.Speaker.YOU
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Text(
                            row.line.text, style = MaterialTheme.typography.bodyMedium,
                            color = if (mine) Theme.colors.buttonText else Theme.colors.text,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp))
                                .background(if (mine) Theme.colors.accent else Theme.colors.fieldFill)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                is TalkRow.Card -> {
                    // The app's own line for it (`AgentArtifactLine`): says where it lives, opens it (on its org's strut in the page host
                    // over the call, else its link in the browser) or says why it cannot be opened here. The call stays up behind it.
                    com.sphinx.peel.ui.apps.AgentArtifactLines(
                        listOf(row.card.card),
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.colors.fieldFill).padding(12.dp),
                    )
                }
            }
        }
    }
}

/// The circle's waveform (iOS `TalkWaveView`): bars that move with the voice
/// heard, sampled on every frame ([TalkWave] is the pure part).
@Composable
private fun TalkWaveView(session: TalkSession?, phase: TalkPhase?, muted: Boolean) {
    var bars by remember { mutableStateOf(List(TalkWave.BAR_COUNT) { TalkWave.FLOOR }) }
    val smoother = remember { LevelSmoother() }
    LaunchedEffect(session, phase, muted) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = (now - last) / 1e9
            last = now
            val mode = TalkWave.Mode.of(phase, muted)
            val heard = when (mode) {
                TalkWave.Mode.AGENT -> session?.level(TalkLine.Speaker.AGENT) ?: 0.0
                TalkWave.Mode.MEMBER -> session?.level(TalkLine.Speaker.YOU) ?: 0.0
                else -> 0.0
            }
            val level = smoother.update(if (mode.hearsVoice) heard else 0.0, dt)
            bars = TalkWave.heights(mode, level, now / 1e9)
        }
    }
    val speaking = phase == TalkPhase.Speaking
    val color = if (speaking) Theme.colors.accent else Theme.colors.secondaryText
    Box(Modifier.size(200.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(120.dp)) {
            val count = bars.size
            val slot = size.width / count
            val width = slot * 0.55f
            bars.forEachIndexed { i, share ->
                val height = size.height * share.toFloat()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * slot + (slot - width) / 2, (size.height - height) / 2),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width / 2, width / 2),
                )
            }
        }
    }
}
