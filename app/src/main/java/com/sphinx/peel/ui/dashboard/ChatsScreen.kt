package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.ConversationRow
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.ConversationAvatar
import com.sphinx.peel.ui.components.PeelLogo
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/// "Chats" (docs/design/iphone.md, screen 1, `ChatsTab.swift`/`ChatList`):
/// the large title with the connection bolt, Tribes then Direct messages,
/// each ending in its actions. No Unread section and no search yet — see
/// `docs/phases/04-dashboard.md` for what that needs first.
@Composable
fun ChatsScreen(engine: Engine, storage: Storage, modifier: Modifier = Modifier, onOpenChat: (String) -> Unit = {}) {
    var conversations by remember { mutableStateOf(emptyList<ConversationRow>()) }
    var tribesOpen by remember { mutableStateOf(true) }
    var directOpen by remember { mutableStateOf(true) }
    var allTribes by remember { mutableStateOf(false) }
    var allDirect by remember { mutableStateOf(false) }
    val connection by engine.connectionState.collectAsState()
    val restoreProgress by engine.restoreProgress.collectAsState()

    // No reactive query layer yet (no Room, no Flow-backed DB) to push a
    // row the moment a live message lands — re-reads on every restore
    // step, which covers the one real test so far, plus a plain poll as
    // the honest fallback for anything that arrives outside a restore.
    suspend fun reload() {
        conversations = withContext(Dispatchers.Default) { storage.conversations() }
    }
    LaunchedEffect(restoreProgress) { reload() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            reload()
        }
    }

    // The agent's DM is its own row, first in Direct messages, never
    // among the regular rows — PeelCore's `ChatList`: "The agent's DM is
    // its own row, first in Direct messages... never in the sections".
    val agentConversation = conversations.firstOrNull { it.chat == engine.agentPubkey }
    val sectionable = if (agentConversation == null) conversations else conversations.filter { it.chat != engine.agentPubkey }
    val sections = remember(sectionable, tribesOpen, directOpen, allTribes, allDirect) {
        ChatSections.of(sectionable, tribesOpen, directOpen, allTribes, allDirect)
    }

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

        if (conversations.isEmpty()) {
            EmptyChats(restoring = restoreProgress.restoring)
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp)) {
                item { SectionHeader("Tribes", expanded = tribesOpen) { tribesOpen = !tribesOpen } }
                if (tribesOpen) {
                    items(sections.tribes, key = { "tribe-${it.chat}" }) { conversation ->
                        ChatRow(conversation) { onOpenChat(conversation.chat) }
                    }
                    sections.allTribesCount?.let { count ->
                        item { ShowAllRow(count = count, noun = "tribes", expanded = allTribes) { allTribes = !allTribes } }
                    }
                    item { ActionRow("Join a tribe") { /* not wired yet */ } }
                    item { ActionRow("Create a tribe") { /* not wired yet */ } }
                }

                item { Spacer(Modifier.height(16.dp)) }

                item { SectionHeader("Direct messages", expanded = directOpen) { directOpen = !directOpen } }
                if (directOpen) {
                    agentConversation?.let { item(key = "agent") { AgentChatRow { onOpenChat(engine.agentPubkey) } } }
                    items(sections.direct, key = { "direct-${it.chat}" }) { conversation ->
                        ChatRow(conversation) { onOpenChat(conversation.chat) }
                    }
                    sections.allDirectCount?.let { count ->
                        item { ShowAllRow(count = count, noun = "people", expanded = allDirect) { allDirect = !allDirect } }
                    }
                    item { ActionRow("Add contact") { /* not wired yet */ } }
                    item { ActionRow("Invite someone") { /* not wired yet */ } }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyChats(restoring: Boolean) {
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

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Theme.colors.secondaryText,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = Theme.colors.secondaryText,
        )
    }
}

@Composable
private fun ChatRow(conversation: ConversationRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConversationAvatar(conversation, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            conversation.name,
            style = MaterialTheme.typography.bodyLarge,
            color = Theme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/// The agent's row: Peel's own logo, never the hashed-colour avatar its
/// stored alias/photo would otherwise get, and the "AGENT" tag beside its
/// name — PeelCore's `ChatListRow(isAgent: true)`.
@Composable
private fun AgentChatRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeelLogo(size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Text("Peel", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
        Spacer(Modifier.width(8.dp))
        AgentTag()
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
/// Present, same place as the real app, not wired to anything yet.
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
