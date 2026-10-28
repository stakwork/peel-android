package com.sphinx.peel.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.you.YouScreen

private enum class MainTab { CHATS, PAYMENTS, YOU, SEARCH }

/// The tab bar — PeelCore's `MainTabs.swift`: Chats, Payments, You, Search.
/// Only Chats and You are real; Payments and Search are placeholders,
/// since neither has anything behind it yet.
@Composable
fun MainTabs(engine: Engine, storage: Storage, secretStore: SecretStore, onForgotten: () -> Unit) {
    var tab by remember { mutableStateOf(MainTab.CHATS) }
    // An open chat replaces the whole tab area, bar included — PeelCore's
    // `ConversationScreen` hides the tab bar the same way; Back returns to
    // whichever tab was open, not necessarily Chats.
    var openChat by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        val chat = openChat
        if (chat != null) {
            ConversationScreen(engine, storage, chat, onBack = { openChat = null })
        } else {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    MainTab.CHATS -> ChatsScreen(engine, storage, modifier = Modifier.fillMaxSize()) { openChat = it }
                    MainTab.PAYMENTS -> NotBuiltYet("Payments")
                    MainTab.YOU -> YouScreen(engine, storage, secretStore, onForgotten)
                    MainTab.SEARCH -> NotBuiltYet("Search")
                }
            }
            BottomBar(tab) { tab = it }
        }
    }
}

@Composable
private fun NotBuiltYet(title: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text("$title isn't built yet.", color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodyMedium)
    }
}

/// Chats, Payments and You in one pill, Search its own circle beside it —
/// the shape the iPhone's native search-role tab gets for free.
@Composable
private fun BottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(28.dp)).background(Theme.colors.fieldFill),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TabIcon(Icons.AutoMirrored.Filled.Chat, "Chats", selected == MainTab.CHATS) { onSelect(MainTab.CHATS) }
            TabIcon(Icons.Filled.Bolt, "Payments", selected == MainTab.PAYMENTS) { onSelect(MainTab.PAYMENTS) }
            TabIcon(Icons.Filled.Person, "You", selected == MainTab.YOU) { onSelect(MainTab.YOU) }
        }
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Theme.colors.fieldFill)
                .clickable { onSelect(MainTab.SEARCH) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search",
                tint = if (selected == MainTab.SEARCH) Theme.colors.accent else Theme.colors.secondaryText,
            )
        }
    }
}

@Composable
private fun RowScope.TabIcon(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Theme.colors.accent else Theme.colors.secondaryText
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
