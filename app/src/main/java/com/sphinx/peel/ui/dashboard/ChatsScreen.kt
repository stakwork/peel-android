package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin as OutlinedPushPin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sphinx.peel.R
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.InviteRecord
import com.sphinx.peel.engine.dateLabel
import com.sphinx.peel.storage.ConversationRow
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.call.rememberLiveCallCounts
import com.sphinx.peel.ui.components.AgentTag
import com.sphinx.peel.ui.components.CapsuleChip
import com.sphinx.peel.ui.components.ConversationAvatar
import com.sphinx.peel.ui.components.PeelLogo
import com.sphinx.peel.ui.components.countText
import com.sphinx.peel.ui.theme.Theme
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/// The pages and forms Chats opens over the tabs (`MainTabs`): Threads and Mentions from the More menu or a pinned chip, the four
/// New chat forms from the New chat menu or the empty state.
enum class DashboardRoute { THREADS, MENTIONS, ADD_CONTACT, INVITE, JOIN_TRIBE, CREATE_TRIBE }

/// "Chats" (docs/phases/58-chats-one-list.md; the iPhone's `ChatsTab`, Tom 2026-11-27): one list, newest first. The bar: More
/// (•••, Mentions and Threads with what is new) on the left, the title, then the
/// connection bolt (unchanged) and New chat (Add contact, Invite someone, Join a tribe, Create a tribe). No search, no floating
/// button.
@Composable
fun ChatsScreen(engine: Engine, storage: Storage, modifier: Modifier = Modifier, onOpenRoute: (DashboardRoute) -> Unit = {}, onOpenInvite: (String) -> Unit = {}, onOpenChat: (String) -> Unit = {}) {
    val connection by engine.connectionState.collectAsState()
    // Held outside the composition: a tab switch keeps the last list and refreshes behind it.
    val listState = remember(storage) { ChatListState.of(storage) }
    // The pages pinned above the chats (from each page's own bar): PeelCore's `sidebarLayout` (`pinnedPages`), kept on the device.
    val layoutState = remember(storage) { SidebarLayoutState.of(storage) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatsMoreMenu(mentions = listState.unreadMentions, threads = listState.unreadThreads, onOpen = onOpenRoute)
            Text(
                stringResource(R.string.chats_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Theme.colors.text,
                modifier = Modifier.weight(1f),
            )
            ConnectionBolt(connected = connection == ConnectionState.Connected)
            Spacer(Modifier.width(4.dp))
            NewChatMenu(onOpenRoute)
        }
        com.sphinx.peel.ui.diagnostics.DiagnosticsNotice(storage)
        ChatListBody(engine, storage, listState, layoutState, modifier = Modifier.weight(1f), onOpenRoute = onOpenRoute, onOpenInvite = onOpenInvite, onOpenChat = onOpenChat)
    }
}

/// Mentions' and Threads' words and icons (the iPhone's `ChatsPage.title`/`systemImage`).
@Composable
private fun ChatsPage.title(): String = stringResource(if (this == ChatsPage.MENTIONS) R.string.chats_mentions else R.string.chats_threads)

private val ChatsPage.icon: ImageVector
    get() = if (this == ChatsPage.MENTIONS) Icons.Filled.AlternateEmail else Icons.Filled.ChatBubbleOutline

/// "3 new", the count a page has.
@Composable
private fun newCount(count: Long): String =
    LocalContext.current.resources.getQuantityString(R.plurals.ppl_new_count, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count)

/// ••• on Chats (the iPhone's `ChatsMoreMenu`, without its "Show above your chats": Tom, 2026-11-27, "pin from the page"):
/// Mentions and Threads with how many are new, each opening its page. Each page pins itself from its own bar ([PagePinButton]).
@Composable
private fun ChatsMoreMenu(mentions: Long, threads: Long, onOpen: (DashboardRoute) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.chats_pages), tint = Theme.colors.text)
        }
        DropdownMenu(open, { open = false }) {
            for (page in ChatsPage.entries) {
                val count = if (page == ChatsPage.MENTIONS) mentions else threads
                DropdownMenuItem(
                    text = { Text(page.title()) },
                    leadingIcon = { Icon(page.icon, contentDescription = null) },
                    trailingIcon = if (count > 0) {
                        { Text(newCount(count), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText) }
                    } else {
                        null
                    },
                    onClick = { open = false; onOpen(page.route) },
                )
            }
        }
    }
}

/// The pin at the trailing end of the Mentions and Threads pages' bars: pins [page] above the chats (a chip, [PinnedPages]) or
/// takes it off, in the layout's `pinnedPages`. Outlined in the secondary colour while not pinned, the accent while pinned.
@Composable
fun PagePinButton(page: ChatsPage, storage: Storage) {
    val layoutState = remember(storage) { SidebarLayoutState.of(storage) }
    val pinned = layoutState.layout.isPagePinned(page.key)
    IconButton(onClick = { layoutState.change(layoutState.layout.withPagePinned(page.key, !pinned)) }) {
        Icon(
            if (pinned) Icons.Filled.PushPin else Icons.Outlined.OutlinedPushPin,
            contentDescription = stringResource(if (pinned) R.string.chats_remove_from_chats else R.string.chats_show_on_chats),
            tint = if (pinned) Theme.colors.accent else Theme.colors.secondaryText,
        )
    }
}

/// New chat on Chats (the iPhone's `NewChatMenu`): the four ways in, each opening the form the list's "+" rows used to.
@Composable
private fun NewChatMenu(onOpenRoute: (DashboardRoute) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chats_new_chat), tint = Theme.colors.text)
        }
        DropdownMenu(open, { open = false }) {
            listOf(
                Triple(R.string.chats_add_contact, Icons.Filled.PersonAdd, DashboardRoute.ADD_CONTACT),
                Triple(R.string.chats_invite_someone, Icons.Filled.Mail, DashboardRoute.INVITE),
                Triple(R.string.chats_join_tribe, Icons.Filled.Groups, DashboardRoute.JOIN_TRIBE),
                Triple(R.string.chats_create_tribe, Icons.Filled.AddComment, DashboardRoute.CREATE_TRIBE),
            ).forEach { (title, icon, route) ->
                DropdownMenuItem(
                    text = { Text(stringResource(title)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    onClick = { open = false; onOpenRoute(route) },
                )
            }
        }
    }
}

/// The list (the iPhone's `ChatList`): the pages pinned (from their own bars) as chips, then, with no chats yet, the empty state; then the
/// pending invites, Peel's row and every other chat newest first (`ChatSections.items`). Owns its data loading.
@Composable
private fun ChatListBody(
    engine: Engine,
    storage: Storage,
    listState: ChatListState,
    layoutState: SidebarLayoutState,
    modifier: Modifier = Modifier,
    onOpenRoute: (DashboardRoute) -> Unit = {},
    onOpenInvite: (String) -> Unit = {},
    onOpenChat: (String) -> Unit = {},
) {
    val conversations = listState.conversations
    // The invites still waiting (sending, ready, cancelling): a row each, first.
    val pendingInvites = listState.pendingInvites
    var refresh by remember { mutableStateOf(0) }
    val restoreProgress by engine.restoreProgress.collectAsState()
    val liveCalls = rememberLiveCallCounts(engine, storage)
    val layout = layoutState.layout

    // No reactive query layer yet (no Room, no Flow-backed DB) to push a
    // row the moment a live message lands — re-reads on every restore
    // step, which covers the one real test so far, plus a plain poll as
    // the honest fallback for anything that arrives outside a restore.
    suspend fun reload() {
        // One background hop for all reads, then publish together so the list never shows half-updated.
        val alias = storage.value(Setting.ALIAS)?.takeIf { it.isNotEmpty() } ?: "You"
        val read = withContext(Dispatchers.IO) {
            listOf(
                storage.conversations(),
                storage.unreadMentions(alias),
                storage.unreadThreadCount().toLong(),
                storage.invites().filter { it.isPending },
            )
        }
        @Suppress("UNCHECKED_CAST")
        run {
            listState.conversations = read[0] as List<ConversationRow>
            listState.unreadMentions = read[1] as Long
            listState.unreadThreads = read[2] as Long
            listState.pendingInvites = read[3] as List<InviteRecord>
            listState.loaded = true
        }
    }
    // `refresh`: a row action (read, unread, mute) just changed something.
    LaunchedEffect(restoreProgress, refresh) { reload() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            reload()
        }
    }

    val rows = remember(conversations, pendingInvites) { ChatSections.items(conversations, pendingInvites, engine.agentPubkey) }
    // In the enum's order, as the iPhone shows them (`ChatsPage.allCases`).
    val pinned = ChatsPage.entries.filter { layout.isPagePinned(it.key) }

    Box(modifier) {
        if (!listState.loaded && conversations.isEmpty()) {
            // First read still running: never the "No chats yet" placeholder.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Theme.colors.secondaryText)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp)) {
                if (pinned.isNotEmpty()) item(key = "pinned-pages") {
                    PinnedPages(
                        pinned, mentions = listState.unreadMentions, threads = listState.unreadThreads, onOpen = onOpenRoute,
                        onRemove = { layoutState.change(layoutState.layout.withPagePinned(it.key, false)) },
                    )
                }
                if (conversations.isEmpty()) item(key = "no-chats") {
                    NoChats(restoring = restoreProgress.restoring, onOpenRoute = onOpenRoute)
                }
                items(rows, key = { it.key }) { item ->
                    when (item) {
                        is ChatSections.Item.Invite -> PendingInviteRow(item.invite) { onOpenInvite(item.invite.code) }
                        is ChatSections.Item.Agent -> ChatRowActions(item.conversation, engine, onChanged = { refresh++ }) { onLongClick ->
                            ChatRow(item.conversation, onLongClick = onLongClick, isAgent = true) { onOpenChat(item.conversation.chat) }
                        }
                        is ChatSections.Item.Chat -> {
                            val conversation = item.conversation
                            ChatRowActions(conversation, engine, onChanged = { refresh++ }) { onLongClick ->
                                ChatRow(conversation, onCall = (liveCalls[conversation.chat] ?: 0) > 0, onLongClick = onLongClick) { onOpenChat(conversation.chat) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/// The pages pinned from their own bars, above the chats (the iPhone's `PinnedPages`): each a chip with its icon, title and how many are new,
/// opening its page; a long press offers Remove from Chats.
@Composable
private fun PinnedPages(pages: List<ChatsPage>, mentions: Long, threads: Long, onOpen: (DashboardRoute) -> Unit, onRemove: (ChatsPage) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (page in pages) key(page) {
            val count = if (page == ChatsPage.MENTIONS) mentions else threads
            var menu by remember { mutableStateOf(false) }
            val described = if (count > 0) newCount(count) else null
            Box {
                CapsuleChip(
                    page.title(), onClick = { onOpen(page.route) }, count = count, onLongClick = { menu = true },
                    modifier = Modifier.semantics { if (described != null) stateDescription = described },
                ) {
                    Icon(page.icon, contentDescription = null, tint = Theme.colors.secondaryText, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chats_remove_from_chats)) },
                        leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                        onClick = { menu = false; onRemove(page) },
                    )
                }
            }
        }
    }
}

/// No chats on the account yet (the iPhone's `NoChats`): that a restore is still bringing them, or, in Peel's words, the ways in.
/// The pending invites and Peel's row still follow it in the list.
@Composable
private fun NoChats(restoring: Boolean, onOpenRoute: (DashboardRoute) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(if (restoring) R.string.chats_restoring else R.string.chats_none_yet),
            style = MaterialTheme.typography.titleMedium,
            color = Theme.colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (restoring) R.string.chats_restoring_detail else R.string.chats_none_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = Theme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        if (!restoring) {
            Spacer(Modifier.height(16.dp))
            val width = Modifier.widthIn(min = 200.dp)
            Button(onClick = { onOpenRoute(DashboardRoute.ADD_CONTACT) }, modifier = width) { Text(stringResource(R.string.chats_add_contact)) }
            OutlinedButton(onClick = { onOpenRoute(DashboardRoute.JOIN_TRIBE) }, modifier = width) { Text(stringResource(R.string.chats_join_tribe)) }
            TextButton(onClick = { onOpenRoute(DashboardRoute.INVITE) }, modifier = width) { Text(stringResource(R.string.chats_invite_someone)) }
        }
    }
}

/// Green connected, orange not — PeelCore's `ConnectionBolt`
/// (`Theme.confirmed`/`Theme.attention`). Not a button yet: nothing here
/// has a connection panel to open.
@Composable
private fun ConnectionBolt(connected: Boolean) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Theme.colors.fieldFill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Bolt,
            contentDescription = if (connected) stringResource(R.string.chats_connected) else stringResource(R.string.chats_not_connected),
            tint = if (connected) Theme.colors.confirmed else Theme.colors.attention,
        )
    }
}

/// Two lines, as the Mac's and iPhone's chat lists: the name and its time alone on the first, so every time stays in
/// one column; the last message under it with the call, count, NEW, @ or muted bell at its end.
@Composable
private fun ChatRow(
    conversation: ConversationRow,
    onCall: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    isAgent: Boolean = false,
    onClick: () -> Unit,
) {
    val unread = conversation.shownUnread
    // Bold/bright is for something actually unread (PeelCore's own `lit`,
    // ChatListRow): a new contact's row text stays normal, with only the
    // NEW tag marking it.
    val bold = unread > 0
    val label = remember(conversation.lastTimestamp) {
        dateLabel(conversation.lastTimestamp, Instant.now(), ZoneId.systemDefault(), Locale.getDefault())
    }
    val line = conversation.previewLine
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isAgent) PeelLogo(size = 44.dp) else ConversationAvatar(conversation, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isAgent) "Peel" else conversation.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (bold) Theme.colors.text else Theme.colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isAgent) {
                        Spacer(Modifier.width(8.dp))
                        AgentTag()
                    }
                }
                if (line != null && label != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        fontSize = 12.sp,
                        color = Theme.colors.secondaryText.copy(alpha = 0.8f),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (line == null) ChatRowEnd(conversation, onCall)
            }
            if (line != null) PreviewLine(line, unread = unread > 0) { ChatRowEnd(conversation, onCall) }
        }
    }
}

/// What a chat holds for you, at its preview line's end: a call, then the count, NEW, @ or the muted bell.
@Composable
private fun ChatRowEnd(conversation: ConversationRow, onCall: Boolean) {
    val unread = conversation.shownUnread
    if (onCall) {
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.chats_on_call), tint = Theme.colors.confirmed, modifier = Modifier.size(16.dp))
    }
    if (unread > 0) {
        Spacer(Modifier.width(8.dp))
        UnreadBadge(unread)
    } else if (conversation.isNew) {
        Spacer(Modifier.width(8.dp))
        NewTag()
    } else {
        // Nothing unread to show as a count: the chat's own mute state as a glyph (bell-slash/"@").
        when (conversation.muteLevel) {
            2 -> {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.NotificationsOff, contentDescription = stringResource(R.string.chats_muted), tint = Theme.colors.secondaryText, modifier = Modifier.size(16.dp))
            }
            1 -> {
                Spacer(Modifier.width(8.dp))
                Text("@", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
            }
        }
    }
}

/// "NEW" on a contact who just added this account, until its chat is opened (PeelCore's `ChatReadState.isNew` tag).
@Composable
private fun NewTag() {
    Text(
        stringResource(R.string.chats_new),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Theme.colors.buttonText,
        modifier = Modifier.clip(CircleShape).background(Theme.colors.button).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/// A pending invite, first in the list (PeelCore's `PendingInviteRow`): a dashed ring round an envelope, its name
/// (or "Invite") and, at the right, how it stands. Opens its detail.
@Composable
private fun PendingInviteRow(invite: InviteRecord, onClick: () -> Unit) {
    val ring = Theme.colors.secondaryText
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).drawBehind {
                drawCircle(ring, style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mail, contentDescription = null, tint = ring, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            invite.nickname ?: stringResource(R.string.chats_invite),
            style = MaterialTheme.typography.bodyLarge,
            color = Theme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            invite.shortStatus,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"),
            color = Theme.colors.secondaryText.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

/// A chat's last message under its name on one line (PeelCore's
/// `PreviewLine`): muted and regular when read; primary text, semibold,
/// with an accent dot in front when unread; [end] after it.
@Composable
private fun PreviewLine(line: String, unread: Boolean, end: @Composable RowScope.() -> Unit) {
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (unread) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Theme.colors.accent))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            line,
            fontSize = 12.sp,
            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
            color = if (unread) Theme.colors.text else Theme.colors.secondaryText.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        end()
    }
}

/// A chat's unread count, same shape as PeelCore's `CountBadge` — a
/// capsule, at least 22dp so a single digit still reads as a circle.
@Composable
private fun UnreadBadge(count: Long) {
    Text(
        countText(count),
        style = MaterialTheme.typography.labelSmall,
        color = Theme.colors.buttonText,
        modifier = Modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(CircleShape)
            .background(Theme.colors.button)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 7.dp),
        textAlign = TextAlign.Center,
    )
}

