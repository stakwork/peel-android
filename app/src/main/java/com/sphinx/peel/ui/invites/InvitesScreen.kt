package com.sphinx.peel.ui.invites

import android.content.Intent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// Inviting someone (the iPhone's `InviteSheet`/`InviteDetailScreen`): the form makes an invite
/// holding sats for someone without an account, shared as a link or QR code; the detail shows where
/// it stands and, while it waits, lets it be cancelled to get the sats back. The pending ones are
/// listed in the chat list (`ChatsScreen`), not here.

/// Amount (sats), an optional name and note (kept on this device only), then a confirmation: the
/// sats leave for a code anyone holding it can spend. [onMade] gets the invite made, to open it.
@Composable
fun NewInviteScreen(engine: Engine, onBack: () -> Unit, onMade: (InviteRecord) -> Unit) {
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

/// One invite (PeelCore's `InviteDetailScreen`): a close button and its name, the amount and where it
/// stands, its QR code, Copy / Share / QR, and, pinned at the bottom, Cancel while it waits.
@Composable
fun InviteDetailScreen(engine: Engine, storage: Storage, code: String, onClose: () -> Unit) {
    // The stored row moves as the server answers (status), even while Peel was closed.
    var stored by remember(code) { mutableStateOf(storage.invite(code)) }
    LaunchedEffect(code) {
        while (true) {
            stored = storage.invite(code)
            delay(1_000)
        }
    }
    val invite = stored
    if (invite == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var confirmingCancel by remember { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val ready = invite.status == InviteRecord.Status.READY
    val shareable = ready || invite.status == InviteRecord.Status.SENDING

    fun cancel() {
        confirmingCancel = false
        working = true
        problem = null
        scope.launch {
            try {
                engine.cancelInvite(invite.code)
                toast.show("Invite cancelled.", ToastType.Success)
            } catch (e: Exception) {
                problem = e.message ?: "Could not cancel the invite."
            } finally {
                working = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.align(Alignment.CenterStart).size(36.dp).clip(CircleShape).background(Theme.colors.fieldFill).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Theme.colors.text, modifier = Modifier.size(18.dp)) }
            Text(
                invite.nickname ?: "Invite",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text,
                maxLines = 1, modifier = Modifier.padding(horizontal = 48.dp),
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Text(Sats.format(invite.amountMsat), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Theme.colors.text, textAlign = TextAlign.Center)
            Text(invite.statusLine(), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            // White while it can be scanned; faint and empty until the server holds its sats.
            Box(
                Modifier.size(248.dp).clip(RoundedCornerShape(20.dp)).background(if (ready) Color.White else Theme.colors.fieldFill),
                contentAlignment = Alignment.Center,
            ) {
                if (ready) QrCodeImage(invite.link, size = 224.dp, contentDescription = "QR code of the invite")
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InviteButton(
                    "Copy invite", Icons.Filled.ContentCopy, enabled = shareable, modifier = Modifier.weight(1f),
                ) { AndroidClipboardAccess.sensitive(context).copy(invite.link, SensitiveClipboard.DEFAULT_CLEAR_MS * 2) }
                InviteButton("Share", Icons.Filled.Share, enabled = shareable, modifier = Modifier.weight(1f)) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite.link)
                    context.startActivity(Intent.createChooser(send, "Share invite"))
                }
                InviteButton("QR", Icons.Filled.QrCode, enabled = ready, modifier = Modifier.weight(1f)) { enlarged = true }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Anyone with this invite can claim its sats.",
                style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText, textAlign = TextAlign.Center,
            )
            invite.message?.let { message ->
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Theme.colors.fieldFill).padding(12.dp)) {
                    Text("Your message", style = MaterialTheme.typography.labelMedium, color = Theme.colors.secondaryText)
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text)
                }
            }
            problem?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
        }
        // Pinned to the bottom of the screen.
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                !invite.isPending -> InviteButton("Done", null, modifier = Modifier.fillMaxWidth(), onClick = onClose)
                confirmingCancel -> {
                    Text(
                        "Get the sats back?", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    InviteButton("Cancel invite", null, modifier = Modifier.fillMaxWidth()) { cancel() }
                    TextButton(onClick = { confirmingCancel = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Keep it", color = Theme.colors.secondaryText)
                    }
                }
                else -> {
                    if (invite.status == InviteRecord.Status.CANCELLING) {
                        InviteButton("Remove from list", null, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                            try {
                                engine.forgetInvite(invite.code)
                                onClose()
                            } catch (e: Exception) {
                                problem = e.message ?: "Could not remove it."
                            }
                        }
                    }
                    InviteButton(
                        if (invite.status == InviteRecord.Status.CANCELLING) "Ask again" else "Cancel invite", null,
                        enabled = InviteRules.canCancel(invite.status) && !working, working = working, modifier = Modifier.fillMaxWidth(),
                    ) { confirmingCancel = true }
                }
            }
        }
    }

    if (enlarged) {
        Dialog(onDismissRequest = { enlarged = false }) {
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).clickable { enlarged = false }.padding(16.dp)) {
                QrCodeImage(invite.link, size = 300.dp, contentDescription = "QR code of the invite")
            }
        }
    }
}

/// A bordered, dark-card button in the normal text colour, with an optional leading icon.
@Composable
private fun InviteButton(
    text: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    working: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Theme.colors.inputBorder),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Theme.colors.fieldFill, contentColor = Theme.colors.text),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        if (working) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Theme.colors.text)
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
    }
}
