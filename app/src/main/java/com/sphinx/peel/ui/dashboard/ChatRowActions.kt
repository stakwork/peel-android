package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.MuteLevel
import com.sphinx.peel.engine.ReadToggle
import com.sphinx.peel.storage.ConversationRow
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/// Swipe and long-press actions of a chat-list row — iOS's `ChatLink`
/// (`ChatsTab.swift`): swipe right marks the chat read or unread, swipe
/// left mutes or unmutes it, and a long press has the same plus its
/// notification level ("All messages", "Mentions only" in a tribe,
/// "Nothing (mute)"). iOS has no pin, archive or delete for a chat, so
/// neither does this. A swipe runs its action and the row springs back
/// (iOS's non-destructive swipe), the list reloading through [onChanged].
/// A refusal (mute needs a connection, as on iOS) shows as a toast.
@OptIn(ExperimentalMaterial3Api::class)
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

    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> toggle?.let(::runToggle)
                SwipeToDismissBoxValue.EndToStart -> setLevel(if (level == MuteLevel.MUTED) MuteLevel.ALL else MuteLevel.MUTED)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // Never dismissed: the row springs back, whatever was done.
            false
        },
    )

    Box {
        SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = toggle != null,
            backgroundContent = {
                val (icon, label, fromStart) = when (state.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd -> Triple(toggle.icon(), toggle?.title ?: "", true)
                    else -> if (level == MuteLevel.MUTED) {
                        Triple(Icons.Filled.Notifications, "Unmute", false)
                    } else {
                        Triple(Icons.Filled.NotificationsOff, "Mute", false)
                    }
                }
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(if (fromStart) Theme.colors.accent else Theme.colors.secondaryText)
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = if (fromStart) androidx.compose.foundation.layout.Arrangement.Start else androidx.compose.foundation.layout.Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, tint = Theme.colors.buttonText, modifier = Modifier.size(22.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = Theme.colors.buttonText,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            },
        ) {
            // Opaque, so the swipe's colour only shows where the row has moved off.
            Box(Modifier.background(Theme.colors.background)) { content { menuOpen = true } }
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

private fun ReadToggle?.icon(): ImageVector = when (this) {
    ReadToggle.MARK_UNREAD -> Icons.Filled.MarkEmailUnread
    else -> Icons.Filled.CheckCircle
}
