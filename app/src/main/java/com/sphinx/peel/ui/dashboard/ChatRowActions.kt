package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.MuteLevel
import com.sphinx.peel.engine.ReadToggle
import com.sphinx.peel.storage.ConversationRow
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/// Swipe and long-press actions of a chat-list row — iOS's `ChatLink`
/// (`ChatsTab.swift`'s `.swipeActions`): a swipe reveals a fixed-width
/// button at that edge (trailing: mark read/unread, leading: mute/unmute)
/// which stays open until tapped. The swipe gesture itself never performs
/// the action — iOS's trailing edge is `allowsFullSwipe: false`, and this
/// matches that for both edges rather than auto-firing on a far-enough
/// drag. Tapping the row elsewhere while open closes it instead of
/// opening the chat, same as tapping away from iOS's revealed actions. A
/// long press has the same two actions plus the notification level ("All
/// messages", "Mentions only" in a tribe, "Nothing (mute)"). iOS has no
/// pin, archive or delete for a chat, so neither does this. A refusal
/// (mute needs a connection, as on iOS) shows as a toast.
@Composable
fun ChatRowActions(
    conversation: ConversationRow,
    engine: Engine,
    onChanged: () -> Unit,
    content: @Composable (onLongClick: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var menuOpen by remember { mutableStateOf(false) }
    val toggle = conversation.readToggle
    val level = MuteLevel.of(conversation.muteLevel)

    fun attempt(what: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show("Could not $what. ${e.message ?: ""}".trim(), ToastType.Warning)
            }
            onChanged()
        }
    }

    fun runToggle(t: ReadToggle) = when (t) {
        ReadToggle.MARK_READ -> attempt("mark as read") { engine.markRead(conversation.chat) }
        ReadToggle.MARK_UNREAD -> attempt("mark as unread") { engine.markUnread(conversation.chat) }
    }

    fun setLevel(next: MuteLevel) {
        if (next != level) attempt("change notifications") { engine.setMute(conversation.chat, next) }
    }

    val density = LocalDensity.current
    val revealWidth = 112.dp
    val revealPx = with(density) { revealWidth.toPx() }
    val hasReadAction = toggle != null
    val anchors = remember(hasReadAction, revealPx) {
        DraggableAnchors<SwipeAnchor> {
            SwipeAnchor.Closed at 0f
            if (hasReadAction) SwipeAnchor.OpenStart at revealPx
            SwipeAnchor.OpenEnd at -revealPx
        }
    }
    // Anchors are set synchronously here, not via LaunchedEffect: an effect
    // only runs after the first layout pass, and the offset below is read
    // during that very first layout — requireOffset() throws if nothing
    // has initialized it yet.
    val state = remember(anchors) {
        AnchoredDraggableState(initialValue = SwipeAnchor.Closed).apply { updateAnchors(anchors) }
    }
    fun close() {
        scope.launch { state.snapTo(SwipeAnchor.Closed) }
    }

    Box {
        // The revealed buttons, fixed at each edge of the row. The content
        // box above slides off them on a drag; they never move themselves,
        // so a swipe only ever uncovers a button — it does not run it.
        Row(Modifier.matchParentSize()) {
            if (toggle != null) {
                SwipeActionButton(toggle.icon(), toggle.title, Theme.colors.accent, revealWidth) {
                    runToggle(toggle)
                    close()
                }
            }
            Box(Modifier.weight(1f))
            val muted = level == MuteLevel.MUTED
            SwipeActionButton(
                icon = if (muted) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
                label = if (muted) "Unmute" else "Mute",
                color = Theme.colors.secondaryText,
                width = revealWidth,
            ) {
                setLevel(if (muted) MuteLevel.ALL else MuteLevel.MUTED)
                close()
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(state.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(state, Orientation.Horizontal)
                // Opaque, so a button behind only shows where the row has moved off.
                .background(Theme.colors.background),
        ) {
            content { menuOpen = true }
            if (state.currentValue != SwipeAnchor.Closed || state.targetValue != SwipeAnchor.Closed) {
                // A button is showing: tapping the row closes it instead of
                // opening the chat, same as tapping away from iOS's actions.
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { close() },
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            toggle?.let { t ->
                DropdownMenuItem(
                    text = { Text(t.title) },
                    leadingIcon = { Icon(t.icon(), contentDescription = null) },
                    onClick = { menuOpen = false; runToggle(t) },
                )
            }
            Text(
                "Notify me about",
                style = MaterialTheme.typography.labelSmall,
                color = Theme.colors.secondaryText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            for (choice in MuteLevel.choices(conversation.isTribe)) {
                DropdownMenuItem(
                    text = { Text(choice.title) },
                    trailingIcon = if (choice == level) ({ Icon(Icons.Filled.Check, contentDescription = "Selected") }) else null,
                    onClick = { menuOpen = false; setLevel(choice) },
                )
            }
        }
    }
}

private enum class SwipeAnchor { Closed, OpenStart, OpenEnd }

@Composable
private fun SwipeActionButton(icon: ImageVector, label: String, color: Color, width: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = Theme.colors.buttonText, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Theme.colors.buttonText, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun ReadToggle?.icon(): ImageVector = when (this) {
    ReadToggle.MARK_UNREAD -> Icons.Filled.MarkEmailUnread
    else -> Icons.Filled.CheckCircle
}
