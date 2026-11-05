package com.sphinx.peel.ui.contacts

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
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.InviteRefused
import com.sphinx.peel.engine.KEY_EXCHANGE_MSAT
import com.sphinx.peel.engine.NotConnectedException
import com.sphinx.peel.engine.PING_MSAT
import com.sphinx.peel.engine.PaymentRefused
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.addContactByCode
import com.sphinx.peel.ui.payments.CodeScanner
import com.sphinx.peel.ui.payments.PageFrame
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlinx.coroutines.launch

/// Add someone who already has Peel (the iPhone's `AddContactSheet`): their contact code
/// (`pubkey_lsp_scid`, a `share_contact` link, or an invite, whose inviter is added), pasted or
/// scanned. A bare public key needs the route hint (`lsp_scid`) beside it. Adding starts a key
/// exchange that pays [KEY_EXCHANGE_MSAT] (and a 1-sat ping), through the usual balance and
/// confirmation checks.
@Composable
fun AddContactScreen(engine: Engine, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var routeHint by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmMsat by remember { mutableStateOf<Long?>(null) }
    val clipboard = LocalClipboardManager.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()

    if (scanning) {
        CodeScanner(
            prompt = "Point the camera at their contact code.",
            accept = { text -> code = text.trim(); scanning = false; null },
            onCancel = { scanning = false },
        )
        return
    }

    val bareKey = code.trim().let { it.length == 66 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }

    fun add(confirmed: Long?) {
        working = true
        problem = null
        scope.launch {
            try {
                val (pubkey, added) = engine.addContactByCode(
                    code, alias = name.ifBlank { null }, confirmedMsat = confirmed, routeHint = routeHint.takeIf { bareKey },
                )
                toast.show(if (added) "Added. Their key is on its way." else "They are already your contact.", ToastType.Success)
                onOpenChat(pubkey)
            } catch (e: PaymentRefused) {
                val ask = e.confirmMsat
                if (ask != null) confirmMsat = ask else problem = e.message
            } catch (e: NotConnectedException) {
                problem = "Not connected. Try again in a moment."
            } catch (e: InviteRefused) {
                problem = e.message
            } catch (e: Exception) {
                problem = e.message ?: "Could not add them."
            } finally {
                working = false
            }
        }
    }

    PageFrame("Add contact", onBack) {
        Text(
            "Paste their contact code, or scan it. Adding them costs ${Sats.format(KEY_EXCHANGE_MSAT)} " +
                "(and ${Sats.format(PING_MSAT)} for the ping), which they get as you meet.",
            style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = code, onValueChange = { code = it.take(3_000) }, label = { Text("Contact code") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let { code = it.trim() } }) { Text("Paste") }
            OutlinedButton(onClick = { scanning = true }) { Text("Scan QR") }
        }
        if (bareKey) {
            OutlinedTextField(
                value = routeHint, onValueChange = { routeHint = it.trim().take(100) },
                label = { Text("Route hint (lsp_scid)") }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(100) }, label = { Text("Name (optional)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        problem?.let { Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = code.isNotBlank() && (!bareKey || routeHint.isNotBlank()) && !working,
            onClick = { add(null) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
        ) {
            if (working) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp) else Text("Add")
        }
    }

    confirmMsat?.let { amount ->
        AlertDialog(
            onDismissRequest = { confirmMsat = null },
            title = { Text("Add this contact?") },
            text = { Text("This sends ${Sats.format(amount)} to start the key exchange.") },
            confirmButton = { TextButton(onClick = { confirmMsat = null; add(amount) }) { Text("Add", color = Theme.colors.accent) } },
            dismissButton = { TextButton(onClick = { confirmMsat = null }) { Text("Cancel") } },
        )
    }
}
