package com.sphinx.peel.ui.threads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.MessageMarkup
import com.sphinx.peel.engine.Mentions
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.storage.ThreadListItem
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.MessageText
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.dashboard.previewContent
import com.sphinx.peel.ui.dashboard.senderDisplay
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// The Dashboard's Threads row — `ChatsLists.swift`'s `ThreadsScreen`: the
/// threads this account wrote in (`Storage.threads()`), newest activity
/// first. Tapping one opens its chat straight into the thread
/// (`onOpenThread(chat, rootUuid)`). Opening at the first new reply (iOS)
/// is not done: the thread screen has no scroll-to-message yet.
@Composable
fun ThreadsScreen(
    engine: Engine,
    storage: Storage,
    onBack: () -> Unit,
    onOpenThread: (chat: String, rootUuid: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var threads by remember { mutableStateOf(emptyList<ThreadListItem>()) }
    // The first reload hasn't answered yet: "No threads yet" would be a lie
    // this early, so the loading state holds until it has, same as
    // `threads` being empty after that really does mean none.
    var loaded by remember { mutableStateOf(false) }
    val restoreProgress by engine.restoreProgress.collectAsState()
    val scope = rememberCoroutineScope()
    val myAlias = remember { storage.value(Setting.ALIAS)?.takeIf { it.isNotEmpty() } ?: "You" }
    val myPhotoUrl = remember { storage.value(Setting.PHOTO_URL)?.takeIf { it.isNotEmpty() } }

    // Refreshes the way the chats list does: on every restore step, plus a poll.
    suspend fun reload() {
        threads = withContext(Dispatchers.Default) { storage.threads() }
        loaded = true
    }
    LaunchedEffect(restoreProgress) { reload() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            reload()
        }
    }

    // "Mark read" marks the whole chat read, like the Mac.
    fun markRead(chats: Collection<String>) {
        scope.launch {
            chats.forEach { engine.markRead(it) }
            reload()
        }
    }
    val unreadChats = threads.filter { it.unread > 0 }.map { it.conversation.chat }.distinct()

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Theme.colors.text) }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Threads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                color = Theme.colors.text, modifier = Modifier.weight(1f),
            )
            if (unreadChats.isNotEmpty()) {
                TextButton(onClick = { markRead(unreadChats) }) { Text("Mark all read", color = Theme.colors.accent) }
            }
        }
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Theme.colors.accent)
            }
        } else if (threads.isEmpty()) {
            EmptyThreads()
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(threads, key = { "${it.conversation.chat}-${it.root.uuid}" }) { item ->
                    ThreadRow(
                        item, myAlias, myPhotoUrl,
                        onOpen = { item.root.uuid?.let { onOpenThread(item.conversation.chat, it) } },
                        onMarkRead = { markRead(listOf(item.conversation.chat)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyThreads() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = Theme.colors.secondaryText, modifier = Modifier.size(40.dp))
        Spacer(Modifier.size(12.dp))
        Text("No threads yet", style = MaterialTheme.typography.titleMedium, color = Theme.colors.text)
        Spacer(Modifier.size(4.dp))
        Text(
            "Threads you start or reply in show here, with their new replies.",
            style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ThreadRow(item: ThreadListItem, myAlias: String, myPhotoUrl: String?, onOpen: () -> Unit, onMarkRead: () -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val isTribe = item.conversation.isTribe
    val (rootName, rootPhoto, rootHueKey) = senderDisplay(item.root, myAlias, myPhotoUrl, isTribe)
    val rootContent = previewContent(item.root)
    val rootIsBlank = MessageMarkup.plainText(rootContent).isBlank()
    val rootFallback = attachmentFallback(item.root)
    val unread = item.unread > 0

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 20.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Avatar(name = rootName, url = rootPhoto, size = 30.dp, hue = PersonHue.of(rootHueKey), shape = AvatarShape.CIRCLE)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.conversation.name, style = MaterialTheme.typography.labelMedium, color = Theme.colors.secondaryText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        relativeTime(context, item.lastTimestamp),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = Theme.colors.secondaryText,
                    )
                }
                Text(
                    rootName,
                    style = MaterialTheme.typography.bodyMedium.copy(color = PersonHue.of(rootHueKey).fill, fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (rootIsBlank) {
                    Text(
                        rootFallback,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                        color = Theme.colors.text,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // The same markdown as a chat bubble, capped at two lines.
                    MessageText(
                        rootContent,
                        myAliases = remember(myAlias) { Mentions.ownAliases(myAlias) },
                        maxLines = 2,
                    )
                }
                Text(
                    buildAnnotatedString {
                        if (unread) {
                            withStyle(SpanStyle(color = Theme.colors.accent, fontWeight = FontWeight.SemiBold)) { append("${item.unread} new") }
                            append(" · ")
                        }
                        append(if (item.count == 1) "1 reply" else "${item.count} replies")
                    },
                    style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
                )
                val latest = item.latest
                val (latestName, _, latestHueKey) = senderDisplay(latest, myAlias, myPhotoUrl, isTribe)
                val latestText = MessageMarkup.plainText(previewContent(latest)).ifBlank { attachmentFallback(latest) }
                if (latestText.isNotEmpty()) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = PersonHue.of(latestHueKey).fill, fontWeight = FontWeight.SemiBold)) { append("$latestName: ") }
                            append(latestText)
                        },
                        style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Open thread") }, onClick = { menuOpen = false; onOpen() })
            if (unread) DropdownMenuItem(text = { Text("Mark read") }, onClick = { menuOpen = false; onMarkRead() })
        }
    }
}

private fun attachmentFallback(row: MessageRow): String = if (row.type == 6) "Attachment" else ""

/// Time of day for today, "Yesterday", else a short date. ChatsScreen has
/// no formatter of its own to share (its rows show no time).
private fun relativeTime(context: android.content.Context, timestamp: Long): String {
    val now = java.util.Calendar.getInstance()
    fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
        a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    if (sameDay(now, then)) return android.text.format.DateFormat.getTimeFormat(context).format(timestamp)
    now.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (sameDay(now, then)) return "Yesterday"
    return android.text.format.DateFormat.getMediumDateFormat(context).format(timestamp)
}
