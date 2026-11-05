package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.SendRetry
import com.sphinx.peel.engine.SendStatus
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// The clock a row's undelivered state is judged by: a send of ours still
/// pending turns retryable 30 s after it went, with nothing in storage
/// changing, so a pending row re-reads the time every few seconds.
/// Anything else needs no ticking.
@Composable
fun rememberSendClock(row: MessageRow): State<Long> =
    produceState(System.currentTimeMillis(), row.status, row.timestamp) {
        value = System.currentTimeMillis()
        while (row.fromMe && row.status == SendStatus.PENDING.raw && row.type in SendRetry.RESENDABLE_TYPES) {
            delay(5_000)
            value = System.currentTimeMillis()
        }
    }

/// "Not delivered" (or "Not confirmed") with Retry and Discard under a
/// send of ours — iOS's `UndeliveredLine`, the Mac's `UndeliveredActions`.
/// Retry goes through `Engine.resendMessage`, which reuses this row; Discard
/// asks once, in place, and is the only way a message that never went leaves.
/// A refusal shows below, as iOS's `ProblemText` does.
@Composable
fun UndeliveredLine(row: MessageRow, engine: Engine, now: Long, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var confirmingDiscard by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val retryable = SendRetry.isRetryable(row, now)
    val discardable = SendRetry.isDiscardable(row, now)

    Column(modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 28.dp),
        ) {
            if (confirmingDiscard) {
                Text("Discard, never sent?", style = MaterialTheme.typography.labelMedium, color = Theme.colors.secondaryText)
                Text(
                    "Discard",
                    style = MaterialTheme.typography.labelMedium,
                    color = Theme.colors.destructive,
                    modifier = Modifier.clickable {
                        confirmingDiscard = false
                        if (!engine.discardMessage(row.id)) problem = "That message can no longer be discarded."
                    },
                )
                Text(
                    "Cancel",
                    style = MaterialTheme.typography.labelMedium,
                    color = Theme.colors.secondaryText,
                    modifier = Modifier.clickable { confirmingDiscard = false },
                )
            } else {
                Text(SendRetry.undeliveredLabel(row), style = MaterialTheme.typography.labelMedium, color = Theme.colors.destructive)
                if (retryable) {
                    Text(
                        "Retry",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (working) Theme.colors.secondaryText else Theme.colors.link,
                        modifier = Modifier.clickable(enabled = !working) {
                            working = true
                            problem = null
                            scope.launch {
                                try {
                                    engine.resendMessage(row.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    problem = "Could not send again. ${e.message ?: ""}".trim()
                                } finally {
                                    working = false
                                }
                            }
                        },
                    )
                }
                if (discardable) {
                    Text(
                        "Discard",
                        style = MaterialTheme.typography.labelMedium,
                        color = Theme.colors.secondaryText,
                        modifier = Modifier.clickable { confirmingDiscard = true },
                    )
                }
            }
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Theme.colors.destructive, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}
