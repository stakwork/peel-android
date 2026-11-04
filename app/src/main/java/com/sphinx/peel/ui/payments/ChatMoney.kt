package com.sphinx.peel.ui.payments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.InvoiceRecord
import com.sphinx.peel.engine.PaymentRefused
import com.sphinx.peel.engine.PaymentRules
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.payRequest
import com.sphinx.peel.engine.paymentConfirmationMsat
import com.sphinx.peel.engine.requestInvoiceOf
import com.sphinx.peel.engine.requestPayment
import com.sphinx.peel.engine.sendPayment
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.Theme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// What the chat's "Send sats" / "Request sats" menu items open.
enum class ChatMoneyAction { SEND, REQUEST }

/// Sats sent in a direct message: the bolt in the photo's place, "You sent
/// 1,000 sats" as the header, the note, and for yours the wait or failure
/// (the iPhone's `PaymentRowView`).
@Composable
fun PaymentMessage(row: MessageRow, name: String) {
    val note = remember(row.body) { noteOf(row) }
    val msat = row.msat ?: 0L
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Theme.colors.attention.copy(alpha = if (row.deleted) 0.06f else 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Bolt, null, Modifier.size(16.dp), tint = if (row.deleted) Theme.colors.secondaryText else Theme.colors.attention)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.alpha(if (row.status == "PENDING") 0.6f else 1f)) {
            val headline = when {
                row.deleted -> "This payment has been deleted"
                msat > 0 -> if (row.fromMe) "You sent ${Sats.format(msat)}" else "$name sent you ${Sats.format(msat)}"
                else -> if (row.fromMe) "You sent a payment" else "$name sent a payment with no sats"
            }
            Text(headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
            if (!row.deleted && note != null) Text(note, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text)
            if (row.fromMe && !row.deleted) when (row.status) {
                "PENDING" -> Text("Payment waiting to go", style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
                "FAILED" -> Text("Payment not sent", style = MaterialTheme.typography.labelSmall, color = Theme.colors.destructive)
            }
        }
    }
}

private fun noteOf(row: MessageRow): String? =
    runCatching { org.json.JSONObject(row.body ?: return null).optString("content") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

/// A request to be paid: who asked, how much (the invoice's, never the
/// message's words), its memo, whether it is paid, and for one someone else
/// sent, Pay (the iPhone's `RequestRowView`, with the Pay the iPhone leaves
/// for the Mac). Paying goes through `Engine.payRequest`: the same rules as
/// a pasted invoice, and over the confirmation threshold Pay asks once more.
@Composable
fun RequestMessage(row: MessageRow, name: String, engine: Engine, storage: Storage, onError: (String) -> Unit) {
    val invoice = remember(row.body) { engine.requestInvoiceOf(row.body) }
    val scope = rememberCoroutineScope()
    var record by remember(invoice?.paymentHash) { mutableStateOf<InvoiceRecord?>(null) }
    var working by remember(row.id) { mutableStateOf(false) }
    var confirming by remember(row.id) { mutableStateOf(false) }

    // Where it stands: our own request is the incoming record (paid by the
    // server's history or the payer's type-3 message); one asked of us is
    // the outgoing record we create paying it.
    LaunchedEffect(invoice?.paymentHash) {
        val hash = invoice?.paymentHash ?: return@LaunchedEffect
        while (true) {
            record = storage.invoice(hash, incoming = row.fromMe)
            delay(1_500)
        }
    }

    fun pay(confirmed: Long?) {
        working = true
        scope.launch {
            try {
                withContext(Dispatchers.Default) { engine.payRequest(row, confirmed) }
                record = invoice?.let { storage.invoice(it.paymentHash, incoming = false) }
            } catch (e: PaymentRefused) {
                if (e.confirmMsat != null) confirming = true else onError(e.message ?: "Could not pay.")
            } catch (e: Exception) {
                onError(e.message ?: "Could not pay.")
            } finally {
                working = false
            }
        }
    }

    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Theme.colors.fieldFill).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bolt, null, Modifier.size(14.dp), tint = Theme.colors.secondaryText)
            Spacer(Modifier.width(6.dp))
            Text(if (row.fromMe) "You requested" else "$name requested", style = MaterialTheme.typography.labelMedium, color = Theme.colors.secondaryText)
            if (record?.status == InvoiceRecord.Status.PAID) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = Theme.colors.confirmed)
                Text("Paid", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
            }
        }
        if (invoice == null) {
            Text("Can't read this invoice, so it can't be paid.", style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
        } else {
            Text(Sats.format(invoice.amountMsat), style = MaterialTheme.typography.titleLarge, color = Theme.colors.text)
            if (invoice.memo.isNotEmpty()) Text(invoice.memo, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text)
            val paysRequester = invoice.payee == row.chat
            if (!row.fromMe && !paysRequester) {
                Text("This invoice pays ${invoice.payee.take(12)}, not $name.", style = MaterialTheme.typography.bodySmall, color = Theme.colors.attention)
            }
            val status = record?.status
            val expired = invoice.isExpired()
            Text(
                when {
                    status == InvoiceRecord.Status.UNDERPAID -> "Partly paid. This invoice can't take the rest."
                    status == InvoiceRecord.Status.PAID -> (if (row.fromMe) "Paid" else "You paid") +
                        (record?.paidAt?.let { " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "")
                    status == InvoiceRecord.Status.PENDING -> "Paying…"
                    expired -> "Expired" + (invoice.expiresAtMs?.let { " at " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "")
                    row.fromMe -> "Waiting to be paid" + (invoice.expiresAtMs?.let { " · expires " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "")
                    else -> "Not paid yet"
                },
                style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
            )
            val canPay = !row.fromMe && !expired && status != InvoiceRecord.Status.PAID && status != InvoiceRecord.Status.PENDING &&
                status != InvoiceRecord.Status.UNDERPAID
            if (canPay) {
                Button(
                    onClick = {
                        if (PaymentRules.needsConfirmation(invoice.amountMsat, null, engine.paymentConfirmationMsat())) confirming = true else pay(null)
                    },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
                ) { Text(if (working) "Paying…" else "Pay ${Sats.format(invoice.amountMsat)}") }
            }
            if (confirming) ConfirmPaymentDialog(invoice.amountMsat, onConfirm = { confirming = false; pay(invoice.amountMsat) }, onDismiss = { confirming = false })
        }
        if (row.fromMe && row.status == "FAILED") Text("Not delivered", style = MaterialTheme.typography.labelSmall, color = Theme.colors.destructive)
    }
}

/// The dialog behind the chat's "Send sats" and "Request sats": an amount
/// and a note or memo. Sending over the confirmation threshold asks once
/// more, naming the amount.
@Composable
fun ChatMoneyDialog(
    action: ChatMoneyAction,
    engine: Engine,
    chat: String,
    name: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val msat = Sats.msat(amountText)
    val send = action == ChatMoneyAction.SEND

    fun go(confirmed: Long?) {
        working = true; problem = null
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    if (send) engine.sendPayment(chat, msat!!, note, confirmed) else engine.requestPayment(chat, msat!!, note)
                }
                onDone()
            } catch (e: PaymentRefused) {
                if (e.confirmMsat != null) confirming = true else problem = e.message
            } catch (e: Exception) {
                problem = e.message ?: "Could not send that."
            } finally {
                working = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(if (send) "Send sats to $name" else "Request sats from $name") },
        text = {
            Column {
                OutlinedTextField(
                    value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == ',' } },
                    label = { Text("Amount (sats)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, label = { Text(if (send) "Note (optional)" else "Memo (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let { Text(it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = msat != null && !working, onClick = { go(null) }) { Text(if (working) "Working…" else if (send) "Send" else "Request") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirming && msat != null) {
        ConfirmPaymentDialog(msat, onConfirm = { confirming = false; go(msat) }, onDismiss = { confirming = false })
    }
}
