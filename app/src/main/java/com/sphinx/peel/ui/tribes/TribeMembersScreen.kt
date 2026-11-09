package com.sphinx.peel.ui.tribes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.TribeMember
import com.sphinx.peel.engine.approveMember
import com.sphinx.peel.engine.kickMember
import com.sphinx.peel.engine.rejectMember
import com.sphinx.peel.engine.tribeMembers
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.payments.PageFrame
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlinx.coroutines.launch

/// A tribe's members, with the join requests waiting for an answer on top. An owner (or admin)
/// approves or rejects a request; an owner removes a member (after a confirmation). The tribes
/// server enforces who may; [isOwner] only decides which buttons are offered.
@Composable
fun TribeMembersScreen(engine: Engine, chat: String, tribeName: String, isOwner: Boolean, onBack: () -> Unit) {
    var members by remember(chat) { mutableStateOf<List<TribeMember>?>(null) }
    var problem by remember(chat) { mutableStateOf<String?>(null) }
    var reload by remember(chat) { mutableStateOf(0) }
    var busy by remember(chat) { mutableStateOf(false) }
    var kicking by remember(chat) { mutableStateOf<TribeMember?>(null) }
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(chat, reload) {
        try {
            members = engine.tribeMembers(chat)
            problem = null
        } catch (e: Exception) {
            problem = e.message ?: "Could not read the members."
        }
    }

    fun act(done: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                toast.show(done, ToastType.Success)
                reload++
            } catch (e: Exception) {
                problem = e.message ?: "That did not go through."
            } finally {
                busy = false
            }
        }
    }

    PageFrame("Members of #$tribeName", onBack) {
        problem?.let { Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
        val shown = members
        if (shown == null) {
            if (problem == null) CircularProgressIndicator(Modifier.padding(top = 24.dp))
        } else {
            val pending = shown.filter { it.pending }
            val joined = shown.filter { !it.pending }
            if (pending.isNotEmpty()) {
                Text("Waiting to join", style = MaterialTheme.typography.labelLarge, color = Theme.colors.secondaryText, modifier = Modifier.padding(vertical = 8.dp))
                for (member in pending) {
                    MemberRow(member) {
                        if (isOwner) {
                            OutlinedButton(enabled = !busy, onClick = { act("Approved.") { engine.approveMember(chat, member) } }) { Text("Approve") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(enabled = !busy, onClick = { act("Rejected.") { engine.rejectMember(chat, member) } }) { Text("Reject") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Text("Members", style = MaterialTheme.typography.labelLarge, color = Theme.colors.secondaryText, modifier = Modifier.padding(vertical = 8.dp))
            if (joined.isEmpty()) {
                Text("No members to show yet.", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
            }
            for (member in joined) {
                MemberRow(member) {
                    if (isOwner && member.pubkey != engine.accountKey) {
                        TextButton(enabled = !busy, onClick = { kicking = member }) { Text("Remove", color = Theme.colors.destructive) }
                    }
                }
            }
        }
    }

    kicking?.let { member ->
        AlertDialog(
            onDismissRequest = { kicking = null },
            title = { Text("Remove ${member.alias ?: "this member"}?") },
            text = { Text("They are taken out of #$tribeName.") },
            confirmButton = {
                TextButton(onClick = { kicking = null; act("Removed.") { engine.kickMember(chat, member.pubkey) } }) {
                    Text("Remove", color = Theme.colors.destructive)
                }
            },
            dismissButton = { TextButton(onClick = { kicking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MemberRow(member: TribeMember, actions: @Composable () -> Unit) {
    val name = member.alias ?: member.pubkey.take(12).ifEmpty { "Someone" }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, member.photoUrl, 40.dp, PersonHue.of(member.pubkey.ifEmpty { name }))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}
