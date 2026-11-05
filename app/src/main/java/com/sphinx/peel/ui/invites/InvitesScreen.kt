package com.sphinx.peel.ui.invites

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.AndroidClipboardAccess
import com.sphinx.peel.account.SensitiveClipboard
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.InviteRecord
import com.sphinx.peel.engine.InviteRefused
import com.sphinx.peel.engine.InviteRules
import com.sphinx.peel.engine.PaymentRefused
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.cancelInvite
import com.sphinx.peel.engine.forgetInvite
import com.sphinx.peel.engine.makeInvite
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.payments.PageFrame
import com.sphinx.peel.ui.payments.QrCodeImage
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// The invites made here (the iPhone's `InviteSheet`/`InviteDetailScreen`): a new one holds sats
/// for someone without an account, shared as a link or QR code; each is listed with where it
/// stands and, while it waits, can be cancelled to get the sats back.
@Composable
fun InvitesScreen(engine: Engine, storage: Storage, onBack: () -> Unit) {
    var invites by remember { mutableStateOf(storage.invites()) }
    var creating by remember { mutableStateOf(false) }
    var openCode by remember { mutableStateOf<String?>(null) }
    // The stored rows move as the server answers (status), even while Peel was closed.
    LaunchedEffect(Unit) {
        while (true) {
            invites = storage.invites()
            delay(1_000)
        }
    }

    val open = openCode?.let { code -> invites.firstOrNull { it.code == code } }
    when {
        creating -> NewInvite(engine, onBack = { creating = false }, onMade = { creating = false; openCode = it.code })
        open != null -> InviteDetail(engine, storage, open, onBack = { openCode = null })
        else -> PageFrame("Invites", onBack) {
            Text(
                "Invite someone who doesn't have Peel yet. The sats you put in go to their account when they join.",
                style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { creating = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
            ) { Text("New invite") }
            Spacer(Modifier.height(16.dp))
            if (invites.isEmpty()) {
                Text("No invites yet.", color = Theme.colors.secondaryText)
            }
            for (invite in invites) {
                InviteRow(invite) { openCode = invite.code }
                HorizontalDivider(color = Theme.colors.divider)
            }
        }
    }
}

@Composable
private fun InviteRow(invite: InviteRecord, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(invite.nickname ?: "Invite", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, fontWeight = FontWeight.SemiBold)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(invite.createdAt)),
                style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Sats.format(invite.amountMsat), color = Theme.colors.text)
            Text(statusLabel(invite.status), style = MaterialTheme.typography.bodySmall, color = statusColor(invite.status))
        }
    }
}

private fun statusLabel(status: InviteRecord.Status): String = when (status) {
    InviteRecord.Status.SENDING -> "Sending"
    InviteRecord.Status.READY -> "Ready"
    InviteRecord.Status.FAILED -> "Failed"
    InviteRecord.Status.CANCELLING -> "Cancelling"
    InviteRecord.Status.CANCELLED -> "Cancelled"
    InviteRecord.Status.USED -> "Used"
}

@Composable
private fun statusColor(status: InviteRecord.Status) = when (status) {
    InviteRecord.Status.READY, InviteRecord.Status.USED -> Theme.colors.confirmed
    InviteRecord.Status.FAILED -> Theme.colors.destructive
    else -> Theme.colors.secondaryText
}

/// Amount (sats), an optional name and note (kept on this device only), then a confirmation: the
/// sats leave for a code anyone holding it can spend.
@Composable
private fun NewInvite(engine: Engine, onBack: () -> Unit, onMade: (InviteRecord) -> Unit) {
    var amountText by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmMsat by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val msat = Sats.msat(amountText)

    fun make(confirmed: Long?) {
        val amount = msat ?: return
        working = true
        problem = null
        scope.launch {
            try {
                onMade(engine.makeInvite(amount, nickname, message, confirmed))
            } catch (e: PaymentRefused) {
                val ask = e.confirmMsat
                if (ask != null) confirmMsat = ask else problem = e.message
            } catch (e: InviteRefused) {
                problem = e.message
            } catch (e: Exception) {
                problem = e.message ?: "Could not make the invite."
            } finally {
                working = false
            }
        }
    }

    PageFrame("New invite", onBack) {
        OutlinedTextField(
            value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == ',' } },
            label = { Text("Amount (sats)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "The server may keep a small fee. They get the rest when they join.",
            style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText, modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = nickname, onValueChange = { nickname = it.take(InviteRecord.MAX_NICKNAME_LENGTH) },
            label = { Text("Who is it for? (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = message, onValueChange = { message = it.take(InviteRecord.MAX_MESSAGE_LENGTH) },
            label = { Text("Note to yourself (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        problem?.let { Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = msat != null && !working,
            onClick = { make(null) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
        ) {
            if (working) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp) else Text("Make invite")
        }
    }

    confirmMsat?.let { amount ->
        AlertDialog(
            onDismissRequest = { confirmMsat = null },
            title = { Text("Put ${Sats.format(amount)} in an invite?") },
            text = { Text("The sats leave your balance now. Anyone who has the invite can use them, until you cancel it.") },
            confirmButton = { TextButton(onClick = { confirmMsat = null; make(amount) }) { Text("Make invite", color = Theme.colors.accent) } },
            dismissButton = { TextButton(onClick = { confirmMsat = null }) { Text("Cancel") } },
        )
    }
}

/// One invite: its QR code, link (Copy, Share), where it stands, and Cancel while it waits.
@Composable
private fun InviteDetail(engine: Engine, storage: Storage, invite: InviteRecord, onBack: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var confirmingCancel by remember { mutableStateOf(false) }
    val shareable = invite.status == InviteRecord.Status.READY || invite.status == InviteRecord.Status.SENDING

    PageFrame(invite.nickname ?: "Invite", onBack) {
        if (shareable) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCodeImage(invite.link, contentDescription = "QR code of the invite")
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(Sats.format(invite.amountMsat), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
        invite.message?.let { Text(it, color = Theme.colors.secondaryText) }
        Spacer(Modifier.height(8.dp))
        val usedBy = invite.usedBy?.let { storage.contact(it)?.alias ?: "someone" }
        Text(
            when (invite.status) {
                InviteRecord.Status.SENDING -> "Waiting for the server to confirm it. It shows ready once it does."
                InviteRecord.Status.READY -> "Ready to share. It works once."
                InviteRecord.Status.FAILED -> "The server refused the payment. No sats moved."
                InviteRecord.Status.CANCELLING -> "Asked the server for the sats back. Not answered yet."
                InviteRecord.Status.CANCELLED -> "Cancelled. The sats came back."
                InviteRecord.Status.USED -> "Used by $usedBy."
            },
            color = statusColor(invite.status),
        )
        if (shareable) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { AndroidClipboardAccess.sensitive(context).copy(invite.link, SensitiveClipboard.DEFAULT_CLEAR_MS * 2) },
                    modifier = Modifier.weight(1f),
                ) { Text("Copy") }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite.link)
                        context.startActivity(Intent.createChooser(send, "Share invite"))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
        }
        if (InviteRules.canCancel(invite.status)) {
            TextButton(enabled = !working, onClick = { confirmingCancel = true }) {
                if (working) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                else Text(if (invite.status == InviteRecord.Status.CANCELLING) "Ask again" else "Cancel invite", color = Theme.colors.destructive)
            }
        }
        if (invite.status == InviteRecord.Status.CANCELLING) {
            TextButton(onClick = {
                runCatching { engine.forgetInvite(invite.code) }.onFailure { toast.show(it.message ?: "Could not remove it.", ToastType.Error) }
            }) { Text("Remove from the list", color = Theme.colors.secondaryText) }
        }
    }

    if (confirmingCancel) {
        AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            title = { Text("Cancel this invite?") },
            text = { Text("The sats come back to your balance. The link stops working.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingCancel = false
                    working = true
                    scope.launch {
                        try {
                            engine.cancelInvite(invite.code)
                            toast.show("Invite cancelled.", ToastType.Success)
                        } catch (e: Exception) {
                            toast.show(e.message ?: "Could not cancel the invite.", ToastType.Error)
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Cancel invite", color = Theme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmingCancel = false }) { Text("Keep it") } },
        )
    }
}
