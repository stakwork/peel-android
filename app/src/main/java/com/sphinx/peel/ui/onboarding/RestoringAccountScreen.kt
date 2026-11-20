package com.sphinx.peel.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sphinx.peel.R
import com.sphinx.peel.ui.ErrorText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.RestoreCount
import com.sphinx.peel.engine.RestoreOutcome
import com.sphinx.peel.ui.theme.Theme
import java.text.NumberFormat

private enum class LineState { WAITING, ACTIVE, DONE }
private data class Line(val title: String, val detail: String, val state: LineState, val fraction: Double? = null)

/// Contacts, then messages, as the server sends them back. Peel opens
/// before history finishes — ported from PeelUI's `RestoreProgress`/
/// `RestoreLine` (the iPhone app's `AppModel.restoreProgress`), reading
/// straight off `Engine.connectionState`/`Engine.restoreProgress` instead
/// of a view model, since `Engine` already owns and publishes them.
@Composable
fun RestoringAccountScreen(engine: Engine, onOpen: () -> Unit) {
    val connection by engine.connectionState.collectAsState()
    val progress by engine.restoreProgress.collectAsState()
    val finished = progress.outcome is RestoreOutcome.Finished
    val context = LocalContext.current
    val failed = (progress.outcome as? RestoreOutcome.Failed)?.message?.let { ErrorText.text(context, it, R.string.err_restoring_stopped) }

    val lines = listOf(
        Line(
            title = stringResource(R.string.restore_account),
            detail = when (connection) {
                ConnectionState.Idle -> stringResource(R.string.restore_waiting)
                ConnectionState.Connecting -> stringResource(R.string.restore_connecting)
                ConnectionState.Connected -> stringResource(R.string.restore_connected)
                is ConnectionState.Offline -> stringResource(R.string.restore_offline, (connection as ConnectionState.Offline).retryInSeconds)
                is ConnectionState.Failed -> ErrorText.text(context, (connection as ConnectionState.Failed).message, R.string.err_could_not_connect)
            },
            state = if (connection == ConnectionState.Connected) LineState.DONE else LineState.ACTIVE,
        ),
        Line(
            title = stringResource(R.string.restore_contacts_tribes),
            detail = label(progress.contacts, finished, noun = stringResource(R.string.restore_noun_checked)),
            state = when {
                finished || progress.messages != null ||
                    progress.contacts?.let { it.end != null && it.reached >= it.end } == true -> LineState.DONE
                progress.contacts != null -> LineState.ACTIVE
                else -> LineState.WAITING
            },
            fraction = progress.contacts?.fraction,
        ),
        Line(
            title = stringResource(R.string.restore_messages),
            // The cursor moves past a page the core refused part of by design
            // (HistoryPage.readablePart), so a finished restore can still be
            // short of its own total -- said here rather than left to look
            // like a stall or a silent loss.
            detail = label(progress.messages, finished, noun = stringResource(R.string.restore_noun_received)) +
                if (progress.messagesSkipped > 0) " " + stringResource(R.string.restore_skipped, progress.messagesSkipped) else "",
            state = when {
                finished -> LineState.DONE
                progress.messages != null -> LineState.ACTIVE
                else -> LineState.WAITING
            },
            fraction = progress.messages?.fraction,
        ),
    )

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(stringResource(R.string.restore_title), style = MaterialTheme.typography.headlineSmall, color = Theme.colors.text)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.restore_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = Theme.colors.secondaryText,
        )
        Spacer(Modifier.height(24.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Theme.colors.fieldFill),
        ) {
            lines.forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider(color = Theme.colors.divider)
                RestoreLineRow(line)
            }
        }

        if (failed != null) {
            Spacer(Modifier.height(16.dp))
            Text(failed, color = Theme.colors.destructive, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { engine.resumeRestoreIfNeeded() }) { Text(stringResource(R.string.restore_resume)) }
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
        ) { Text(stringResource(R.string.restore_open)) }
    }
}

@Composable
private fun RestoreLineRow(line: Line) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        StateIndicator(line.state)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    line.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (line.state == LineState.WAITING) Theme.colors.secondaryText else Theme.colors.text,
                )
                Text(line.detail, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            }
            if (line.fraction != null && line.state == LineState.ACTIVE) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { line.fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = Theme.colors.accent,
                    trackColor = Theme.colors.divider,
                    // Material3's default draws a small dot at the
                    // track's end (its "stop indicator") regardless of
                    // actual progress; this bar has nothing at the end
                    // to mark.
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun StateIndicator(state: LineState) {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        when (state) {
            LineState.DONE -> Icon(Icons.Filled.Check, contentDescription = null, tint = Theme.colors.accent)
            LineState.ACTIVE -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Theme.colors.accent)
            LineState.WAITING -> Box(Modifier.size(14.dp).clip(CircleShape).background(Theme.colors.divider))
        }
    }
}

private val numberFormat: NumberFormat get() = NumberFormat.getIntegerInstance()

/// "120 of 300", "35 checked", or before any, "Waiting" ("None" once
/// finished). Ported from PeelUI's `RestoreProgress.label`.
@Composable
private fun label(count: RestoreCount?, finished: Boolean, noun: String): String {
    if (count == null) return stringResource(if (finished) R.string.restore_none else R.string.restore_waiting)
    val end = count.end
    return if (end != null) {
        stringResource(R.string.restore_of, numberFormat.format(count.reached), numberFormat.format(end))
    } else {
        "${numberFormat.format(count.reached)} $noun"
    }
}
