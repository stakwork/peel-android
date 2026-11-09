package com.sphinx.peel.ui.tribes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.ChatDetails
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.JoinOffer
import com.sphinx.peel.engine.NotConnectedException
import com.sphinx.peel.engine.PaymentRefused
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.TribeRefused
import com.sphinx.peel.engine.joinTribe
import com.sphinx.peel.engine.lookupTribe
import com.sphinx.peel.ui.payments.CodeScanner
import com.sphinx.peel.ui.payments.PageFrame
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlinx.coroutines.launch

/// Join a tribe (the iPhone's join sheet): a `sphinx.chat` tribe link, `pubkey@host`, a bare public
/// key or a uuid, pasted or scanned. "Look up" reads the tribe's registry entry and shows what
/// joining costs; "Join" then pays it, through the usual balance and confirmation checks.
@Composable
fun JoinTribeScreen(engine: Engine, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var offer by remember { mutableStateOf<JoinOffer?>(null) }
    var confirmMsat by remember { mutableStateOf<Long?>(null) }
    val clipboard = LocalClipboardManager.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()

    if (scanning) {
        CodeScanner(
            prompt = "Point the camera at the tribe's QR code.",
            accept = { scanned -> text = scanned.trim(); offer = null; scanning = false; null },
            onCancel = { scanning = false },
        )
        return
    }

    fun lookUp() {
        working = true
        problem = null
        scope.launch {
            try {
                offer = engine.lookupTribe(text)
            } catch (e: TribeRefused) {
                problem = e.message
            } catch (e: Exception) {
                problem = e.message ?: "Could not look that up."
            } finally {
                working = false
            }
        }
    }

    fun join(confirmed: Long?) {
        val chosen = offer ?: return
        working = true
        problem = null
        scope.launch {
            try {
                val chat = engine.joinTribe(chosen, confirmed)
                toast.show(
                    if (chosen.info.isPrivate == true) "Request sent. The owner decides." else "Joined #${chosen.info.name}.",
                    ToastType.Success,
                )
                onOpenChat(chat)
            } catch (e: PaymentRefused) {
                val ask = e.confirmMsat
                if (ask != null) confirmMsat = ask else problem = e.message
            } catch (e: NotConnectedException) {
                problem = "Not connected. Try again in a moment."
            } catch (e: TribeRefused) {
                problem = e.message
            } catch (e: Exception) {
                problem = e.message ?: "Could not join."
            } finally {
                working = false
            }
        }
    }

    PageFrame("Join a tribe", onBack) {
        Text(
            "Paste a tribe link, a public key (add @host to name its server) or a uuid, or scan its QR code.",
            style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(2_000); offer = null }, label = { Text("Tribe link, key or uuid") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let { text = it.trim(); offer = null } }) { Text("Paste") }
            OutlinedButton(onClick = { scanning = true }) { Text("Scan QR") }
        }
        offer?.let { found ->
            Spacer(Modifier.height(8.dp))
            Text("#${found.info.name}", style = MaterialTheme.typography.titleMedium, color = Theme.colors.text)
            found.info.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText, modifier = Modifier.padding(top = 4.dp))
            }
            val join = ChatDetails.joinPrice(found.info.priceToJoinMsat)
            Text(
                "To join: ${Sats.format(found.amountMsat)}" + (join.note?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText, modifier = Modifier.padding(top = 4.dp),
            )
            if (found.info.isPrivate == true) {
                Text("The owner approves new members.", style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            }
            if (found.alreadyJoined) {
                Text("You are already in this tribe.", style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            }
        }
        problem?.let { Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = text.isNotBlank() && !working,
            onClick = { if (offer == null) lookUp() else join(null) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
        ) {
            if (working) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
            else Text(if (offer == null) "Look up" else if (offer?.alreadyJoined == true) "Open" else "Join")
        }
    }

    confirmMsat?.let { amount ->
        AlertDialog(
            onDismissRequest = { confirmMsat = null },
            title = { Text("Join this tribe?") },
            text = { Text("Joining sends ${Sats.format(amount)}.") },
            confirmButton = { TextButton(onClick = { confirmMsat = null; join(amount) }) { Text("Join", color = Theme.colors.accent) } },
            dismissButton = { TextButton(onClick = { confirmMsat = null }) { Text("Cancel") } },
        )
    }
}
