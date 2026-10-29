package com.sphinx.peel.ui.dashboard

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.sphinx.peel.engine.AttachmentKind
import com.sphinx.peel.engine.DownloadedAttachment
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AudioPlayer
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.PeelLogo
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.components.formatDuration
import com.sphinx.peel.ui.theme.Theme
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/// One chat (docs/design/iphone.md, screen 2 — `ConversationScreen.swift`):
/// a header with the back arrow and the chat's name, its messages flat
/// (not bubbles — a run's first row carries the avatar and name in the
/// sender's colour, the rest of the run under it, same as the Mac/iPhone),
/// and the composer at the bottom. Long-press a message to reply to it.
/// No thread, calls, pinned message, mentions or boosts yet — see
/// `docs/phases/05-conversation.md`/`docs/phases/07-reply.md`.
@Composable
fun ConversationScreen(engine: Engine, storage: Storage, chat: String, onBack: () -> Unit) {
    val isAgent = chat == engine.agentPubkey
    var conversation by remember(chat) { mutableStateOf<com.sphinx.peel.storage.ConversationRow?>(null) }
    var messages by remember(chat) { mutableStateOf(emptyList<MessageRow>()) }
    var draft by remember(chat) { mutableStateOf("") }
    var replyTo by remember(chat) { mutableStateOf<MessageRow?>(null) }
    var error by remember(chat) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val restoreProgress by engine.restoreProgress.collectAsState()
    val audioPlayer = remember(chat) { AudioPlayer() }
    DisposableEffect(chat) { onDispose { audioPlayer.release() } }
    // MediaPlayer has no position-changed callback of its own; polled
    // while something is actually playing, not continuously.
    LaunchedEffect(audioPlayer.isPlaying) {
        while (audioPlayer.isPlaying) {
            audioPlayer.refreshPosition()
            delay(200)
        }
    }

    suspend fun reload() {
        conversation = withContext(Dispatchers.Default) { storage.conversations().firstOrNull { it.chat == chat } }
        messages = withContext(Dispatchers.Default) { storage.messages(chat) }
        // Viewing a chat marks it read, here and on the server — every
        // reload, not only the first, so a message that arrives while
        // this screen is still open is caught too. Cheap when there is
        // nothing new: `markRead` is a no-op past the first call for the
        // same marker (`Storage.setRead`'s own "did anything change" check).
        engine.markRead(chat)
    }
    LaunchedEffect(chat, restoreProgress) { reload() }
    LaunchedEffect(chat) {
        while (true) {
            delay(3_000)
            reload()
        }
    }

    val isTribe = conversation?.isTribe ?: false
    val title = if (isAgent) "Peel" else (conversation?.name ?: chat.take(12))
    val myAlias = storage.value(com.sphinx.peel.storage.Setting.ALIAS)?.takeIf { it.isNotEmpty() } ?: "You"

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Theme.colors.text)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Theme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isAgent) {
                Spacer(Modifier.width(8.dp))
                AgentTag()
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            itemsIndexed(messages, key = { _, row -> row.id.takeIf { it != 0L } ?: (row.uuid ?: row.hashCode()) }) { index, row ->
                val previous = messages.getOrNull(index - 1)
                val startsRun = previous == null || runSenderKey(previous) != runSenderKey(row)
                val replyUuid = replyUuid(row)
                val parent = replyUuid?.let { uuid -> messages.firstOrNull { it.uuid == uuid } }
                MessageRowView(
                    row = row, startsRun = startsRun, isAgent = isAgent, isTribe = isTribe, myAlias = myAlias,
                    replyUuid = replyUuid, parent = parent, engine = engine, audioPlayer = audioPlayer,
                    onReply = { replyTo = row },
                )
            }
        }

        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Theme.colors.destructive,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Composer(
            text = draft,
            onTextChange = { draft = it },
            placeholder = if (isAgent) "Ask Peel" else "Message $title",
            replyTarget = replyTo?.let { target -> (if (target.fromMe) myAlias else senderAlias(target)) to previewContent(target) },
            onCancelReply = { replyTo = null },
            onSend = {
                val toSend = draft
                val replyUuid = replyTo?.uuid
                draft = ""
                replyTo = null
                error = null
                scope.launch {
                    try {
                        engine.sendMessage(chat, toSend, isTribe, replyUuid)
                        reload()
                    } catch (e: Exception) {
                        error = e.message ?: "Could not send that."
                    }
                }
            },
        )
    }
}

/// What separates one run of messages from the next: the account itself
/// for anything `fromMe` (one voice, whoever is actually signed in), or
/// the member's own alias for a tribe's other messages — the chat's own
/// pubkey is shared by every member there, so it cannot tell them apart
/// (`Engine.contactHint`'s own comment on why).
private fun runSenderKey(row: MessageRow): String {
    if (row.fromMe) return "me"
    val sender = row.sender?.let { runCatching { JSONObject(it) }.getOrNull() }
    return sender?.optString("alias")?.takeIf { it.isNotEmpty() } ?: row.chat
}

private fun senderJson(row: MessageRow): JSONObject? = row.sender?.let { runCatching { JSONObject(it) }.getOrNull() }

private fun senderAlias(row: MessageRow): String = senderJson(row)?.optString("alias")?.takeIf { it.isNotEmpty() } ?: "Someone"

/// A message's own text, from `body`'s `content` field — the same body
/// `Engine.sendMessage` writes, and the one `mapMessage` stores as is.
private fun previewContent(row: MessageRow): String =
    row.body?.let { runCatching { JSONObject(it).optString("content") }.getOrNull() }.orEmpty()

/// What `row` replies to, by uuid — `body`'s `replyUuid` field
/// (`Engine.sendMessage`'s own `replyTo` parameter writes it; an incoming
/// reply carries it the same way, PeelCore's `BodyFields.replyUuid`).
private fun replyUuid(row: MessageRow): String? =
    row.body?.let { runCatching { JSONObject(it).optString("replyUuid") }.getOrNull() }?.takeIf { it.isNotEmpty() }

/// A message of this type carries an attachment, caption (`content`)
/// optional — PeelCore's `MessageType.attachment`.
private const val ATTACHMENT_TYPE = 6

@Composable
private fun MessageRowView(
    row: MessageRow,
    startsRun: Boolean,
    isAgent: Boolean,
    isTribe: Boolean,
    myAlias: String,
    replyUuid: String?,
    parent: MessageRow?,
    engine: Engine,
    audioPlayer: AudioPlayer,
    onReply: () -> Unit,
) {
    val sender = senderJson(row)
    val name = if (row.fromMe) myAlias else senderAlias(row)
    val photoUrl = if (row.fromMe) null else sender?.optString("photo_url")?.takeIf { it.isNotEmpty() }
    val hueKey = sender?.optString("pubkey")?.takeIf { it.isNotEmpty() } ?: row.chat
    val content = previewContent(row)
    val time = row.timestamp?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(it) }

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (row.fromMe) Theme.colors.accent.copy(alpha = 0.07f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = {}, onLongClick = onReply.takeIf { row.uuid != null })
            .padding(top = if (startsRun) 8.dp else 1.dp, bottom = 2.dp),
    ) {
        Box(Modifier.width(32.dp)) {
            if (startsRun) {
                if (isAgent && row.fromMe.not()) {
                    PeelLogo(size = 32.dp)
                } else {
                    Avatar(name = name, url = photoUrl, size = 32.dp, hue = PersonHue.of(hueKey), shape = AvatarShape.CIRCLE)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (startsRun) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PersonHue.of(hueKey).fill,
                        maxLines = 1,
                    )
                    if (time != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(time, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
                    }
                }
            }
            if (replyUuid != null) {
                Spacer(Modifier.height(2.dp))
                QuoteBox(parent = parent, myAlias = myAlias)
                Spacer(Modifier.height(2.dp))
            }
            if (row.type == ATTACHMENT_TYPE) {
                AttachmentView(row, engine, audioPlayer)
                if (content.isNotEmpty()) Spacer(Modifier.height(4.dp))
            }
            if (content.isNotEmpty() || row.type != ATTACHMENT_TYPE) {
                Text(
                    content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Theme.colors.text,
                    modifier = Modifier.alpha(if (row.status == "PENDING") 0.6f else 1f),
                )
            }
            if (row.status == "FAILED") {
                Text("Not delivered", style = MaterialTheme.typography.labelSmall, color = Theme.colors.destructive)
            }
        }
    }
}

private sealed class AttachmentState {
    data object Loading : AttachmentState()
    data class Failed(val message: String) : AttachmentState()
    data class Loaded(val attachment: DownloadedAttachment) : AttachmentState()
}

/// One attachment: downloads and decrypts it (`Engine.downloadAttachment`)
/// the moment its row appears, then shows it — an image inline (Coil,
/// from the decrypted file on disk), anything else as a named row that
/// opens in whatever app the person has for it. No tap-to-load gate:
/// every attachment in a chat downloads as soon as it's shown, same as
/// a small chat's worth of images costs little either way; a busier
/// one may want that gate later.
@Composable
private fun AttachmentView(row: MessageRow, engine: Engine, audioPlayer: AudioPlayer) {
    var state by remember(row.id, row.uuid) { mutableStateOf<AttachmentState>(AttachmentState.Loading) }
    LaunchedEffect(row.id, row.uuid) {
        state = try {
            AttachmentState.Loaded(engine.downloadAttachment(row))
        } catch (e: Exception) {
            AttachmentState.Failed(e.message ?: "Could not load this attachment.")
        }
    }
    when (val current = state) {
        is AttachmentState.Loading -> AttachmentPlaceholder {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Theme.colors.secondaryText)
        }
        is AttachmentState.Failed -> AttachmentPlaceholder(isError = true) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Theme.colors.destructive, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(current.message, style = MaterialTheme.typography.labelSmall, color = Theme.colors.destructive)
        }
        is AttachmentState.Loaded -> AttachmentContent(current.attachment, audioPlayer)
    }
}

@Composable
private fun AttachmentPlaceholder(isError: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isError) Theme.colors.destructive.copy(alpha = 0.08f) else Theme.colors.fieldFill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun AttachmentContent(attachment: DownloadedAttachment, audioPlayer: AudioPlayer) {
    if (attachment.kind == AttachmentKind.IMAGE) {
        AsyncImage(
            model = attachment.file,
            contentDescription = attachment.fileName ?: "Image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Theme.colors.fieldFill),
        )
        return
    }
    if (attachment.kind == AttachmentKind.AUDIO) {
        AudioAttachmentRow(attachment, audioPlayer)
        return
    }
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Theme.colors.fieldFill)
            .clickable { openAttachment(context, attachment) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(attachmentIcon(attachment.kind), contentDescription = null, tint = Theme.colors.secondaryText, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                attachment.fileName ?: fallbackFileName(attachment.kind),
                style = MaterialTheme.typography.bodyMedium,
                color = Theme.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formattedSize(attachment.size), style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
        }
    }
}

/// A voice message: Play/Pause and a draggable progress bar, in place
/// of the generic named row every other attachment kind gets — the one
/// kind worth playing in place rather than handing to another app.
@Composable
private fun AudioAttachmentRow(attachment: DownloadedAttachment, audioPlayer: AudioPlayer) {
    val scope = rememberCoroutineScope()
    val isThis = audioPlayer.playingFile == attachment.file
    val playing = isThis && audioPlayer.isPlaying
    val position = if (isThis) audioPlayer.positionMs else 0
    val duration = if (isThis && audioPlayer.durationMs > 0) audioPlayer.durationMs else 0
    // Dragging sets this so the thumb follows the finger instead of
    // snapping back to the player's own (slower-to-update) position
    // until the drag lands.
    var dragPosition by remember(attachment.file) { mutableStateOf<Float?>(null) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Theme.colors.fieldFill)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { scope.launch { audioPlayer.toggle(attachment.file) } }, modifier = Modifier.size(36.dp)) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = Theme.colors.accent,
            )
        }
        Spacer(Modifier.width(4.dp))
        Slider(
            value = dragPosition ?: (if (duration > 0) position.toFloat() / duration else 0f),
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                val target = dragPosition
                if (target != null && duration > 0) audioPlayer.seekTo((target * duration).toInt())
                dragPosition = null
            },
            modifier = Modifier.weight(1f).height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = Theme.colors.accent,
                activeTrackColor = Theme.colors.accent,
                inactiveTrackColor = Theme.colors.inputBorder,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            formatDuration(if (duration > 0) (dragPosition?.let { (it * duration).toInt() } ?: position) else 0),
            style = MaterialTheme.typography.labelSmall,
            color = Theme.colors.secondaryText,
            modifier = Modifier.width(36.dp),
        )
    }
}

private fun attachmentIcon(kind: AttachmentKind) = when (kind) {
    AttachmentKind.VIDEO -> Icons.Filled.Videocam
    AttachmentKind.AUDIO -> Icons.Filled.AudioFile
    AttachmentKind.PDF -> Icons.Filled.PictureAsPdf
    AttachmentKind.IMAGE, AttachmentKind.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun fallbackFileName(kind: AttachmentKind) = when (kind) {
    AttachmentKind.VIDEO -> "video"
    AttachmentKind.AUDIO -> "audio"
    AttachmentKind.PDF -> "file.pdf"
    AttachmentKind.IMAGE, AttachmentKind.FILE -> "file"
}

private fun formattedSize(bytes: Int): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/// Hands the file to whatever app the person has for it, as a
/// `content://` URI (`FileProvider`, declared in the manifest) — never
/// a raw `file://` path, which apps on recent Android refuse to open.
private fun openAttachment(context: android.content.Context, attachment: DownloadedAttachment) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", attachment.file)
    val mimeType = attachment.fileName
        ?.substringAfterLast('.', "")
        ?.takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        ?: when (attachment.kind) {
            AttachmentKind.VIDEO -> "video/*"
            AttachmentKind.AUDIO -> "audio/*"
            AttachmentKind.PDF -> "application/pdf"
            AttachmentKind.IMAGE, AttachmentKind.FILE -> "*/*"
        }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

/// The message a reply answers: who and a one-line preview — PeelCore's
/// `QuoteBox`, minus "tap to go to it" (no scroll-to-message here yet).
/// `parent` null is "Original message not available" (not stored, or
/// too far back to have loaded) — PeelCore's `Quote.missing`.
@Composable
private fun QuoteBox(parent: MessageRow?, myAlias: String) {
    val shape = RoundedCornerShape(8.dp)
    if (parent == null) {
        Text(
            "Original message not available",
            style = MaterialTheme.typography.labelSmall,
            color = Theme.colors.secondaryText,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Theme.colors.fieldFill)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        return
    }
    val name = if (parent.fromMe) myAlias else senderAlias(parent)
    val hueKey = senderJson(parent)?.optString("pubkey")?.takeIf { it.isNotEmpty() } ?: parent.chat
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme.colors.fieldFill)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = PersonHue.of(hueKey).fill)
        Text(
            previewContent(parent),
            style = MaterialTheme.typography.labelSmall,
            color = Theme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AgentTag() {
    Text(
        "AGENT",
        style = MaterialTheme.typography.labelSmall,
        color = Theme.colors.secondaryText,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Theme.colors.fieldFill)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/// The capsule text field and send button — PeelCore's `PhoneComposer`,
/// trimmed to text only: no attach, mentions, voice or the sats bolt.
/// `replyTarget` (name, preview) shows PeelCore's `ReplyingTo` above the
/// capsule, with its own dismiss; long-pressing a row sets it.
@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    replyTarget: Pair<String, String>?,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (replyTarget != null) {
            val (name, preview) = replyTarget
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Replying to $name",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Theme.colors.text,
                    )
                    Text(
                        preview,
                        style = MaterialTheme.typography.labelSmall,
                        color = Theme.colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onCancelReply, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Stop replying", tint = Theme.colors.secondaryText)
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Theme.colors.fieldFill)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.secondaryText)
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Theme.colors.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Theme.colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val canSend = text.isNotBlank()
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Theme.colors.button.copy(alpha = if (canSend) 1f else 0.4f))
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Theme.colors.buttonText)
            }
        }
    }
}
