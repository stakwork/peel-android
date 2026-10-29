package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.PeelLogo
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.theme.Theme
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/// One chat (docs/design/iphone.md, screen 2 — `ConversationScreen.swift`):
/// a header with the back arrow and the chat's name, its messages flat
/// (not bubbles — a run's first row carries the avatar and name in the
/// sender's colour, the rest of the run under it, same as the Mac/iPhone),
/// and the composer at the bottom. No reply/thread, calls, pinned message,
/// mentions or boosts yet — see `docs/phases/05-conversation.md`.
@Composable
fun ConversationScreen(engine: Engine, storage: Storage, chat: String, onBack: () -> Unit) {
    val isAgent = chat == engine.agentPubkey
    var conversation by remember(chat) { mutableStateOf<com.sphinx.peel.storage.ConversationRow?>(null) }
    var messages by remember(chat) { mutableStateOf(emptyList<MessageRow>()) }
    var draft by remember(chat) { mutableStateOf("") }
    var error by remember(chat) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val restoreProgress by engine.restoreProgress.collectAsState()

    suspend fun reload() {
        conversation = withContext(Dispatchers.Default) { storage.conversations().firstOrNull { it.chat == chat } }
        messages = withContext(Dispatchers.Default) { storage.messages(chat) }
        // Viewing a chat marks it read, here and on the server — every
        // reload, not only the first, so a message that arrives while
        // this screen is still open is caught too. Cheap when there is
        // nothing new: `markRead` is a no-op past the first call for the
        // same marker (`Storage.setRead`'s own "did anything change" check).
        engine.markRead(chat)
    }
    LaunchedEffect(chat, restoreProgress) { reload() }
    LaunchedEffect(chat) {
        while (true) {
            delay(3_000)
            reload()
        }
    }

    val isTribe = conversation?.isTribe ?: false
    val title = if (isAgent) "Peel" else (conversation?.name ?: chat.take(12))
    val myAlias = storage.value(com.sphinx.peel.storage.Setting.ALIAS)?.takeIf { it.isNotEmpty() } ?: "You"

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Theme.colors.text)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Theme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isAgent) {
                Spacer(Modifier.width(8.dp))
                AgentTag()
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            itemsIndexed(messages, key = { _, row -> row.id.takeIf { it != 0L } ?: (row.uuid ?: row.hashCode()) }) { index, row ->
                val previous = messages.getOrNull(index - 1)
                val startsRun = previous == null || runSenderKey(previous) != runSenderKey(row)
                MessageRowView(row = row, startsRun = startsRun, isAgent = isAgent, isTribe = isTribe, myAlias = myAlias)
            }
        }

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Theme.colors.destructive,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Composer(
            text = draft,
            onTextChange = { draft = it },
            placeholder = if (isAgent) "Ask Peel" else "Message $title",
            onSend = {
                val toSend = draft
                draft = ""
                error = null
                scope.launch {
                    try {
                        engine.sendMessage(chat, toSend, isTribe)
                        reload()
                    } catch (e: Exception) {
                        error = e.message ?: "Could not send that."
                    }
                }
            },
        )
    }
}

/// What separates one run of messages from the next: the account itself
/// for anything `fromMe` (one voice, whoever is actually signed in), or
/// the member's own alias for a tribe's other messages — the chat's own
/// pubkey is shared by every member there, so it cannot tell them apart
/// (`Engine.contactHint`'s own comment on why).
private fun runSenderKey(row: MessageRow): String {
    if (row.fromMe) return "me"
    val sender = row.sender?.let { runCatching { JSONObject(it) }.getOrNull() }
    return sender?.optString("alias")?.takeIf { it.isNotEmpty() } ?: row.chat
}

@Composable
private fun MessageRowView(row: MessageRow, startsRun: Boolean, isAgent: Boolean, isTribe: Boolean, myAlias: String) {
    val sender = row.sender?.let { runCatching { JSONObject(it) }.getOrNull() }
    val name = if (row.fromMe) myAlias else (sender?.optString("alias")?.takeIf { it.isNotEmpty() } ?: "Someone")
    val photoUrl = if (row.fromMe) null else sender?.optString("photo_url")?.takeIf { it.isNotEmpty() }
    val hueKey = sender?.optString("pubkey")?.takeIf { it.isNotEmpty() } ?: row.chat
    val content = row.body?.let { runCatching { JSONObject(it).optString("content") }.getOrNull() }.orEmpty()
    val time = row.timestamp?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(it) }

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (row.fromMe) Theme.colors.accent.copy(alpha = 0.07f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(top = if (startsRun) 8.dp else 1.dp, bottom = 2.dp),
    ) {
        Box(Modifier.width(32.dp)) {
            if (startsRun) {
                if (isAgent && row.fromMe.not()) {
                    PeelLogo(size = 32.dp)
                } else {
                    Avatar(name = name, url = photoUrl, size = 32.dp, hue = PersonHue.of(hueKey), shape = AvatarShape.CIRCLE)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (startsRun) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PersonHue.of(hueKey).fill,
                        maxLines = 1,
                    )
                    if (time != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(time, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
                    }
                }
            }
            Text(
                content,
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.colors.text,
                modifier = Modifier.alpha(if (row.status == "PENDING") 0.6f else 1f),
            )
            if (row.status == "FAILED") {
                Text("Not delivered", style = MaterialTheme.typography.labelSmall, color = Theme.colors.destructive)
            }
        }
    }
}

@Composable
private fun AgentTag() {
    Text(
        "AGENT",
        style = MaterialTheme.typography.labelSmall,
        color = Theme.colors.secondaryText,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Theme.colors.fieldFill)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/// The capsule text field and send button — PeelCore's `PhoneComposer`,
/// trimmed to text only: no attach, mentions, voice or the sats bolt.
@Composable
private fun Composer(text: String, onTextChange: (String) -> Unit, placeholder: String, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Theme.colors.fieldFill)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.secondaryText)
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Theme.colors.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Theme.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val canSend = text.isNotBlank()
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Theme.colors.button.copy(alpha = if (canSend) 1f else 0.4f))
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Theme.colors.buttonText)
        }
    }
}
