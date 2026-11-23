package com.sphinx.peel.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sphinx.peel.R
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.TransferState
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.delay

/// The quiet bar pinned above a chat's messages while a video compresses and an attachment uploads
/// (`Engine.transfer`). Done goes away on its own after [DONE_MS]; a failure stays until dismissed.
@Composable
fun TransferBar(engine: Engine, modifier: Modifier = Modifier) {
    val state by engine.transfer.state.collectAsState()
    LaunchedEffect(state) {
        if (state is TransferState.Done) {
            delay(DONE_MS)
            engine.transfer.dismiss()
        }
    }
    AnimatedVisibility(visible = state != TransferState.Idle, modifier = modifier) {
        Surface(color = Theme.colors.fieldFill, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when (val now = state) {
                    is TransferState.Compressing -> Progress(stringResource(R.string.transfer_compressing), now.progress)
                    is TransferState.Uploading -> Progress(stringResource(R.string.transfer_uploading), now.progress)
                    TransferState.Done -> Text(stringResource(R.string.transfer_done), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
                    is TransferState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(now.reason, style = MaterialTheme.typography.bodySmall, color = Theme.colors.destructive, modifier = Modifier.weight(1f))
                        TextButton(onClick = { engine.transfer.dismiss() }) { Text(stringResource(R.string.transfer_dismiss), color = Theme.colors.accent) }
                    }
                    TransferState.Idle -> {}
                }
            }
        }
    }
}

private const val DONE_MS = 1_500L

@Composable
private fun Progress(label: String, progress: Float) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = Theme.colors.text)
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Theme.colors.accent)
}

/// The composer's row for an oversized video: compressing, queued for Send, or ready; the x removes it.
@Composable
fun VideoChip(outbox: VideoOutbox, modifier: Modifier = Modifier) {
    if (!outbox.active) return
    val text = when {
        outbox.queued != null -> stringResource(R.string.video_queued_chip, outbox.name)
        outbox.phase == VideoOutbox.Phase.COMPRESSING -> stringResource(R.string.video_compressing_chip, outbox.name)
        else -> stringResource(R.string.video_ready_chip, outbox.name)
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText, modifier = Modifier.weight(1f), maxLines = 2)
        TextButton(onClick = { outbox.cancel() }) { Text(stringResource(R.string.video_remove), color = Theme.colors.accent) }
    }
}
