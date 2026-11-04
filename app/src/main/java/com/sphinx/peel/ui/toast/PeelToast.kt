package com.sphinx.peel.ui.toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.delay

/// Bottom-aligned, drawn once at the app root. Sits inside the root's
/// `safeDrawingPadding`, so it clears the navigation bar and the keyboard
/// (and so the message input).
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    val current = state.current
    // Keep the last toast around while it animates out.
    var shown by remember { mutableStateOf(current) }
    if (current != null) shown = current

    current?.let { toast ->
        LaunchedEffect(toast.id) {
            toast.durationMs?.let {
                delay(it)
                state.dismiss(toast.id)
            }
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = current != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            shown?.let { PeelToast(it.message, it.type, onClose = { state.dismiss(it.id) }) }
        }
    }
}

/// The banner itself: stateless.
@Composable
fun PeelToast(message: String, type: ToastType, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val tint = type.color()
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tint),
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(type.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.colors.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = Theme.colors.secondaryText, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ToastType.color(): Color = when (this) {
    ToastType.Info -> Theme.colors.accent
    ToastType.Success -> Theme.colors.confirmed
    ToastType.Warning -> Theme.colors.attention
    ToastType.Error -> Theme.colors.destructive
}

private fun ToastType.icon(): ImageVector = when (this) {
    ToastType.Info -> Icons.Outlined.Info
    ToastType.Success -> Icons.Outlined.CheckCircle
    ToastType.Warning, ToastType.Error -> Icons.Outlined.ErrorOutline
}
