package com.sphinx.peel.ui.mentions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.ClipboardUtil
import com.sphinx.peel.engine.Mentions
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.MessageText
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.dashboard.previewContent
import com.sphinx.peel.ui.dashboard.senderDisplay
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/// Messages that mention you, newest first — PeelCore's `MentionsScreen`:
/// the newest 200 across your tribes, refreshed while open. A row opens its
/// chat at that message (`onOpenMessage(chat, uuid, threadUuid)`).
@Composable
fun MentionsScreen(
    storage: Storage,
    onBack: () -> Unit,
    onOpenMessage: (chat: String, uuid: String, threadUuid: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var rows by remember { mutableStateOf(emptyList<MessageRow>()) }
    var tribeNames by remember { mutableStateOf(emptyMap<String, String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    // The first poll hasn't answered yet: "No mentions yet" would be a lie
    // this early, so the loading state holds until it has, same as `rows`
    // being empty after that really does mean none.
    var loaded by remember { mutableStateOf(false) }
    val myAlias = storage.value(Setting.ALIAS)?.takeIf { it.isNotEmpty() } ?: "You"

    // No reactive query layer, so a plain poll, as the chat list does.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val loadedRows = withContext(Dispatchers.Default) { storage.mentions(myAlias) }
                tribeNames = withContext(Dispatchers.Default) { storage.conversations().associate { it.chat to it.name } }
                rows = loadedRows
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
            }
            loaded = true
            delay(3_000)
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Theme.colors.text) }
            }
            Spacer(Modifier.width(12.dp))
            Text("Mentions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
        }
        val loadError = error
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Theme.colors.accent)
            }
        } else if (rows.isEmpty()) {
            if (loadError != null) {
                EmptyState("Mentions did not load", loadError, icon = false)
            } else {
                EmptyState("No mentions yet", "When someone in a tribe mentions you, it shows here.", icon = true)
            }
        } else {
            val aliases = remember(myAlias) { setOf(Mentions.tribeAlias(myAlias)) }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(rows, key = { it.id.takeIf { id -> id != 0L } ?: (it.uuid ?: it.hashCode()) }) { row ->
                    MentionRow(row, tribeNames[row.chat] ?: row.chat.take(12), aliases, myAlias, onOpenMessage)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String, icon: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon) {
            Icon(Icons.Filled.AlternateEmail, contentDescription = null, tint = Theme.colors.secondaryText, modifier = Modifier.size(40.dp))
            Spacer(Modifier.size(12.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = Theme.colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.size(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText, textAlign = TextAlign.Center)
    }
}

/// One mention: the sender's avatar, then their name and `in <tribe>` with
/// the date and time at the end, and the message under it, your own mention
/// as a chip. Tap opens it in its chat; long-press for the menu.
@Composable
private fun MentionRow(
    row: MessageRow,
    tribeName: String,
    aliases: Set<String>,
    myAlias: String,
    onOpenMessage: (chat: String, uuid: String, threadUuid: String?) -> Unit,
) {
    val (name, photoUrl, hueKey) = senderDisplay(row, myAlias, null, isTribe = true)
    val content = previewContent(row)
    val context = LocalContext.current
    val whenText = row.timestamp?.let {
        android.text.format.DateFormat.getMediumDateFormat(context).format(it) + " " +
            android.text.format.DateFormat.getTimeFormat(context).format(it)
    }
    var menuOpen by remember { mutableStateOf(false) }
    val uuid = row.uuid

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .combinedClickable(
                    onClick = { if (uuid != null) onOpenMessage(row.chat, uuid, row.threadUuid) },
                    onLongClick = { menuOpen = true },
                )
                .padding(vertical = 7.dp),
        ) {
            Avatar(name = name, url = photoUrl, size = 30.dp, hue = PersonHue.of(hueKey), shape = AvatarShape.CIRCLE)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PersonHue.of(hueKey).fill,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "in $tribeName",
                        style = MaterialTheme.typography.labelSmall,
                        color = Theme.colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    if (whenText != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(whenText, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText, maxLines = 1)
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    MessageText(content, myAliases = aliases, maxLines = 4)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (uuid != null) {
                DropdownMenuItem(
                    text = { Text("Open in $tribeName") },
                    onClick = { menuOpen = false; onOpenMessage(row.chat, uuid, row.threadUuid) },
                )
            }
            if (content.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = { menuOpen = false; ClipboardUtil.copySensitive(context, "message", content) },
                )
            }
        }
    }
}
