package com.sphinx.peel.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sphinx.peel.ui.theme.Theme

/// Under the top bar on every screen while on a call with the call screen
/// put away — PeelCore's own `IOSOnCallBar`: a dot, the chat and the
/// running time, Mute, and Leave. Tapping the row itself (not either
/// button) brings the call screen back; tapping Leave ends the call from
/// right here, without opening it first.
@Composable
fun CallBanner(phase: CallPhase, onTap: () -> Unit, onLeave: () -> Unit) {
    val session = (phase as? CallPhase.Active)?.session
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onTap).background(Theme.colors.fieldFill)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (session?.state == CallConnectionState.CONNECTED) Theme.colors.confirmed else Theme.colors.secondaryText),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(phase.title, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text, maxLines = 1)
                Text(
                    when (phase) {
                        is CallPhase.Connecting -> "Connecting…"
                        is CallPhase.Failed -> phase.error
                        is CallPhase.Active -> CallStatusText(phase.session)
                    },
                    style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText, maxLines = 1,
                )
            }
            if (session != null) {
                IconButton(onClick = session::toggleMicrophone, enabled = !session.micRefused) {
                    Icon(
                        if (session.micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = if (session.micOn) "Mute" else "Unmute",
                        tint = if (session.micOn) Theme.colors.text else Theme.colors.secondaryText,
                    )
                }
            }
            IconButton(onClick = onLeave) {
                Icon(Icons.Filled.CallEnd, contentDescription = "Leave the call", tint = Theme.colors.destructive)
            }
        }
        HorizontalDivider(color = Theme.colors.divider)
    }
}
