package com.sphinx.peel.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.CallChat
import com.sphinx.peel.ui.theme.Theme

/// The in-call chat as a side panel over the right of the call screen —
/// plain text lines to everyone in the room, over LiveKit's data channel
/// (`CallChat`). Opening it clears the unread count.
@Composable
fun CallChatPanel(session: CallSession, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val messages = session.chatMessages
    val list = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }
    Column(
        modifier.fillMaxHeight().widthIn(max = 340.dp).fillMaxWidth(0.86f).background(Theme.colors.background).imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chat", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Theme.colors.text)
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close the chat", tint = Theme.colors.text) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Text(
                    "Messages here are only for people on this call.",
                    Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
                )
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages, key = { it.identity + it.id }) { line ->
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = if (line.isYou) Alignment.End else Alignment.Start,
                    ) {
                        Text(line.name, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
                        Text(
                            line.text,
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .background(if (line.isYou) Theme.colors.accent.copy(alpha = 0.25f) else Theme.colors.fieldFill)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val send = {
                if (CallChat.outgoing(draft) != null) {
                    session.sendChat(draft)
                    draft = ""
                }
            }
            OutlinedTextField(
                draft, { draft = it.take(CallChat.MAX_LENGTH) }, Modifier.weight(1f),
                placeholder = { Text("Message") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            IconButton(onClick = send, enabled = CallChat.outgoing(draft) != null) {
                Icon(Icons.Filled.Send, contentDescription = "Send", tint = Theme.colors.accent)
            }
        }
    }
}
