package com.sphinx.peel.ui.talk

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.TalkHistoryFormat
import com.sphinx.peel.engine.TalkHistoryStore
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.TalkSavedSession
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// Past Talk conversations of one workspace, newest first (iOS `TalkHistoryScreen`): date and a preview of the first
/// line; a tap opens the saved transcript. New conversation (and Resume on a row or a transcript) starts a call.
/// UNTESTED: layout; the store behind it is `TalkHistoryStore` (JVM-tested).
@Composable
fun TalkHistoryScreen(home: TalkHome, store: TalkHistoryStore, onBack: () -> Unit, onTalk: (TalkHome) -> Unit, modifier: Modifier = Modifier) {
    var sessions by remember { mutableStateOf<List<TalkSavedSession>?>(null) }
    var open by remember { mutableStateOf<TalkSavedSession?>(null) }
    LaunchedEffect(home.id) { sessions = withContext(Dispatchers.IO) { store.sessions(home.id) } }
    BackHandler { if (open != null) open = null else onBack() }
    val shown = open
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
                IconButton(onClick = { if (shown != null) open = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Theme.colors.text) }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (shown != null) TalkHistoryFormat.title(shown.date) else "Conversations", Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text,
            )
            IconButton(onClick = { onTalk(home) }) { Icon(Icons.Filled.Mic, if (shown != null) "Resume" else "New conversation", tint = Theme.colors.accent) }
        }
        val list = sessions
        when {
            shown != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                items(shown.lines, key = { it.id }) { line ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(TalkHistoryFormat.speaker(line), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(line.text, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                        }
                    }
                }
            }
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.History, null, Modifier.size(40.dp), tint = Theme.colors.secondaryText)
                    Text("No conversations yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
                    Text("Past conversations with Jamie on ${home.name} will appear here after a call ends.", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                items(list, key = { it.id }) { session ->
                    Row(Modifier.fillMaxWidth().clickable { open = session }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(TalkHistoryFormat.row(session.date), style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                            Text(
                                session.preview ?: "No transcript", maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
                            )
                        }
                        IconButton(onClick = { onTalk(home) }) { Icon(Icons.Filled.Mic, "Resume", tint = Theme.colors.accent) }
                    }
                }
            }
        }
    }
}
