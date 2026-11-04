package com.sphinx.peel.ui.payments

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.AndroidClipboardAccess
import com.sphinx.peel.account.SensitiveClipboard
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.Invoice
import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.PaymentInput
import com.sphinx.peel.engine.PaymentRefused
import com.sphinx.peel.engine.PaymentRules
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.createInvoice
import com.sphinx.peel.engine.decodeInvoice
import com.sphinx.peel.engine.invoiceProblem
import com.sphinx.peel.engine.paymentConfirmationMsat
import com.sphinx.peel.engine.payInvoice
import com.sphinx.peel.engine.sendPayment
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.Theme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Receive (the iPhone's `ReceiveSheet`): an amount and a memo, then the
/// invoice the server's node makes, as a QR code with Copy and Share and the
/// time until it expires. It turns to Paid when the server's history shows
/// it settled (`Engine.storeMoney`), even if Peel was closed meanwhile.
@Composable
fun ReceiveScreen(engine: Engine, storage: Storage, onBack: () -> Unit) {
    var amountText by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var record by remember { mutableStateOf<InvoiceRecord?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Live status: the stored record moves as history arrives.
    LaunchedEffect(record?.paymentHash) {
        val hash = record?.paymentHash ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            storage.invoice(hash, incoming = true)?.let { record = it }
        }
    }

    PageFrame("Receive", onBack) {
        val current = record
        if (current == null) {
            Text("Make an invoice for the sats you want. Whoever pays it sends them to this account, even while Peel is closed.",
                style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == ',' } },
                label = { Text("Amount (sats)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = memo, onValueChange = { memo = it }, label = { Text("Memo (optional)") }, modifier = Modifier.fillMaxWidth(),
            )
            problem?.let { Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
            val msat = Sats.msat(amountText)
            Button(
                enabled = msat != null && !working,
                onClick = {
                    working = true; problem = null
                    scope.launch {
                        try {
                            record = engine.createInvoice(msat!!, memo)
                        } catch (e: Exception) {
                            problem = e.message ?: "Could not make an invoice."
                        } finally {
                            working = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
            ) {
                if (working) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp) else Text("Make invoice")
            }
        } else {
            val expired = current.expiresAt?.let { it <= System.currentTimeMillis() } ?: false
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCodeImage(current.bolt11.uppercase(), contentDescription = "QR code of the invoice for ${Sats.format(current.amountMsat)}")
            }
            Spacer(Modifier.height(12.dp))
            Text("Invoice for ${Sats.format(current.amountMsat)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
            if (current.memo.isNotEmpty()) Text(current.memo, color = Theme.colors.secondaryText)
            Spacer(Modifier.height(8.dp))
            when {
                current.status == InvoiceRecord.Status.PAID ->
                    Text("Paid ${Sats.format(current.paidMsat ?: current.amountMsat)}", color = Theme.colors.confirmed, fontWeight = FontWeight.SemiBold)
                current.status == InvoiceRecord.Status.UNDERPAID ->
                    Text("Partly paid: ${Sats.number(current.paidMsat ?: 0)} of ${Sats.format(current.amountMsat)}. This invoice can't take the rest.", color = Theme.colors.attention)
                expired -> Text("This invoice has expired. Nothing was paid.", color = Theme.colors.attention)
                else -> Text(
                    "Waiting to be paid" + (current.expiresAt?.let { " · expires ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" } ?: ""),
                    color = Theme.colors.secondaryText,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { AndroidClipboardAccess.sensitive(context).copy(current.bolt11, SensitiveClipboard.DEFAULT_CLEAR_MS * 2) }, modifier = Modifier.weight(1f)) { Text("Copy") }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, current.bolt11)
                        context.startActivity(Intent.createChooser(send, "Share invoice"))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
            TextButton(onClick = { record = null; amountText = ""; memo = "" }) { Text(if (current.status == InvoiceRecord.Status.PAID) "Done" else "Another invoice", color = Theme.colors.accent) }
        }
    }
}

/// Send (the iPhone's `SendSheet`): paste or scan an invoice and pay it, or
/// pick a contact and send them sats. An invoice is read through the core;
/// what stops paying it is said before Pay is offered, and over the
/// confirmation threshold Pay asks once more with the amount.
@Composable
fun SendScreen(engine: Engine, storage: Storage, prefill: String = "", onBack: () -> Unit, onReceive: () -> Unit) {
    var text by remember { mutableStateOf(prefill) }
    var scanning by remember { mutableStateOf(false) }
    var contactChat by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    if (scanning) {
        InvoiceScanner(onFound = { text = it; scanning = false }, onCancel = { scanning = false })
        return
    }
    val chat = contactChat
    if (chat != null) {
        SendToContactForm(engine, storage, chat, onBack = { contactChat = null }, onDone = onBack)
        return
    }
    val input = remember(text) { if (text.isBlank()) null else PaymentInput.parse(text) }
    if (input is PaymentInput.Bolt11) {
        PayInvoiceForm(engine, storage, input.bolt11, clear = { text = "" }, onBack = onBack, onReceive = onReceive)
        return
    }
    PageFrame("Send", onBack) {
        Text("Pay an invoice", style = MaterialTheme.typography.labelLarge, color = Theme.colors.secondaryText)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, placeholder = { Text("lnbc…") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth(),
        )
        when (input) {
            is PaymentInput.Unsupported -> Text("That's ${input.kind}, which Peel can't pay. Paste a BOLT 11 invoice.", color = Theme.colors.attention, style = MaterialTheme.typography.bodySmall)
            PaymentInput.Unknown -> Text("That isn't an invoice.", color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall)
            else -> {}
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let { text = it.trim() } }) { Text("Paste") }
            OutlinedButton(onClick = { scanning = true }) { Text("Scan") }
        }
        val contacts = remember { storage.conversations().filter { !it.isTribe } }
        if (contacts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Or send to a contact", style = MaterialTheme.typography.labelLarge, color = Theme.colors.secondaryText)
            for (c in contacts) {
                Text(
                    c.alias?.takeIf { it.isNotEmpty() } ?: c.chat.take(12), color = Theme.colors.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { contactChat = c.chat }.padding(vertical = 14.dp),
                )
                HorizontalDivider(color = Theme.colors.divider)
            }
        }
    }
}

@Composable
private fun PayInvoiceForm(engine: Engine, storage: Storage, bolt11: String, clear: () -> Unit, onBack: () -> Unit, onReceive: () -> Unit) {
    var invoice by remember(bolt11) { mutableStateOf<Invoice?>(null) }
    var readProblem by remember(bolt11) { mutableStateOf<String?>(null) }
    var blocker by remember(bolt11) { mutableStateOf<String?>(null) }
    var record by remember(bolt11) { mutableStateOf<InvoiceRecord?>(null) }
    var working by remember(bolt11) { mutableStateOf(false) }
    var problem by remember(bolt11) { mutableStateOf<String?>(null) }
    var confirming by remember(bolt11) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(bolt11) {
        try {
            invoice = withContext(Dispatchers.Default) { engine.decodeInvoice(bolt11) }
        } catch (e: PaymentRefused) {
            readProblem = e.message
        }
    }
    // Re-checked every second: a payment moving, an expiry, a balance.
    LaunchedEffect(invoice?.paymentHash) {
        val inv = invoice ?: return@LaunchedEffect
        while (true) {
            record = storage.invoice(inv.paymentHash, incoming = false)
            blocker = withContext(Dispatchers.Default) { engine.invoiceProblem(inv) }
            delay(1_000)
        }
    }

    fun pay(confirmed: Long?) {
        val inv = invoice ?: return
        working = true; problem = null
        scope.launch {
            try {
                record = withContext(Dispatchers.Default) { engine.payInvoice(inv.bolt11, confirmed) }
            } catch (e: PaymentRefused) {
                if (e.confirmMsat != null) confirming = true else problem = e.message
            } catch (e: Exception) {
                problem = e.message ?: "Could not pay."
            } finally {
                working = false
            }
        }
    }

    PageFrame("Pay an invoice", onBack) {
        val inv = invoice
        if (inv == null) {
            Text(readProblem ?: "Reading the invoice…", color = if (readProblem != null) Theme.colors.destructive else Theme.colors.secondaryText)
            if (readProblem != null) TextButton(onClick = clear) { Text("Another invoice", color = Theme.colors.accent) }
            return@PageFrame
        }
        Text("Invoice for", style = MaterialTheme.typography.labelMedium, color = Theme.colors.secondaryText)
        Text("${Sats.number(inv.amountMsat)} sats", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Theme.colors.text)
        Spacer(Modifier.height(8.dp))
        val contactName = storage.contact(inv.payee)?.alias
        Fact("To", (contactName?.let { "$it · " } ?: "") + inv.payee.take(12) + if (contactName == null) ", not a contact" else "")
        if (inv.memo.isNotEmpty()) Fact("Memo", inv.memo)
        inv.expiresAtMs?.let {
            Fact("Expires", (if (inv.isExpired()) "Expired at " else "At ") + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)))
        }
        val rec = record
        when {
            rec != null && rec.status == InvoiceRecord.Status.PAID ->
                Text("Paid ${Sats.format(rec.amountMsat)}", color = Theme.colors.confirmed, fontWeight = FontWeight.SemiBold)
            rec != null && rec.status == InvoiceRecord.Status.PENDING ->
                Text("Paying… It stays pending until the server says how it went, and is never sent twice.", color = Theme.colors.secondaryText)
            blocker != null -> {
                Text(blocker!!, color = Theme.colors.attention)
                if (blocker!!.startsWith("That is more than your balance")) TextButton(onClick = onReceive) { Text("Receive sats", color = Theme.colors.accent) }
            }
            else -> {
                if (rec?.status == InvoiceRecord.Status.FAILED) Text("Last try failed (${rec.error ?: "failed"}). Nothing was sent.", color = Theme.colors.attention)
                Text("The routing fee is added on top.", style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            }
        }
        problem?.let { Text(it, color = Theme.colors.destructive, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        if (rec?.status == InvoiceRecord.Status.PAID) {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        } else if (rec?.status != InvoiceRecord.Status.PENDING) {
            if (blocker == null) {
                Button(
                    onClick = {
                        if (PaymentRules.needsConfirmation(inv.amountMsat, null, engine.paymentConfirmationMsat())) confirming = true else pay(null)
                    },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
                ) { Text(if (working) "Paying…" else "Pay ${Sats.format(inv.amountMsat)}") }
            }
            TextButton(onClick = clear, enabled = !working) { Text("Another invoice", color = Theme.colors.accent) }
        }
        if (confirming) {
            ConfirmPaymentDialog(inv.amountMsat, onConfirm = { confirming = false; pay(inv.amountMsat) }, onDismiss = { confirming = false })
        }
    }
}

/// The second touch over the threshold: names exactly the amount, and the
/// payment is sent only for that amount.
@Composable
fun ConfirmPaymentDialog(amountMsat: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send ${Sats.format(amountMsat)}?") },
        text = { Text("This is more than your confirmation limit. Check the amount before you send.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SendToContactForm(engine: Engine, storage: Storage, chat: String, onBack: () -> Unit, onDone: () -> Unit) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val name = storage.contact(chat)?.alias?.takeIf { it.isNotEmpty() } ?: chat.take(12)
    val msat = Sats.msat(amountText)

    fun send(confirmed: Long?) {
        working = true; problem = null
        scope.launch {
            try {
                withContext(Dispatchers.Default) { engine.sendPayment(chat, msat!!, note, confirmed) }
                sent = true
            } catch (e: PaymentRefused) {
                if (e.confirmMsat != null) confirming = true else problem = e.message
            } catch (e: Exception) {
                problem = e.message ?: "Could not send."
            } finally {
                working = false
            }
        }
    }

    PageFrame("Send to $name", onBack) {
        if (sent) {
            Text("Sent ${Sats.format(msat ?: 0)} to $name.", color = Theme.colors.confirmed, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onDone) { Text("Done", color = Theme.colors.accent) }
            return@PageFrame
        }
        OutlinedTextField(
            value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == ',' } },
            label = { Text("Amount (sats)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
        problem?.let { Text(it, color = Theme.colors.destructive, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = msat != null && !working,
            onClick = { send(null) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
        ) { Text(if (working) "Sending…" else "Send") }
        if (confirming && msat != null) {
            ConfirmPaymentDialog(msat, onConfirm = { confirming = false; send(msat) }, onDismiss = { confirming = false })
        }
    }
}
