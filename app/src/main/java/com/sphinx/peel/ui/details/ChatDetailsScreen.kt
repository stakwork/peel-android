package com.sphinx.peel.ui.details

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sphinx.peel.engine.ChatDetails
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.TimeZones
import com.sphinx.peel.engine.TribeInfo
import com.sphinx.peel.storage.ConversationRow
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.ConversationAvatar
import com.sphinx.peel.ui.payments.PageFrame
import com.sphinx.peel.ui.payments.QrCode
import com.sphinx.peel.ui.payments.QrCodeImage
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/// A chat's details (the iPhone's `ChatDetailsScreen`), opened by tapping
/// the conversation's header: a tribe's, or a contact's. Pushed over the
/// conversation like the other sub-pages (`PageFrame`); Back returns to it.
/// Only what Peel holds is shown, rows with nothing in them hidden; actions
/// only where Android already has the function (copy, share a link).
@Composable
fun ChatDetailsScreen(engine: Engine, storage: Storage, conversation: ConversationRow, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    if (conversation.isTribe) {
        TribeDetails(storage, conversation, onBack)
    } else {
        ContactDetails(engine, storage, conversation, onBack)
    }
}

@Composable
private fun TribeDetails(storage: Storage, conversation: ConversationRow, onBack: () -> Unit) {
    val host = remember { storage.value(Setting.TRIBE_HOST)?.takeIf { it.isNotEmpty() } }
    // What the registry says now (description, tags, owner, members); the
    // stored name, photo and prices show at once and stay if it can't be read.
    val info by produceState<TribeInfo?>(null, conversation.chat) {
        value = host?.let { h -> withContext(Dispatchers.IO) { runCatching { TribeInfo.fetch(conversation.chat, h) }.getOrNull() } }
    }
    val stored = remember(conversation.chat) { storage.contact(conversation.chat) }
    val facts = ChatDetails.tribeFacts(info, stored?.pricePerMessageMsat ?: 0, stored?.escrowMsat ?: 0)
    val link = ChatDetails.tribeLink(conversation.chat, host)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val toast = LocalToast.current

    PageFrame("Tribe details", onBack) {
        Hero(conversation, info?.name ?: conversation.name)
        info?.description?.let {
            Spacer(Modifier.height(16.dp))
            SelectionContainer {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        info?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
            Spacer(Modifier.height(12.dp))
            Text(
                tags.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = Theme.colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Tags: ${tags.joinToString(", ")}" },
            )
        }
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            FactsCard(facts)
        }
        if (link != null) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Tribe link")
            MonoValue(link)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(link))
                    toast.show("Link copied", ToastType.Success)
                }) { Text("Copy link") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(send, "Share tribe link"))
                }) { Text("Share link") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ContactDetails(engine: Engine, storage: Storage, conversation: ConversationRow, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val toast = LocalToast.current
    val since = remember(conversation.chat) { storage.firstMessageTime(conversation.chat) }
    val theirZone by produceState<ZoneId?>(null, conversation.chat) {
        value = withContext(Dispatchers.IO) { engine.theirTimeZone(conversation.chat) }
    }
    PageFrame("Details", onBack) {
        Hero(conversation, conversation.name)
        // Under the name, like the iPhone: since when, and their time when it is not yours.
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            since?.let {
                val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                HeroLine("Contact since $day")
            }
            theirZone?.let { zone ->
                TimeZones.localTimeLine(zone, Instant.now(), ZoneId.systemDefault(), ::localTime)?.let { HeroLine(it) }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Public key",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Theme.colors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrCodeImage(conversation.chat, 220.dp, "QR code of their public key")
        }
        Spacer(Modifier.height(12.dp))
        MonoValue(conversation.chat)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(conversation.chat))
                toast.show("Key copied", ToastType.Success)
            }) { Text("Copy key") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, conversation.chat)
                context.startActivity(Intent.createChooser(send, "Share public key"))
            }) { Text("Share") }
            OutlinedButton(onClick = { shareQr(context, conversation.chat) }) { Text("QR") }
        }
        Spacer(Modifier.height(24.dp))
        TimeZoneSection(engine, conversation)
        Spacer(Modifier.height(24.dp))
    }
}

private fun localTime(at: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone).format(at)

/// The QR as a PNG in the shared cache folder, handed to the share sheet.
private fun shareQr(context: android.content.Context, text: String) {
    val bitmap = QrCode.bitmap(text) ?: return
    val file = java.io.File(context.cacheDir, "outgoing/key-qr.png")
    file.parentFile?.mkdirs()
    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share QR code"))
}

/// The "Time zone" section: the switch for sharing your offset with this
/// contact, and a picker to send another zone's instead (the default entry
/// uses the device's).
@Composable
private fun TimeZoneSection(engine: Engine, conversation: ConversationRow) {
    val toast = LocalToast.current
    var sharing by remember(conversation.chat) { mutableStateOf(engine.timeZoneSharing(conversation.chat)) }
    var identifier by remember(conversation.chat) { mutableStateOf(engine.timeZoneIdentifier(conversation.chat)) }
    var picking by remember { mutableStateOf(false) }
    SectionLabel("Time zone")
    Column(Modifier.fillMaxWidth().border(1.dp, Theme.colors.inputBorder, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Share my time zone with ${conversation.name}",
                style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, modifier = Modifier.weight(1f),
            )
            Switch(checked = sharing, onCheckedChange = {
                sharing = it
                engine.setTimeZoneSharing(conversation.chat, it)
            })
        }
        if (sharing) {
            HorizontalDivider(color = Theme.colors.divider)
            Box {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { picking = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Time zone", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, modifier = Modifier.weight(1f))
                    Text(
                        identifier ?: TimeZones.DEFAULT_CHOICE,
                        style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
                        textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }, modifier = Modifier.heightIn(max = 400.dp)) {
                    for (choice in TimeZones.choices) {
                        DropdownMenuItem(text = { Text(choice) }, onClick = {
                            picking = false
                            val new = choice.takeIf { it != TimeZones.DEFAULT_CHOICE }
                            if (new != identifier) {
                                identifier = new
                                engine.setTimeZoneIdentifier(conversation.chat, new)
                                toast.show("Time zone changed", ToastType.Success)
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText, textAlign = TextAlign.Center)
}

/// The top of a details page: the picture at 64 dp and the name, centred.
@Composable
private fun Hero(conversation: ConversationRow, name: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ConversationAvatar(conversation, 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Theme.colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { contentDescription = name },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Theme.colors.secondaryText, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun MonoValue(text: String) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = Theme.colors.text,
            modifier = Modifier.fillMaxWidth().background(Theme.colors.fieldFill, RoundedCornerShape(12.dp)).padding(12.dp),
        )
    }
}

@Composable
private fun FactsCard(facts: List<ChatDetails.Fact>) {
    Column(Modifier.fillMaxWidth().border(1.dp, Theme.colors.inputBorder, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp)) {
        facts.forEachIndexed { i, fact ->
            if (i > 0) HorizontalDivider(color = Theme.colors.divider)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fact.label, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText, modifier = Modifier.weight(1f))
                Text(fact.value, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 220.dp))
            }
        }
    }
}
