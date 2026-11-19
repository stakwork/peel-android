package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.InviteRecord
import com.sphinx.peel.engine.Stadeum
import com.sphinx.peel.engine.dateLabel
import com.sphinx.peel.storage.ConversationRow
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import com.sphinx.peel.ui.call.rememberLiveCallCounts
import androidx.compose.material.icons.filled.Call
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.AgentTag
import com.sphinx.peel.ui.components.ConversationAvatar
import com.sphinx.peel.ui.components.PeelLogo
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/// Pages the rows above Tribes open. Placeholders for now (`ui/apps`,
/// `ui/threads`, `ui/mentions`); PeelCore's `ChatsTab.swift` `NavRow`s.
enum class DashboardRoute { APPS, THREADS, MENTIONS, ADD_CONTACT, INVITE, JOIN_TRIBE, CREATE_TRIBE }

/// "Chats" (docs/design/iphone.md, screen 1, `ChatsTab.swift`/`ChatList`):
/// the large title with the connection bolt, then `ChatListBody` for
/// everything else.
@Composable
fun ChatsScreen(engine: Engine, storage: Storage, modifier: Modifier = Modifier, onOpenRoute: (DashboardRoute) -> Unit = {}, onOpenInvite: (String) -> Unit = {}, onOpenChat: (String) -> Unit = {}) {
    val connection by engine.connectionState.collectAsState()

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Chats",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Theme.colors.text,
                modifier = Modifier.weight(1f),
            )
            ConnectionBolt(connected = connection == ConnectionState.Connected)
        }
        com.sphinx.peel.ui.diagnostics.DiagnosticsNotice(storage)
        ChatListBody(engine, storage, query = "", modifier = Modifier.weight(1f), onOpenRoute = onOpenRoute, onOpenInvite = onOpenInvite, onOpenChat = onOpenChat)
    }
}

/// The actual list: Unread (when there is any), then Tribes, then Direct
/// messages, each ending in its own actions — shared by `ChatsScreen`
/// (`query` always blank) and `SearchScreen` (PeelCore's own `ChatList`,
/// reused the same way by both `ChatsTab` and the iPhone's `SearchTab`).
/// Owns the data loading so either caller can be mounted on its own.
@Composable
private fun ChatListBody(
    engine: Engine,
    storage: Storage,
    query: String,
    modifier: Modifier = Modifier,
    onOpenRoute: (DashboardRoute) -> Unit = {},
    onOpenInvite: (String) -> Unit = {},
    onOpenChat: (String) -> Unit = {},
) {
    var conversations by remember { mutableStateOf(emptyList<ConversationRow>()) }
    var unreadMentions by remember { mutableStateOf(0L) }
    var unreadThreads by remember { mutableStateOf(0L) }
    // The invites still waiting (sending, ready, cancelling): a row each at the end of Direct messages.
    var pendingInvites by remember { mutableStateOf(emptyList<InviteRecord>()) }
    var refresh by remember { mutableStateOf(0) }
    var tribesOpen by remember { mutableStateOf(true) }
    var directOpen by remember { mutableStateOf(true) }
    var allTribes by remember { mutableStateOf(false) }
    var allDirect by remember { mutableStateOf(false) }
    // Separated / Recent and "Show all" in Recent: PeelCore's `sidebarLayout` (`combinedView`, `allRecent`), kept on the device.
    // Shared with Apps (which pins from its menu), so a pin shows here at once.
    val layoutState = remember(storage) { SidebarLayoutState.of(storage) }
    val layout = layoutState.layout
    fun changeLayout(next: SidebarLayout) = layoutState.change(next)
    // Separated / Recent is the synced `peel.chats_recent_view`; until it was ever chosen, the layout's own `combinedView` stands.
    var recentView by remember { mutableStateOf(engine.chatsRecentView() ?: layout.combinedView) }
    val restoreProgress by engine.restoreProgress.collectAsState()
    val liveCalls = rememberLiveCallCounts(engine, storage)
    // A stranger's public app opened here (a worker's, from a Stadeum's invite link) shows Apps too: read again as one is kept or forgotten.
    val publicApps by storage.publicAppsVersion.collectAsState()
    var openedApps by remember { mutableStateOf(false) }
    LaunchedEffect(publicApps) { openedApps = withContext(Dispatchers.Default) { storage.openedApps().isNotEmpty() } }
    val showsApps = Stadeum.showsApps(openedApps)
    // The apps under the Apps row, A–Z (the Mac's sidebar): the pinned ones while the fold is open, or, searching, every app that
    // matches. Read by `Stadeum.readApps`, shared with Apps; only the workspaces in use.
    val openPage = com.sphinx.peel.ui.apps.LocalPageOpener.current
    val live = Stadeum.live
    val liveIds = live.map { it.id }
    val listedApps = com.sphinx.peel.engine.WorkspaceApp.byTitle(
        com.sphinx.peel.engine.AppsOpenDecision.visible(Stadeum.apps, liveIds.toSet()),
    )
    val hasPins = live.isNotEmpty() && layout.pinned(listedApps).isNotEmpty()
    val searchingApps = query.isNotBlank()
    val underApps = when {
        live.isEmpty() -> emptyList()
        searchingApps -> listedApps.filter { it.matches(query) }
        layout.appsOpen -> layout.pinned(listedApps)
        else -> emptyList()
    }
    val appsSeveral = listedApps.mapNotNull { it.peer }.toSet().size > 1
    @Composable
    fun pinnedRow(app: com.sphinx.peel.engine.WorkspaceApp) {
        val pinned = layout.isPinned(app.id)
        PinnedAppRow(app, appsSeveral, pinned, onOpen = {
            // As Apps does: a page when there is one, otherwise nothing.
            com.sphinx.peel.engine.AppsOpenDecision.page(Stadeum.openings.opening(app))?.let(openPage)
        }, onTogglePin = { changeLayout(layout.withPinned(app.id, !pinned)) })
    }
    // Pins show without opening Apps first: read when there are saved pins (or a search wants the apps) and Stadeum is live.
    LaunchedEffect(liveIds, layout.pinnedApps.isNotEmpty(), searchingApps) {
        if (live.isNotEmpty() && (layout.pinnedApps.isNotEmpty() || (searchingApps && Stadeum.apps.isEmpty()))) Stadeum.readApps()
    }

    // No reactive query layer yet (no Room, no Flow-backed DB) to push a
    // row the moment a live message lands — re-reads on every restore
    // step, which covers the one real test so far, plus a plain poll as
    // the honest fallback for anything that arrives outside a restore.
    suspend fun reload() {
        conversations = withContext(Dispatchers.Default) { storage.conversations() }
        unreadMentions = withContext(Dispatchers.Default) {
            storage.unreadMentions(storage.value(Setting.ALIAS)?.takeIf { it.isNotEmpty() } ?: "You")
        }
        unreadThreads = withContext(Dispatchers.Default) { storage.unreadThreadCount().toLong() }
        pendingInvites = withContext(Dispatchers.Default) { storage.invites().filter { it.isPending } }
        recentView = withContext(Dispatchers.Default) { engine.chatsRecentView() ?: layout.combinedView }
    }
    // `refresh`: a row action (read, unread, mute) just changed something.
    LaunchedEffect(restoreProgress, refresh) { reload() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            reload()
        }
    }

    // The agent's DM is its own row, first in Direct messages, never
    // among the regular rows — PeelCore's `ChatList`: "The agent's DM is
    // its own row, first in Direct messages... never in the sections".
    // While searching it's found by name the same way any other chat is.
    // Unread, it is in the Unread section like any chat.
    val agentConversation = conversations.firstOrNull { it.chat == engine.agentPubkey }
        ?.takeIf { ChatSections.matches(it.name, query) && !it.isUnread }
    val sectionable = if (agentConversation == null) {
        conversations
    } else {
        conversations.filter { it.chat != engine.agentPubkey }
    }
    val sections = remember(sectionable, tribesOpen, directOpen, allTribes, allDirect, query, layout.allRecent) {
        ChatSections.of(sectionable, tribesOpen, directOpen, allTribes, allDirect, query, layout.allRecent)
    }
    val searching = sections.searching
    val recent = recentView && !searching
    val noMatches = searching && sections.unread.isEmpty() && sections.tribes.isEmpty() &&
        sections.direct.isEmpty() && agentConversation == null && underApps.isEmpty()

    Box(modifier) {
        if (conversations.isEmpty() && !searching) {
            // Only for a kept public app here: a workspace's Apps row stays where iOS has it, in the list.
            EmptyChats(restoring = restoreProgress.restoring, showsApps = openedApps, pendingInvites = pendingInvites, onOpenRoute = onOpenRoute, onOpenInvite = onOpenInvite)
        } else if (noMatches) {
            NoSearchResults(query)
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp)) {
                // Not while searching. Threads counts threads with new
                // replies, Mentions the unread mentions; Apps stays 0 and
                // shows only with a live Stadeum workspace or a development
                // talk home, as on iOS (`Stadeum` is observable: the row
                // appears as soon as You › Stadeum pairs), or (Android) with
                // a public app opened here.
                if (!searching) {
                    if (showsApps) item(key = "apps-row") {
                        AppsRow(fold = if (hasPins) layout.appsOpen else null, onToggleFold = { changeLayout(layout.copy(appsOpen = !layout.appsOpen)) }) {
                            onOpenRoute(DashboardRoute.APPS)
                        }
                    }
                    items(underApps, key = { "pinned-${it.id}" }) { app -> pinnedRow(app) }
                    item { NavRow(Icons.Filled.ChatBubbleOutline, "Threads", unreadThreads) { onOpenRoute(DashboardRoute.THREADS) } }
                    item { NavRow(Icons.Filled.AlternateEmail, "Mentions", unreadMentions) { onOpenRoute(DashboardRoute.MENTIONS) } }
                    item { Spacer(Modifier.height(16.dp)) }
                }

                // Searching shows every app that matches, as the Mac's sidebar does.
                if (searching) items(underApps, key = { "found-${it.id}" }) { app -> pinnedRow(app) }

                if (sections.unread.isNotEmpty()) {
                    item { SectionHeader("Unread", expanded = true, onToggle = null) }
                    items(sections.unread, key = { "unread-${it.chat}" }) { conversation ->
                        ChatRowActions(conversation, engine, onChanged = { refresh++ }) { onLongClick ->
                            ChatRow(
                                conversation,
                                onCall = (liveCalls[conversation.chat] ?: 0) > 0,
                                onLongClick = onLongClick,
                                isAgent = conversation.chat == engine.agentPubkey,
                            ) { onOpenChat(conversation.chat) }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }

                if (!searching) {
                    item(key = "layout-switch") {
                        LayoutSwitch(recent = recentView) {
                            recentView = it
                            engine.setChatsRecentView(it)
                        }
                    }
                }

                if (recent) {
                    // One list, newest first, with every action after it (iOS's Recent view).
                    item { SectionHeader("Recent", expanded = true, onToggle = null) }
                    agentConversation?.let { agent ->
                        item(key = "agent") {
                            ChatRowActions(agent, engine, onChanged = { refresh++ }) { onLongClick ->
                                ChatRow(agent, onLongClick = onLongClick, isAgent = true) { onOpenChat(engine.agentPubkey) }
                            }
                        }
                    }
                    items(sections.combined, key = { "recent-${it.chat}" }) { conversation ->
                        ChatRowActions(conversation, engine, onChanged = { refresh++ }) { onLongClick ->
                            ChatRow(conversation, onCall = (liveCalls[conversation.chat] ?: 0) > 0, onLongClick = onLongClick) { onOpenChat(conversation.chat) }
                        }
                    }
                    sections.allCombinedCount?.let { count ->
                        item { ShowAllRow(count = count, noun = "chats", expanded = layout.allRecent) { changeLayout(layout.copy(allRecent = !layout.allRecent)) } }
                    }
                    item { ActionRow("Join a tribe") { onOpenRoute(DashboardRoute.JOIN_TRIBE) } }
                    item { ActionRow("Create a tribe") { onOpenRoute(DashboardRoute.CREATE_TRIBE) } }
                    items(pendingInvites, key = { "invite-${it.code}" }) { invite -> PendingInviteRow(invite) { onOpenInvite(invite.code) } }
                    item { ActionRow("Add contact") { onOpenRoute(DashboardRoute.ADD_CONTACT) } }
                    item { ActionRow("Invite someone") { onOpenRoute(DashboardRoute.INVITE) } }
                }

                // Searching shows every matching section, no chevron, no
                // actions — a section with no matches doesn't show at all.
                if (!recent && (!searching || sections.tribes.isNotEmpty())) {
                    val toggleTribes: (() -> Unit)? = if (searching) null else ({ tribesOpen = !tribesOpen })
                    item { SectionHeader("Tribes", expanded = tribesOpen || searching, onToggle = toggleTribes) }
                    if (tribesOpen || searching) {
                        items(sections.tribes, key = { "tribe-${it.chat}" }) { conversation ->
                            ChatRowActions(conversation, engine, onChanged = { refresh++ }) { onLongClick ->
                            ChatRow(conversation, onCall = (liveCalls[conversation.chat] ?: 0) > 0, onLongClick = onLongClick) { onOpenChat(conversation.chat) }
                        }
                        }
                        sections.allTribesCount?.let { count ->
                            item { ShowAllRow(count = count, noun = "tribes", expanded = allTribes) { allTribes = !allTribes } }
                        }
                        if (!searching) {
                            item { ActionRow("Join a tribe") { onOpenRoute(DashboardRoute.JOIN_TRIBE) } }
                            item { ActionRow("Create a tribe") { onOpenRoute(DashboardRoute.CREATE_TRIBE) } }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }

                if (!recent && (!searching || sections.direct.isNotEmpty() || agentConversation != null)) {
                    val toggleDirect: (() -> Unit)? = if (searching) null else ({ directOpen = !directOpen })
                    item { SectionHeader("Direct messages", expanded = directOpen || searching, onToggle = toggleDirect) }
                    if (directOpen || searching) {
                        agentConversation?.let { agent ->
                            item(key = "agent") {
                                ChatRowActions(agent, engine, onChanged = { refresh++ }) { onLongClick ->
                                    ChatRow(agent, onLongClick = onLongClick, isAgent = true) { onOpenChat(engine.agentPubkey) }
                                }
                            }
                        }
                        items(sections.direct, key = { "direct-${it.chat}" }) { conversation ->
                            ChatRowActions(conversation, engine, onChanged = { refresh++ }) { onLongClick ->
                            ChatRow(conversation, onCall = (liveCalls[conversation.chat] ?: 0) > 0, onLongClick = onLongClick) { onOpenChat(conversation.chat) }
                        }
                        }
                        sections.allDirectCount?.let { count ->
                            item { ShowAllRow(count = count, noun = "people", expanded = allDirect) { allDirect = !allDirect } }
                        }
                        if (!searching) {
                            items(pendingInvites, key = { "invite-${it.code}" }) { invite -> PendingInviteRow(invite) { onOpenInvite(invite.code) } }
                            item { ActionRow("Add contact") { onOpenRoute(DashboardRoute.ADD_CONTACT) } }
                            item { ActionRow("Invite someone") { onOpenRoute(DashboardRoute.INVITE) } }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/// The Separated / Recent switch (iPhone `ChatsTab`'s segmented picker).
@Composable
private fun LayoutSwitch(recent: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Separated" to false, "Recent" to true).forEach { (label, value) ->
            val selected = recent == value
            val modifier = Modifier.weight(1f)
            if (selected) {
                androidx.compose.material3.Button(onClick = { onChange(value) }, modifier = modifier) { Text(label) }
            } else {
                androidx.compose.material3.OutlinedButton(onClick = { onChange(value) }, modifier = modifier) { Text(label) }
            }
        }
    }
}

/// "No results for '…'" — PeelCore's own `ContentUnavailableView.search`.
@Composable
private fun NoSearchResults(query: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No results for \"$query\"", style = MaterialTheme.typography.titleMedium, color = Theme.colors.text)
    }
}

/// "Search" (docs/design/iphone.md's own tab bar: Chats, Payments, You,
/// "plus the system's separate search tab") — a text field filtering
/// `ChatListBody`, the same list the Chats tab shows, by name
/// (`ChatSections`'s own `matches`/`searching`), PeelCore's own iPhone
/// `SearchTab`: `.searchable` over the same `ChatList` `ChatsTab` uses.
/// Not message content — PeelCore's own content search is a different
/// feature entirely (Mac's ⌘F find-within-a-chat, `Storage+Search.swift`'s
/// FTS5 index), and "results across messages" for *this* tab is still
/// explicitly listed under `docs/design/iphone.md`'s own "To design", not
/// built even on iOS yet.
@Composable
fun SearchScreen(engine: Engine, storage: Storage, modifier: Modifier = Modifier, onOpenChat: (String) -> Unit = {}) {
    var query by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        Text(
            "Search",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Theme.colors.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        )
        SearchField(query, onQueryChange = { query = it }, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
        if (query.isBlank()) {
            EmptySearch()
        } else {
            ChatListBody(engine, storage, query = query, modifier = Modifier.weight(1f), onOpenChat = onOpenChat)
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Theme.colors.fieldFill)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Theme.colors.secondaryText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Search chats", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.secondaryText)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Theme.colors.text),
                cursorBrush = SolidColor(Theme.colors.accent),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear",
                tint = Theme.colors.secondaryText,
                modifier = Modifier.size(18.dp).clickable { onQueryChange("") },
            )
        }
    }
}

/// "Search chats" / "Tribes and people by name." for a blank query —
/// PeelCore's own `ContentUnavailableView("Search chats", ...)`.
@Composable
private fun EmptySearch() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Theme.colors.secondaryText, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Search chats", style = MaterialTheme.typography.titleMedium, color = Theme.colors.text)
        Spacer(Modifier.height(4.dp))
        Text("Tribes and people by name.", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
    }
}

@Composable
private fun EmptyChats(
    restoring: Boolean,
    showsApps: Boolean,
    pendingInvites: List<InviteRecord>,
    onOpenRoute: (DashboardRoute) -> Unit,
    onOpenInvite: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (restoring) "Restoring your chats" else "No chats yet",
            style = MaterialTheme.typography.titleMedium,
            color = Theme.colors.text,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (restoring) {
                "They show here as they arrive. This can take a while."
            } else {
                "Join a tribe or add someone below, and they show here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Theme.colors.secondaryText,
        )
        if (!restoring) {
            // Nobody to talk to yet: the two ways to get someone here must not hide behind a chat.
            Spacer(Modifier.height(16.dp))
            for (invite in pendingInvites) PendingInviteRow(invite) { onOpenInvite(invite.code) }
            ActionRow("Add contact") { onOpenRoute(DashboardRoute.ADD_CONTACT) }
            ActionRow("Invite someone") { onOpenRoute(DashboardRoute.INVITE) }
        }
        // A new worker has no chats yet but has the Stadeum's worker app: Apps must not hide behind a chat either.
        if (showsApps) {
            Spacer(Modifier.height(16.dp))
            NavRow(Icons.Filled.Apps, "Apps", 0) { onOpenRoute(DashboardRoute.APPS) }
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
            contentDescription = if (connected) "Connected" else "Not connected",
            tint = if (connected) Theme.colors.confirmed else Theme.colors.attention,
        )
    }
}

/// `onToggle` null for a section with no collapse of its own — Unread,
/// which PeelCore never lets fold (its own `unread` array has no
/// `shown()`/layout-driven open state at all).
@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Theme.colors.secondaryText,
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = Theme.colors.secondaryText,
            )
        }
    }
}

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
    // ChatListRow): a new contact sits in the Unread section (`isUnread`
    // covers that) but its row text stays normal, with only the trailing
    // NEW tag marking it.
    val bold = unread > 0
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isAgent) PeelLogo(size = 44.dp) else ConversationAvatar(conversation, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            conversation.previewLine?.let {
                val label = remember(conversation.lastTimestamp) {
                    dateLabel(conversation.lastTimestamp, Instant.now(), ZoneId.systemDefault(), Locale.getDefault())
                }
                PreviewLine(it, label, unread = unread > 0)
            }
        }
        if (onCall) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Call, contentDescription = "On a call", tint = Theme.colors.confirmed, modifier = Modifier.size(18.dp))
        }
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            UnreadBadge(unread)
        } else if (conversation.isNew) {
            Spacer(Modifier.width(8.dp))
            NewTag()
        } else {
            // Nothing unread to show as a count — PeelCore's own
            // `ChatListRow` still shows the chat's own mute state as a
            // trailing glyph (bell-slash/"@"), state already stored and
            // read (Phase 6), just never shown here until now.
            when (conversation.muteLevel) {
                2 -> {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.NotificationsOff, contentDescription = "Muted", tint = Theme.colors.secondaryText, modifier = Modifier.size(18.dp))
                }
                1 -> {
                    Spacer(Modifier.width(8.dp))
                    Text("@", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                }
            }
        }
    }
}

/// "NEW" on a contact who just added this account, until its chat is opened (PeelCore's `ChatReadState.isNew` tag).
@Composable
private fun NewTag() {
    Text(
        "NEW",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Theme.colors.buttonText,
        modifier = Modifier.clip(CircleShape).background(Theme.colors.button).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/// A pending invite in Direct messages (PeelCore's `PendingInviteRow`): a dashed ring round an envelope, its name
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
            invite.nickname ?: "Invite",
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
/// with an accent dot in front when unread.
@Composable
private fun PreviewLine(line: String, label: String?, unread: Boolean) {
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
        if (label != null) {
            Text(
                label,
                fontSize = 12.sp,
                color = Theme.colors.secondaryText.copy(alpha = 0.8f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/// A chat's unread count, same shape as PeelCore's `CountBadge` — a
/// capsule, at least 22dp so a single digit still reads as a circle.
@Composable
private fun UnreadBadge(count: Long) {
    Text(
        if (count > 99) "99+" else count.toString(),
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

/// "Show all 14 tribes" / "Show fewer" — PeelCore's `ShowAll`.
@Composable
private fun ShowAllRow(count: Int, noun: String, expanded: Boolean, onToggle: () -> Unit) {
    Text(
        if (expanded) "Show fewer" else "Show all $count $noun",
        style = MaterialTheme.typography.bodyMedium,
        color = Theme.colors.accent,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp),
    )
}

/// "Join a tribe", "Add contact" and the rest — PeelCore's `ActionRow`.
/// "Add contact", "Invite someone", "Join a tribe" and "Create a tribe" open their screens.
@Composable
private fun ActionRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = Theme.colors.secondaryText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
    }
}

/// "Apps", "Threads", "Mentions" — PeelCore's `NavRow`. A count above zero
/// shows a badge and turns the title semibold, primary colour.
@Composable
private fun NavRow(icon: ImageVector, title: String, count: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Theme.colors.secondaryText, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (count > 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (count > 0) Theme.colors.text else Theme.colors.secondaryText,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) UnreadBadge(count)
    }
}
