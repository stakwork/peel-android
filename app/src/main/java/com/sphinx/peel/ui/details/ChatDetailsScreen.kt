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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.sphinx.peel.engine.TimeZoneSharing
import com.sphinx.peel.engine.TribeInfo
import com.sphinx.peel.storage.ConversationRow
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.ConversationAvatar
import com.sphinx.peel.ui.payments.PageFrame
import com.sphinx.peel.ui.payments.QrCodeImage
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// A chat's details (the iPhone's `ChatDetailsScreen`), opened by tapping
/// the conversation's header: a tribe's, or a contact's. Pushed over the
/// conversation like the other sub-pages (`PageFrame`); Back returns to it.
/// Only what Peel holds is shown, rows with nothing in them hidden; actions
/// only where Android already has the function (copy, share a link, the
/// time zone choice).
@Composable
fun ChatDetailsScreen(engine: Engine, storage: Storage, conversation: ConversationRow, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    if (conversation.isTribe) {
        TribeDetails(engine, storage, conversation, onBack)
    } else {
        ContactDetails(engine, conversation, onBack)
    }
}

@Composable
private fun TribeDetails(engine: Engine, storage: Storage, conversation: ConversationRow, onBack: () -> Unit) {
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
    // Only a private tribe is sent the time zone: known from an earlier look, or once the registry answers.
    var offersTimeZone by remember(conversation.chat) { mutableStateOf(engine.offersTimeZone(conversation.chat, true)) }
    LaunchedEffect(info) {
        info?.isPrivate?.let {
            engine.recordTribePrivacy(conversation.chat, it)
            offersTimeZone = it
        }
    }

    PageFrame("Tribe details", onBack) {
        Hero(conversation, info?.name ?: conversation.name, ChatDetails.heroSubtitle(info))
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
        ChatDetails.seenHere(info)?.let { seen ->
            Spacer(Modifier.height(12.dp))
            FactsCard(listOf(ChatDetails.Fact("Seen here", seen)))
        }
        if (link != null) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Tribe link",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Theme.colors.secondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                    QrCodeImage(link, size = 220.dp, contentDescription = "QR code of the tribe link")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(link))
                    toast.show("Link copied", ToastType.Success)
                }) { Text("Copy link") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(send, "Share tribe link"))
                }) { Text("Share") }
            }
            Spacer(Modifier.height(12.dp))
            MonoValue(link)
        }
        if (offersTimeZone) {
            Spacer(Modifier.height(24.dp))
            TimeZoneSection(engine, conversation.chat, "#${info?.name ?: conversation.name}")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ContactDetails(engine: Engine, conversation: ConversationRow, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val toast = LocalToast.current
    PageFrame("Details", onBack) {
        Hero(conversation, conversation.name, null)
        Spacer(Modifier.height(24.dp))
        SectionLabel("Public key")
        MonoValue(conversation.chat)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(conversation.chat))
            toast.show("Key copied", ToastType.Success)
        }) { Text("Copy key") }
        Spacer(Modifier.height(24.dp))
        TimeZoneSection(engine, conversation.chat, conversation.name)
        Spacer(Modifier.height(24.dp))
    }
}

/// The top of a details page: the picture at 64 dp, the name and a line under it, centred.
@Composable
private fun Hero(conversation: ConversationRow, name: String, subtitle: String?) {
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
        subtitle?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText, textAlign = TextAlign.Center)
        }
    }
}

/// "Time zone": the switch "Share my time zone with <chat>" and the picker row, always both
/// shown (the picker stays when the switch is off). Each change is saved at once
/// (`Engine.setTimeZoneChoice`, synced as the preferences file's `timezone` item).
@Composable
private fun TimeZoneSection(engine: Engine, chat: String, title: String) {
    var choice by remember(chat) { mutableStateOf(engine.timeZoneChoice(chat)) }
    var picking by remember { mutableStateOf(false) }
    val deviceId = remember { java.util.TimeZone.getDefault().id }
    val now = System.currentTimeMillis()
    val zoneText = (if (choice.identifier.isEmpty()) "Automatic · $deviceId" else choice.identifier.replace('_', ' ')) +
        " (" + TimeZoneSharing.offsetLabel(choice.identifier, now) + ")"
    SectionLabel("Time zone")
    Column(Modifier.fillMaxWidth().border(1.dp, Theme.colors.inputBorder, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Share my time zone with $title", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text, modifier = Modifier.weight(1f))
            Switch(checked = choice.enabled, onCheckedChange = {
                engine.setTimeZoneChoice(chat, it, choice.identifier)
                choice = engine.timeZoneChoice(chat)
            })
        }
        HorizontalDivider(color = Theme.colors.divider)
        Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Time zone", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText, modifier = Modifier.weight(1f))
            Text(zoneText, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 220.dp))
        }
    }
    if (picking) {
        TimeZonePicker(deviceId, now, onDismiss = { picking = false }) { identifier ->
            engine.setTimeZoneChoice(chat, choice.enabled, identifier)
            choice = engine.timeZoneChoice(chat)
            picking = false
        }
    }
}

/// The searchable list: "Automatic · <device zone>" first, then every zone the device knows.
@Composable
private fun TimeZonePicker(deviceId: String, now: Long, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val options = remember(query) { TimeZoneSharing.options(query, deviceId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time zone") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(options, key = { it.identifier }) { option ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(option.identifier) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(option.title, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text, modifier = Modifier.weight(1f))
                            Text(TimeZoneSharing.offsetLabel(option.identifier, now), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
