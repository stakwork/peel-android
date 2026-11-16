package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.AgentRequest
import com.sphinx.peel.engine.AgentToolLine
import com.sphinx.peel.engine.AgentToolTitle
import com.sphinx.peel.engine.ControlRow
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.delay

// A control message's row on the phone (iOS Apps/iOS/Peel/MessageRows.swift: `ConversationRow` `.control`, `AgentRequestLine`, `AgentToolLines`,
// `RowNote`): never a bubble. Composables only; the rules are `engine/AgentChat.kt` (JVM-tested).

/// The row a control message is drawn as: a request to this device or a fold of reads as one quiet line ("Read your unread messages ✓", "Send a
/// message · Waiting for your approval on your Mac"), else its line, centred and quiet like a note.
@Composable
fun ControlRowView(row: ControlRow) {
    when (row) {
        is ControlRow.Reads -> AgentRequestLine(row.reads.summary, row.reads.waiting)
        is ControlRow.Request -> AgentRequestLine(row.request.label, row.request)
        is ControlRow.Line -> Text(
            row.text,
            style = MaterialTheme.typography.bodySmall,
            color = Theme.colors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/// A request from the agent to the Mac, one quiet line like a tool's. The phone never answers one (decision 9). Unanswered, it turns "too late" at
/// its expiry without a new message.
@Composable
fun AgentRequestLine(label: String, request: AgentRequest?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    val changesAt = request?.changesAt
    LaunchedEffect(changesAt) {
        if (changesAt == null) return@LaunchedEffect
        val wait = (changesAt - System.currentTimeMillis() / 1000) * 1000
        if (wait > 0) delay(wait)
        now = System.currentTimeMillis() / 1000
    }
    val outcome = request?.outcomeElsewhere(now)
    val text = if (outcome != null) "$label · $outcome" else "$label  ✓"
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Theme.colors.secondaryText,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TEXT_INSET, end = 16.dp, top = 3.dp, bottom = 3.dp)
            .semantics { if (outcome == null) contentDescription = "$label, done" },
    )
}

/// The tools an agent's reply ran, above it in its DM: one quiet line each, in line with the messages' text ("Read unseen messages · Kate").
@Composable
fun AgentToolLines(tools: List<AgentToolLine>) {
    Column(
        Modifier.fillMaxWidth().padding(start = TEXT_INSET, end = 16.dp, top = 3.dp, bottom = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // By position: a reply may run one tool twice.
        tools.forEachIndexed { index, tool ->
            val asked = remember(tool) { AgentToolTitle.asked(tool.summary) }
            val line = AgentToolTitle.of(tool.name) + (if (asked.isNotEmpty()) " · $asked" else "") + (if (tool.failed) " · failed" else "")
            Row { Text(line, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/// Where a row's text starts: the side gutter, the photo column and its gap (iOS `RowMetrics.textInset`).
private val TEXT_INSET = 16.dp + 32.dp + 10.dp
