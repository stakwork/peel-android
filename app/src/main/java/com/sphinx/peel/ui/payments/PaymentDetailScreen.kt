package com.sphinx.peel.ui.payments

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.Theme
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// A payment's detail (iOS `PaymentDetailScreen`): what it was and the
/// amount, who it was with, a way to its chat, the message a fee was for,
/// then its facts in a group. Not ported: routing fee, key-exchange and
/// ping facts (the Android history has none of those kinds).
@Composable
internal fun PaymentDetailScreen(storage: Storage, id: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val item = remember(id) {
        PaymentItem.build(storage.payments(Int.MAX_VALUE), storage.invoices(), contactOf = storage::contact, messageOf = storage::messageByPaymentHash)
            .firstOrNull { it.id == id }
    }
    val myAlias = remember { storage.value(Setting.ALIAS)?.takeIf { it.isNotEmpty() } ?: "You" }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Theme.colors.text) }
            }
            Spacer(Modifier.width(12.dp))
            Text("Payment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
        }
        if (item == null) {
            Text("This payment is gone.", color = Theme.colors.secondaryText, modifier = Modifier.padding(20.dp))
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Top(item, onOpenChat)
            item.quotedMessage?.let { QuotedCard("Your message", it, myAlias) }
                ?: item.note?.let { NoteCard(if (item.kind == PaymentItem.Kind.INVOICE) "Memo" else "Note", it) }
            Facts(item)
        }
    }
}

@Composable
private fun Top(item: PaymentItem, onOpenChat: (String) -> Unit) {
    val done = item.status == PaymentItem.Status.COMPLETE
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text(item.headline, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    Sats.signed(item.amountMsat, item.incoming), fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    color = if (!done) Theme.colors.secondaryText else if (item.incoming) Theme.colors.confirmed else Theme.colors.text,
                    textDecoration = if (item.status == PaymentItem.Status.FAILED) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.width(6.dp))
                Text("sats", style = MaterialTheme.typography.titleMedium, color = Theme.colors.secondaryText, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Column {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
            Text(item.context, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
        }
        item.chat?.let { chat ->
            OutlinedButton(onClick = { onOpenChat(chat) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (item.isTribe) "Open #${item.chatName.orEmpty()}" else "Open chat with ${item.chatName ?: "them"}")
            }
        }
    }
}

@Composable
private fun Card(label: String, content: @Composable () -> Unit) {
    Column {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge, color = Theme.colors.secondaryText, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Theme.colors.fieldFill).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun NoteCard(label: String, text: String) = Card(label) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
}

/// A message a payment was for: who and when, then its text.
@Composable
private fun QuotedCard(label: String, message: MessageRow, myAlias: String) = Card(label) {
    val sender = if (message.fromMe) myAlias else
        message.sender?.let { runCatching { org.json.JSONObject(it).optString("alias") }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: "Someone"
    val text = message.body?.let { runCatching { org.json.JSONObject(it).optString("content") }.getOrNull() }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Text(sender, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Theme.colors.accent)
            message.timestamp?.let {
                Text(" · ${SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it))}", style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, maxLines = 8)
    }
}

@Composable
private fun Facts(item: PaymentItem) {
    val context = LocalContext.current
    val (statusText, statusIcon, settled) = when (item.status) {
        PaymentItem.Status.COMPLETE -> Triple(if (item.incoming) "Received" else "Settled", Icons.Filled.Check, true)
        PaymentItem.Status.PENDING ->
            Triple(if (item.isToContact) "Waiting for ${item.chatName ?: "them"} to come online, can't be cancelled" else "Pending", Icons.Filled.Schedule, false)
        PaymentItem.Status.FAILED ->
            Triple(if (item.incoming) item.error ?: "Not received" else "Failed${item.error?.let { ": $it" } ?: ""}, nothing was sent", Icons.Filled.Close, false)
    }
    Card("") {
        FactBlock("Status") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(statusIcon, null, Modifier.size(16.dp), tint = if (settled) Theme.colors.confirmed else Theme.colors.text)
                Spacer(Modifier.width(6.dp))
                Text(statusText, color = if (settled) Theme.colors.confirmed else Theme.colors.text)
            }
        }
        FactBlock("When") { Text(moment(item.ts), color = Theme.colors.text) }
        FactBlock("Amount") {
            Text("${Sats.format(item.amountMsat)} · ${NumberFormat.getIntegerInstance().format(item.amountMsat)} msat", color = Theme.colors.text)
        }
        FactBlock("Payment hash") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PaymentItem.shortKey(item.rhash), fontFamily = FontFamily.Monospace, color = Theme.colors.text, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { copy(context, item.rhash) }) { Text("Copy") }
            }
        }
    }
}

@Composable
private fun FactBlock(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
        value()
    }
}

/// iOS `Formatting.moment(seconds: true)`: "Wednesday, 4 November 2026 at 11:22:50".
internal fun moment(ts: Long, locale: Locale = Locale.getDefault(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    val date = SimpleDateFormat("EEEE, d MMMM yyyy", locale).apply { timeZone = zone }.format(Date(ts))
    val time = SimpleDateFormat("HH:mm:ss", locale).apply { timeZone = zone }.format(Date(ts))
    return "$date at $time"
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Payment hash", text))
    Toast.makeText(context, "Payment hash copied", Toast.LENGTH_SHORT).show()
}
