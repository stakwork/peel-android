package com.sphinx.peel.ui.dashboard

import android.content.Intent
import android.webkit.MimeTypeMap
import com.sphinx.peel.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import coil3.compose.AsyncImage
import com.sphinx.peel.engine.ATTACHMENT_MESSAGE_TYPE
import com.sphinx.peel.engine.AttachmentKind
import com.sphinx.peel.engine.BOOST_MESSAGE_TYPE
import com.sphinx.peel.engine.declaredAmountMsat
import com.sphinx.peel.engine.CALL_MESSAGE_TYPE
import com.sphinx.peel.engine.CallLink
import com.sphinx.peel.engine.DELETE_MESSAGE_TYPE
import com.sphinx.peel.engine.DIRECT_PAYMENT_MESSAGE_TYPE
import com.sphinx.peel.engine.INVOICE_MESSAGE_TYPE
import com.sphinx.peel.ui.payments.ChatMoneyAction
import com.sphinx.peel.ui.payments.ChatMoneyDialog
import com.sphinx.peel.ui.payments.PaymentMessage
import com.sphinx.peel.ui.payments.RequestMessage
import com.sphinx.peel.engine.CallPresence
import com.sphinx.peel.engine.DownloadedAttachment
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.MessageRow
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AudioPlayer
import com.sphinx.peel.ui.components.VideoAttachment
import com.sphinx.peel.ui.components.mediaBubble
import com.sphinx.peel.ui.components.readImageSize
import com.sphinx.peel.ui.components.ZoomableImageDialog
import com.sphinx.peel.ui.components.readMediaDurationMs
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.PeelLogo
import com.sphinx.peel.ui.call.CallScreen
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.components.formatDuration
import com.sphinx.peel.ui.components.prepareImageForSending
import com.sphinx.peel.ui.components.VoiceRecorder
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.engine.MessageMarkup
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
fun ConversationScreen(
    engine: Engine,
    storage: Storage,
    chat: String,
    onBack: () -> Unit,
    onJoinCall: (chat: String, title: String, link: CallLink, camera: Boolean) -> Unit,
) {
    val isAgent = chat == engine.agentPubkey
    var conversation by remember(chat) { mutableStateOf<com.sphinx.peel.storage.ConversationRow?>(null) }
    var messages by remember(chat) { mutableStateOf(emptyList<MessageRow>()) }
    var draft by remember(chat) { mutableStateOf("") }
    var replyTo by remember(chat) { mutableStateOf<MessageRow?>(null) }
    var openThreadRoot by remember(chat) { mutableStateOf<String?>(null) }
    var error by remember(chat) { mutableStateOf<String?>(null) }
    var moneyAction by remember(chat) { mutableStateOf<ChatMoneyAction?>(null) }
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

    // Where "new messages" starts, captured once as the screen opens -
    // PeelCore's own `AppModel.newMessages`, the read marker as it stood
    // right before this chat's `markRead` moves it, kept only if there
    // was anything unread to mark the boundary of (otherwise no line).
    var newAfterCaptured by remember(chat) { mutableStateOf(false) }
    var newAfterIndex by remember(chat) { mutableStateOf<Long?>(null) }

    suspend fun reload() {
        conversation = withContext(Dispatchers.Default) { storage.conversations().firstOrNull { it.chat == chat } }
        messages = withContext(Dispatchers.Default) { storage.messages(chat) }
        if (!newAfterCaptured) {
            newAfterCaptured = true
            newAfterIndex = if ((conversation?.unread ?: 0L) > 0L) {
                withContext(Dispatchers.Default) { storage.lastRead(chat) } ?: -1L
            } else null
        }
        // Viewing a chat marks it read, here and on the server — every
        // reload, not only the first, so a message that arrives while
        // this screen is still open is caught too. Cheap when there is
        // nothing new: `markRead` is a no-op past the first call for the
        // same marker (`Storage.setRead`'s own "did anything change" check).
        engine.markRead(chat)
    }
    LaunchedEffect(chat, restoreProgress) { reload() }
    // Entering a chat: ask the server what became of its unconfirmed sends,
    // so their bolts settle (PeelCore's `AppModel.selectedChat` didSet). The
    // engine also asks on foreground, reconnect and history pages via `openChat`.
    DisposableEffect(chat) {
        engine.openChat = chat
        onDispose { if (engine.openChat == chat) engine.openChat = null }
    }
    LaunchedEffect(chat) { engine.checkOwnMessageStatus(chat) }
    LaunchedEffect(chat) {
        while (true) {
            delay(3_000)
            reload()
        }
    }

    val isTribe = conversation?.isTribe ?: false
    val title = if (isAgent) "Peel" else (conversation?.name ?: chat.take(12))
    val myAlias = storage.value(com.sphinx.peel.storage.Setting.ALIAS)?.takeIf { it.isNotEmpty() } ?: "You"
    val myPhotoUrl = storage.value(com.sphinx.peel.storage.Setting.PHOTO_URL)?.takeIf { it.isNotEmpty() }
    val mentionMembers = remember(messages, conversation, isTribe, isAgent, myPhotoUrl) {
        val peer = conversation?.takeIf { !isTribe && !isAgent }?.let { MentionMember(it.alias ?: title, it.photoUrl, it.chat) }
        chatMentionMembers(messages, myAlias, myPhotoUrl, isTribe, peer)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var sendingLabel by remember(chat) { mutableStateOf<String?>(null) }
    var cameraOutputUri by remember(chat) { mutableStateOf<android.net.Uri?>(null) }

    fun sendImage(uri: android.net.Uri) {
        scope.launch {
            sendingLabel = "Sending photo…"
            error = null
            try {
                val jpeg = withContext(Dispatchers.Default) { prepareImageForSending(context, uri) }
                    ?: throw IllegalStateException("That doesn't look like an image.")
                val caption = draft
                val replyUuid = replyTo?.uuid
                val inThread = replyTo?.let { threadRoot(it) }
                draft = ""
                replyTo = null
                engine.sendImageAttachment(chat, jpeg, caption, isTribe, replyUuid, inThread)
                reload()
            } catch (e: Exception) {
                error = e.message ?: "Could not send that image."
            } finally {
                sendingLabel = null
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) sendImage(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraOutputUri
        if (success && uri != null) sendImage(uri)
    }

    // "Files" — any file, the one attach option that isn't image/camera
    // specific: a video reaches `sendFileAttachment` through here too,
    // the same way it does on PeelCore's own iPhone app (no dedicated
    // video picker there either — see `Engine.sendFileAttachment`).
    fun sendFile(uri: android.net.Uri) {
        scope.launch {
            sendingLabel = "Sending file…"
            error = null
            try {
                val (bytes, pickedName, mediaType) = withContext(Dispatchers.Default) {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    } ?: uri.lastPathSegment ?: "file"
                    val data = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("That file could not be read.")
                    Triple(data, name, resolver.getType(uri))
                }
                val caption = draft
                val replyUuid = replyTo?.uuid
                val inThread = replyTo?.let { threadRoot(it) }
                draft = ""
                replyTo = null
                engine.sendFileAttachment(chat, bytes, pickedName, mediaType, caption, isTribe, replyUuid, inThread)
                reload()
            } catch (e: Exception) {
                error = e.message ?: "Could not send that file."
            } finally {
                sendingLabel = null
            }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendFile(uri)
    }

    val voiceRecorder = remember(chat) { VoiceRecorder() }
    DisposableEffect(chat) { onDispose { voiceRecorder.cancel() } }

    fun sendVoice(wav: ByteArray) {
        scope.launch {
            sendingLabel = "Sending voice message…"
            error = null
            try {
                val replyUuid = replyTo?.uuid
                val inThread = replyTo?.let { threadRoot(it) }
                replyTo = null
                engine.sendVoiceMessage(chat, wav, isTribe, replyUuid, inThread)
                reload()
            } catch (e: Exception) {
                error = e.message ?: "Could not send that voice message."
            } finally {
                sendingLabel = null
            }
        }
    }

    fun startRecording() {
        voiceRecorder.start(scope) { wav -> if (wav != null) sendVoice(wav) }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
    }

    fun onPressRecord() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    fun sendBoost(target: MessageRow) {
        val targetUuid = target.uuid ?: return
        scope.launch {
            error = null
            try {
                engine.sendBoost(chat, targetUuid, isTribe)
                reload()
            } catch (e: Exception) {
                error = e.message ?: "Could not send that boost."
            }
        }
    }

    fun deleteMessage(target: MessageRow) {
        val targetUuid = target.uuid ?: return
        scope.launch {
            error = null
            try {
                engine.deleteMessage(chat, targetUuid, isTribe)
                reload()
            } catch (e: Exception) {
                error = e.message ?: "Could not delete that message."
            }
        }
    }

    // Starts a call, posts its message, then hands off to whatever's
    // holding the actual call above this screen (`MainTabs`) to join it
    // in-app — the same `onJoinCall` a received call card's own Join
    // already calls.
    var startingCall by remember(chat) { mutableStateOf(false) }
    fun startCall(audioOnly: Boolean) {
        scope.launch {
            startingCall = true
            error = null
            try {
                val row = engine.startCall(chat, isTribe, audioOnly)
                reload()
                val server = engine.callServer()
                val link = server?.let { CallLink.inMessage(previewContent(row), isCallType = true, server = it) }
                if (link != null) onJoinCall(chat, title, link, !audioOnly) else error = "Could not start a call."
            } catch (e: Exception) {
                error = e.message ?: "Could not start a call."
            } finally {
                startingCall = false
            }
        }
    }

    val listState = rememberLazyListState()
    // Hidden until the first load is positioned at the bottom (no
    // visible scroll); afterwards only follow a new message when the
    // user is already at the bottom or sent it themselves.
    var positioned by remember(chat) { mutableStateOf(false) }
    var previousLast by remember(chat) { mutableStateOf(-1) }

    val openRoot = openThreadRoot
    if (openRoot != null) {
        ThreadScreen(
            engine = engine, chat = chat, isTribe = isTribe, isAgent = isAgent, myAlias = myAlias, myPhotoUrl = myPhotoUrl, audioPlayer = audioPlayer,
            chatName = title, threadRootUuid = openRoot, messages = messages,
            onBack = { openThreadRoot = null },
            onSent = { scope.launch { reload() } },
            onJoinCall = { link -> onJoinCall(chat, title, link, false) },
        )
        return
    }

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
            // Only when this account has a call server at all (`callServer()`'s
            // own doc comment: null on a local test server, never configured) -
            // on an account with none, calling is off, not merely unreachable.
            if (engine.callServer() != null) {
                var callMenuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { callMenuOpen = true }, enabled = !startingCall) {
                        Icon(Icons.Filled.Call, contentDescription = "Start a call", tint = Theme.colors.text)
                    }
                    DropdownMenu(expanded = callMenuOpen, onDismissRequest = { callMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Start an audio call") },
                            onClick = { callMenuOpen = false; startCall(audioOnly = true) },
                        )
                        DropdownMenuItem(
                            text = { Text("Start a video call") },
                            onClick = { callMenuOpen = false; startCall(audioOnly = false) },
                        )
                    }
                }
            }
        }

        // Boosts are never a row of their own (PeelCore's own `BoostLineView`
        // shows them as a chip under the message they boost, by its own
        // `replyUuid`) - summed once per `messages` change, not per row.
        val boosts = remember(messages) { boostSummaries(messages) }
        // Nor is a thread's own reply ever shown inline, once it's really
        // a thread - PeelCore's own `rows(...)` collapses every message
        // that carries a `threadUuid` behind its root's "N replies" row,
        // but only in a tribe (a DM's timeline never builds the summary
        // dictionary at all - `Storage+Threads.swift`'s `timeline(in:)`
        // guards it `if isTribe`) and only once a thread has a second
        // message (`threadsSQL`'s own `HAVING COUNT(*) >= 2` for a tribe -
        // one reply alone still renders as an ordinary reply, quote box
        // and all). The root keeps its own content but moves to its
        // newest reply's chronological spot - PeelCore's `row.date =
        // message.date`, where `message` is the thread's latest
        // surviving message, not the root itself - so a thread that just
        // got a reply jumps to where that reply would sit, same as an
        // active chat bumping to the bottom.
        val threads = remember(messages, isTribe, myAlias, myPhotoUrl) { if (isTribe) threadSummaries(messages, myAlias, myPhotoUrl) else emptyMap() }
        val displayedMessages = remember(messages, threads) {
            messages.filter { it.type != BOOST_MESSAGE_TYPE && it.type != DELETE_MESSAGE_TYPE && it.type !in HIDDEN_MONEY_TYPES && it.threadUuid?.let { root -> !threads.containsKey(root) } ?: true }
                .sortedBy { row -> rowSortKey(row, threads) }
        }

        // Only our own first crossing into "new" gets the line - PeelCore's
        // own `rows(...)` clears `newAfter` the moment one row claims it.
        val newMessageIndex = remember(displayedMessages, newAfterIndex) {
            newMessagesIndex(displayedMessages, newAfterIndex, BOOST_MESSAGE_TYPE)
        }

        LaunchedEffect(displayedMessages.size) {
            val last = displayedMessages.size - 1
            if (last < 0) return@LaunchedEffect
            if (!positioned) {
                listState.scrollToItem(last)
                positioned = true
            } else {
                val atBottom = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= previousLast
                if (last > previousLast && (atBottom || displayedMessages[last].fromMe)) listState.animateScrollToItem(last)
            }
            previousLast = last
        }
        KeepAtBottomOnKeyboard(listState, displayedMessages.size - 1)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).alpha(if (positioned) 1f else 0f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            itemsIndexed(displayedMessages, key = { _, row -> row.id.takeIf { it != 0L } ?: (row.uuid ?: row.hashCode()) }) { index, row ->
                val previous = displayedMessages.getOrNull(index - 1)
                // Runs: one sender, a few minutes apart, on one day -
                // PeelCore's own rule (`ChatRow.swift`'s run-break check),
                // not sender identity alone.
                val startsDay = previous == null || !isSameDay(previous.timestamp, row.timestamp)
                val startsRun = startsDay || runSenderKey(previous) != runSenderKey(row) ||
                    ((row.timestamp ?: 0L) - (previous.timestamp ?: 0L) > 5 * 60 * 1000L)
                if (startsDay && row.timestamp != null) DayDivider(row.timestamp)
                if (index == newMessageIndex) NewMessagesDivider()
                val replyUuid = row.replyUuid
                val parent = replyUuid?.let { uuid -> messages.firstOrNull { it.uuid == uuid } }
                val next = displayedMessages.getOrNull(index + 1)
                val endsRun = next == null || !isSameDay(row.timestamp, next.timestamp) ||
                    runSenderKey(row) != runSenderKey(next) ||
                    ((next.timestamp ?: 0L) - (row.timestamp ?: 0L) > 5 * 60 * 1000L)
                val rowBoosts = row.uuid?.let { boosts[it] }
                MessageRowView(
                    row = row, startsRun = startsRun, endsRun = endsRun, isAgent = isAgent, isTribe = isTribe, myAlias = myAlias, myPhotoUrl = myPhotoUrl,
                    replyUuid = replyUuid, parent = parent, engine = engine, audioPlayer = audioPlayer,
                    boosts = rowBoosts,
                    threadSummary = row.uuid?.let { threads[it] },
                    onOpenThread = { row.uuid?.let { openThreadRoot = it } },
                    onReply = { replyTo = row },
                    onBoost = { sendBoost(row) }.takeIf { canBoost(row, rowBoosts) },
                    onJoinCall = { link -> onJoinCall(chat, title, link, false) },
                    onDelete = { deleteMessage(row) },
                    onMoneyError = { error = it },
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
            members = mentionMembers,
            placeholder = if (isAgent) "Ask Peel" else "Message $title",
            replyTarget = replyTo?.let { target -> (if (target.fromMe) myAlias else senderAlias(target)) to MessageMarkup.plainText(previewContent(target)) },
            onCancelReply = { replyTo = null },
            onPickPhoto = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onTakePhoto = {
                val file = java.io.File(context.cacheDir, "outgoing/camera-${System.currentTimeMillis()}.jpg")
                file.parentFile?.mkdirs()
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cameraOutputUri = uri
                cameraLauncher.launch(uri)
            },
            onPickFile = { fileLauncher.launch(arrayOf("*/*")) },
            recorder = voiceRecorder,
            onPressRecord = { onPressRecord() },
            onReleaseRecord = { voiceRecorder.release() },
            onCancelRecord = { voiceRecorder.cancel() },
            sendingLabel = sendingLabel,
            onSendSats = { moneyAction = ChatMoneyAction.SEND }.takeIf { !isTribe && !isAgent },
            onRequestSats = { moneyAction = ChatMoneyAction.REQUEST }.takeIf { !isTribe && !isAgent },
            onSend = {
                val toSend = draft
                val replyUuid = replyTo?.uuid
                // A reply always lands in a thread, the same thread its
                // own parent is already in (or starts a new one at the
                // parent, if it isn't in one yet) — PeelCore's `threadRoot`.
                val inThread = replyTo?.let { threadRoot(it) }
                draft = ""
                replyTo = null
                error = null
                // Sending reads the chat: "New messages" has done its job.
                newAfterIndex = null
                scope.launch {
                    try {
                        engine.sendMessage(chat, toSend, isTribe, replyUuid, inThread)
                        reload()
                    } catch (e: Exception) {
                        error = e.message ?: "Could not send that."
                    }
                }
            },
        )
        moneyAction?.let { action ->
            ChatMoneyDialog(
                action, engine, chat, title,
                onDismiss = { moneyAction = null },
                onDone = { moneyAction = null; scope.launch { reload() } },
            )
        }
    }
}

/// A thread (docs/design/iphone.md — `ThreadSheet.swift`): the root
/// message, then everything that replies to it, newest at the bottom,
/// and its own composer — always replying to the root, PeelCore's own
/// "a thread is flat" shape (a reply within a thread doesn't start a
/// nested one). The same shared `Composer` the main screen uses, so a
/// thread reply can carry a photo, a file or a voice message too — just
/// always addressed at the root, whatever `replyTo`/`inThread` a reply
/// from the main screen would otherwise carry.
@Composable
private fun ThreadScreen(
    engine: Engine,
    chat: String,
    isTribe: Boolean,
    isAgent: Boolean,
    myAlias: String,
    myPhotoUrl: String?,
    audioPlayer: AudioPlayer,
    chatName: String,
    threadRootUuid: String,
    messages: List<MessageRow>,
    onBack: () -> Unit,
    onSent: () -> Unit,
    onJoinCall: (CallLink) -> Unit,
) {
    var draft by remember(threadRootUuid) { mutableStateOf("") }
    var error by remember(threadRootUuid) { mutableStateOf<String?>(null) }
    var sendingLabel by remember(threadRootUuid) { mutableStateOf<String?>(null) }
    var cameraOutputUri by remember(threadRootUuid) { mutableStateOf<android.net.Uri?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val mentionMembers = remember(messages, myAlias, myPhotoUrl, isTribe) { chatMentionMembers(messages, myAlias, myPhotoUrl, isTribe, null) }
    val root = messages.firstOrNull { it.uuid == threadRootUuid }
    val replies = remember(messages, threadRootUuid) {
        messages.filter { it.threadUuid == threadRootUuid }.sortedBy { it.timestamp ?: 0L }
    }

    // A reply sent from inside a thread always targets the thread's own
    // root (`replyTo = inThread = threadRootUuid`) — this screen keeps
    // every thread flat, no nesting (Phase 9's own "Decided"). Every
    // attach path here is otherwise the main composer's own
    // `sendImage`/`sendFile`/`sendVoice`, just always addressed at the
    // root instead of whatever `replyTo` the main screen's own reply
    // state happens to hold.
    fun sendImage(uri: android.net.Uri) {
        scope.launch {
            sendingLabel = "Sending photo…"
            error = null
            try {
                val jpeg = withContext(Dispatchers.Default) { prepareImageForSending(context, uri) }
                    ?: throw IllegalStateException("That doesn't look like an image.")
                val caption = draft
                draft = ""
                engine.sendImageAttachment(chat, jpeg, caption, isTribe, replyTo = threadRootUuid, inThread = threadRootUuid)
                onSent()
            } catch (e: Exception) {
                error = e.message ?: "Could not send that image."
            } finally {
                sendingLabel = null
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) sendImage(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraOutputUri
        if (success && uri != null) sendImage(uri)
    }

    fun sendFile(uri: android.net.Uri) {
        scope.launch {
            sendingLabel = "Sending file…"
            error = null
            try {
                val (bytes, pickedName, mediaType) = withContext(Dispatchers.Default) {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    } ?: uri.lastPathSegment ?: "file"
                    val data = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("That file could not be read.")
                    Triple(data, name, resolver.getType(uri))
                }
                val caption = draft
                draft = ""
                engine.sendFileAttachment(chat, bytes, pickedName, mediaType, caption, isTribe, replyTo = threadRootUuid, inThread = threadRootUuid)
                onSent()
            } catch (e: Exception) {
                error = e.message ?: "Could not send that file."
            } finally {
                sendingLabel = null
            }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendFile(uri)
    }

    val voiceRecorder = remember(threadRootUuid) { VoiceRecorder() }
    DisposableEffect(threadRootUuid) { onDispose { voiceRecorder.cancel() } }

    fun sendVoice(wav: ByteArray) {
        scope.launch {
            sendingLabel = "Sending voice message…"
            error = null
            try {
                engine.sendVoiceMessage(chat, wav, isTribe, replyTo = threadRootUuid, inThread = threadRootUuid)
                onSent()
            } catch (e: Exception) {
                error = e.message ?: "Could not send that voice message."
            } finally {
                sendingLabel = null
            }
        }
    }

    fun startRecording() {
        voiceRecorder.start(scope) { wav -> if (wav != null) sendVoice(wav) }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
    }

    fun onPressRecord() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    // Boosts show and can be sent inside a thread too - a boostable
    // message doesn't stop being one just because it's shown here
    // rather than the main list.
    val boosts = remember(messages) { boostSummaries(messages) }

    fun sendBoost(target: MessageRow) {
        val targetUuid = target.uuid ?: return
        scope.launch {
            error = null
            try {
                engine.sendBoost(chat, targetUuid, isTribe)
                onSent()
            } catch (e: Exception) {
                error = e.message ?: "Could not send that boost."
            }
        }
    }

    fun deleteMessage(target: MessageRow) {
        val targetUuid = target.uuid ?: return
        scope.launch {
            error = null
            try {
                engine.deleteMessage(chat, targetUuid, isTribe)
                onSent()
            } catch (e: Exception) {
                error = e.message ?: "Could not delete that message."
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Thread", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Theme.colors.text)
                Text("#$chatName", style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText, maxLines = 1)
            }
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Theme.colors.secondaryText)
            }
        }

        val listState = rememberLazyListState()
        var positioned by remember(threadRootUuid) { mutableStateOf(false) }
        var previousLast by remember(threadRootUuid) { mutableStateOf(-1) }
        LaunchedEffect(replies.size) {
            val last = replies.size
            if (replies.isEmpty()) return@LaunchedEffect
            if (!positioned) {
                listState.scrollToItem(last)
                positioned = true
            } else {
                val atBottom = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= previousLast
                if (last > previousLast && (atBottom || replies[last - 1].fromMe)) listState.animateScrollToItem(last)
            }
            previousLast = last
        }
        KeepAtBottomOnKeyboard(listState, replies.size)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).alpha(if (positioned) 1f else 0f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (root != null) {
                item(key = "root") {
                    val rootBoosts = root.uuid?.let { boosts[it] }
                    // `threadSummary` here is only ever read by `canDelete`
                    // for its own `replyCount` — a real one (participant
                    // avatars, "N replies" text) would be this root's own
                    // collapsed row in the *main* list, not itself, inside
                    // the thread sheet that's already open because of it.
                    MessageRowView(
                        row = root, startsRun = true, endsRun = replies.firstOrNull()?.let { runSenderKey(root) != runSenderKey(it) } ?: true, isAgent = isAgent, isTribe = isTribe, myAlias = myAlias, myPhotoUrl = myPhotoUrl,
                        replyUuid = null, parent = null, engine = engine, audioPlayer = audioPlayer,
                        boosts = rootBoosts, threadSummary = null, deleteReplyCount = replies.size,
                        onOpenThread = {}, onReply = {},
                        onBoost = { sendBoost(root) }.takeIf { canBoost(root, rootBoosts) },
                        onJoinCall = onJoinCall,
                        onDelete = { deleteMessage(root) },
                    )
                    HorizontalDivider(color = Theme.colors.divider, modifier = Modifier.padding(vertical = 8.dp))
                }
            } else {
                item(key = "root-missing") {
                    Text(
                        "Original message not available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Theme.colors.secondaryText,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            itemsIndexed(replies, key = { _, row -> row.id.takeIf { it != 0L } ?: (row.uuid ?: row.hashCode()) }) { index, row ->
                val previous = replies.getOrNull(index - 1) ?: root
                val startsRun = previous == null || runSenderKey(previous) != runSenderKey(row)
                val next = replies.getOrNull(index + 1)
                val endsRun = next == null || runSenderKey(row) != runSenderKey(next)
                val rowBoosts = row.uuid?.let { boosts[it] }
                MessageRowView(
                    row = row, startsRun = startsRun, endsRun = endsRun, isAgent = isAgent, isTribe = isTribe, myAlias = myAlias, myPhotoUrl = myPhotoUrl,
                    replyUuid = null, parent = null, engine = engine, audioPlayer = audioPlayer,
                    boosts = rowBoosts, threadSummary = null, onOpenThread = {}, onReply = {},
                    onBoost = { sendBoost(row) }.takeIf { canBoost(row, rowBoosts) },
                    onJoinCall = onJoinCall,
                    onDelete = { deleteMessage(row) },
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
            members = mentionMembers,
            placeholder = "Reply",
            replyTarget = null,
            onCancelReply = {},
            onPickPhoto = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onTakePhoto = {
                val file = java.io.File(context.cacheDir, "outgoing/camera-${System.currentTimeMillis()}.jpg")
                file.parentFile?.mkdirs()
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cameraOutputUri = uri
                cameraLauncher.launch(uri)
            },
            onPickFile = { fileLauncher.launch(arrayOf("*/*")) },
            recorder = voiceRecorder,
            onPressRecord = { onPressRecord() },
            onReleaseRecord = { voiceRecorder.release() },
            onCancelRecord = { voiceRecorder.cancel() },
            sendingLabel = sendingLabel,
            onSend = {
                val toSend = draft
                draft = ""
                error = null
                scope.launch {
                    try {
                        engine.sendMessage(chat, toSend, isTribe, replyTo = threadRootUuid, inThread = threadRootUuid)
                        onSent()
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

/// A row's name, photo and avatar-colour key, one place instead of
/// derived at each of its call sites (`MessageRowView`'s own rendering,
/// a thread summary's participant avatars). A tribe member's hue is
/// keyed on their alias, never their pubkey — PeelCore's own
/// `PersonHue.colourKey` ignores pubkey for a tribe member on purpose
/// (`Sender.init(member:)`), because a tribe's forwarded messages often
/// carry the tribe's own shared pubkey as `sender.pubkey`, not the
/// member's — keying on it here would make two different members land
/// on the same colour.
private fun senderDisplay(row: MessageRow, myAlias: String, myPhotoUrl: String?, isTribe: Boolean): Triple<String, String?, String> {
    val sender = senderJson(row)
    val name = if (row.fromMe) myAlias else senderAlias(row)
    val photoUrl = if (row.fromMe) myPhotoUrl else sender?.optString("photo_url")?.takeIf { it.isNotEmpty() }
    val hueKey = if (isTribe && !row.fromMe) name else (sender?.optString("pubkey")?.takeIf { it.isNotEmpty() } ?: row.chat)
    return Triple(name, photoUrl, hueKey)
}

/// A message's own text, from `body`'s `content` field — the same body
/// `Engine.sendMessage` writes, and the one `mapMessage` stores as is.
private fun previewContent(row: MessageRow): String =
    row.body?.let { runCatching { JSONObject(it).optString("content") }.getOrNull() }.orEmpty()

/// Whether `row` counts toward a thread at all — PeelCore's own
/// `threadMessage` SQL filter (`type IN contentSQL AND noWords = 0`),
/// used for both the 2+-threshold check and the list of replies a
/// thread actually shows. A control message or payment carrying a
/// `threadUuid` (not something this port's own send paths produce, but
/// reachable from any other Sphinx client sharing the chat) shouldn't
/// count toward whether something is a thread at all, show up inside
/// the open thread sheet, or be a candidate for the "N replies" row's
/// participant avatars — same content-type check `canBoost` already
/// uses, plus a text reply must actually have words (an attachment
/// always counts, caption or not).
private fun isThreadContent(row: MessageRow): Boolean {
    if (row.deleted || (row.type != 0 && row.type != ATTACHMENT_MESSAGE_TYPE)) return false
    return row.type == ATTACHMENT_MESSAGE_TYPE || previewContent(row).isNotBlank()
}

/// How far (in dp) a finger may slide left or up from where the mic
/// was pressed before releasing cancels — PeelCore's own
/// `VoiceMessage.cancelDistance` (80 points).
private const val VOICE_CANCEL_DISTANCE_DP = 80

/// Money messages that are bookkeeping, not a row: the type-3 note a payer's
/// app sends once a request is paid (it flips the request to Paid), and the
/// paid-attachment purchase messages (7, 8, 9: not supported here,
/// `docs/money-parity.md`). PeelCore's `shownInChats` leaves them out too.
private val HIDDEN_MONEY_TYPES = setOf(3, 7, 8, 9)

private data class BoostSummary(val count: Int, val totalMsat: Long, val boostedByMe: Boolean)

/// Every boost in `messages`, summed per message it boosts — PeelCore's
/// `BoostSummary`, trimmed: no pending/unsettled/failed tracking — a
/// boost is a single send-or-fail message, same as a text send, not
/// something with its own multi-step delivery state to track.
private fun boostSummaries(messages: List<MessageRow>): Map<String, BoostSummary> {
    val byTarget = mutableMapOf<String, MutableList<MessageRow>>()
    for (row in messages) {
        if (row.type != BOOST_MESSAGE_TYPE) continue
        val target = row.replyUuid ?: continue
        byTarget.getOrPut(target) { mutableListOf() }.add(row)
    }
    return byTarget.mapValues { (_, boosts) ->
        BoostSummary(count = boosts.size, totalMsat = boosts.sumOf { it.msat ?: declaredAmountMsat(it.body) ?: 0L }, boostedByMe = boosts.any { it.fromMe })
    }
}

/// Someone else's content message, not yet boosted by this account —
/// PeelCore's own `Message.isBoostable`/`ChatRow.canBoost`, trimmed:
/// no `deleted` flag to check (this port tracks none, Phase 9's own
/// "Open"), and no call-link exclusion (this port has no calls at all
/// yet to produce one).
private fun canBoost(row: MessageRow, boosts: BoostSummary?): Boolean =
    !row.fromMe && !row.deleted && row.uuid != null && (row.type == 0 || row.type == ATTACHMENT_MESSAGE_TYPE) &&
        boosts?.boostedByMe != true

/// Whether Delete is offered — PeelCore's own `Message.isDeletable`
/// (`(fromMe || ownsTribe) && isLinkable(inTribe:) && threadCount < 2`),
/// trimmed: no `ownsTribe` bypass (this port tracks no tribe roles/
/// membership at all yet, so a tribe owner deleting someone else's
/// message doesn't work here — `docs/phases/14-message-menu.md`'s own
/// "Open"). `isLinkable`'s own delivery check: an undelivered send of
/// your own can't be deleted either (nothing has reached anyone else to
/// delete yet — Retry or just let it go, not this). `replyCount` is the
/// caller's own job to get right: the main list's own `threadSummaries`
/// map for a row shown there, `ThreadScreen`'s own `replies.size` for
/// the root row shown *inside* the thread it starts (whose own
/// `threadSummary` there is always null — that's the "N replies" chip
/// for the main list's collapsed row, not something to show a second
/// time on the very screen those replies are already listed on).
private fun canDelete(row: MessageRow, replyCount: Int): Boolean {
    if (!row.fromMe) return false
    if (row.deleted || row.uuid == null || (row.type != 0 && row.type != ATTACHMENT_MESSAGE_TYPE)) return false
    if (row.status == "PENDING" || row.status == "FAILED") return false
    return replyCount < 2
}

/// The people in this chat who can be `@mentioned`: everyone whose message
/// carries a sender here (this port keeps no tribe member table), plus the
/// DM `peer` when known. Never the account itself.
private fun chatMentionMembers(
    messages: List<MessageRow>,
    myAlias: String,
    myPhotoUrl: String?,
    isTribe: Boolean,
    peer: MentionMember?,
): List<MentionMember> {
    val byName = linkedMapOf<String, MentionMember>()
    peer?.let { byName[it.name] = it }
    for (row in messages) {
        if (row.fromMe || senderJson(row) == null) continue
        val (name, photoUrl, hueKey) = senderDisplay(row, myAlias, myPhotoUrl, isTribe)
        if (name == "Someone" || name == myAlias) continue
        val existing = byName[name]
        if (existing == null || (existing.photoUrl == null && photoUrl != null)) byName[name] = MentionMember(name, photoUrl, hueKey)
    }
    return byName.values.toList()
}

/// The thread a reply to `row` belongs in — PeelCore's own `threadRoot`
/// (`threadUuid ?? replyUuid ?? uuid`): a message already in a thread
/// keeps it going; one that's itself a plain reply starts a thread at
/// what it replies to; anything else starts a thread at itself. This is
/// why *every* reply ends up collapsed behind "N replies" once there's
/// a second one — not a separate "make this a thread" action, the same
/// way PeelCore's own `links(replyTo:inThread:in:)` works it out.
private fun threadRoot(row: MessageRow): String = row.threadUuid ?: row.replyUuid ?: row.uuid ?: ""

/// One of up to three people shown as a small overlapping avatar on a
/// thread's "N replies" row — PeelCore's own `ThreadSummary`'s
/// participant avatars.
private data class ThreadParticipant(val name: String, val photoUrl: String?, val hueKey: String)

private data class ThreadLastReply(val senderName: String, val hueKey: String, val text: String)

private data class ThreadSummaryData(
    val replyCount: Int,
    val lastTimestamp: Long?,
    val participants: List<ThreadParticipant>,
    val lastReply: ThreadLastReply? = null,
)

/// Every REAL thread among `messages` (2+ messages), by its root's uuid
/// — PeelCore's own `ThreadSummary`, trimmed: no unread count (Phase
/// 6's own read-state doesn't distinguish a thread's messages from the
/// chat's). A single reply isn't a thread yet — PeelCore's own
/// `threadsSQL` only builds a summary from `HAVING COUNT(*) >= 2`; one
/// reply alone still renders as an ordinary reply, not a collapsed "1
/// reply" row. Call only for a tribe: a DM's timeline never collapses
/// at all (PeelCore's `timeline(in:)` builds this dictionary `if
/// isTribe` only), so callers pass an empty map for a DM instead of
/// calling this — which is also why `senderDisplay` is always called
/// with `isTribe = true` here, never `false`.
private fun threadSummaries(messages: List<MessageRow>, myAlias: String, myPhotoUrl: String?): Map<String, ThreadSummaryData> {
    val byRoot = mutableMapOf<String, MutableList<MessageRow>>()
    for (row in messages) {
        val root = row.threadUuid ?: continue
        if (!isThreadContent(row)) continue
        byRoot.getOrPut(root) { mutableListOf() }.add(row)
    }
    return byRoot.filterValues { it.size >= 2 }.mapValues { (_, replies) ->
        // Newest repliers first, each person once — PeelCore's own
        // avatar row shows who's *recently* replied, not whoever
        // replied first.
        val participants = mutableListOf<ThreadParticipant>()
        val seen = mutableSetOf<String>()
        for (row in replies.sortedByDescending { it.timestamp ?: 0L }) {
            val (name, photoUrl, hueKey) = senderDisplay(row, myAlias, myPhotoUrl, isTribe = true)
            if (seen.add(if (row.fromMe) "me" else hueKey)) {
                participants.add(ThreadParticipant(name, photoUrl, hueKey))
                if (participants.size == 3) break
            }
        }
        val last = replies.maxByOrNull { it.timestamp ?: 0L }
        val lastReply = last?.let {
            val (name, _, hueKey) = senderDisplay(it, myAlias, myPhotoUrl, isTribe = true)
            val text = MessageMarkup.plainText(previewContent(it)).ifBlank { if (it.type == ATTACHMENT_MESSAGE_TYPE) "Attachment" else "" }
            ThreadLastReply(name, hueKey, text)
        }
        ThreadSummaryData(
            lastReply = lastReply,
            replyCount = replies.size,
            lastTimestamp = replies.mapNotNull { it.timestamp }.maxOrNull(),
            participants = participants,
        )
    }
}

/// Where a row sits once threads are collapsed — its own timestamp,
/// unless it's a thread's root and that thread's latest reply is newer,
/// in which case the root is sorted as if it happened then. Mirrors
/// PeelCore's `ChatRow.rows`, which overwrites the collapsed row's
/// `date` with the thread's newest surviving message's date rather than
/// the root's own.
private fun rowSortKey(row: MessageRow, threads: Map<String, ThreadSummaryData>): Long {
    val own = row.timestamp ?: 0L
    val latestReply = row.uuid?.let { threads[it]?.lastTimestamp } ?: return own
    return maxOf(own, latestReply)
}

@Composable
private fun MessageRowView(
    row: MessageRow,
    startsRun: Boolean,
    endsRun: Boolean,
    isAgent: Boolean,
    isTribe: Boolean,
    myAlias: String,
    myPhotoUrl: String?,
    replyUuid: String?,
    parent: MessageRow?,
    engine: Engine,
    audioPlayer: AudioPlayer,
    boosts: BoostSummary?,
    threadSummary: ThreadSummaryData?,
    onOpenThread: () -> Unit,
    onReply: () -> Unit,
    onBoost: (() -> Unit)? = null,
    onJoinCall: (CallLink) -> Unit = {},
    onDelete: (() -> Unit)? = null,
    onMoneyError: (String) -> Unit = {},
    deleteReplyCount: Int = threadSummary?.replyCount ?: 0,
) {
    val (name, photoUrl, hueKey) = senderDisplay(row, myAlias, myPhotoUrl, isTribe)
    val content = previewContent(row)
    // A call message (type 32), or - the Mac app's own convention, no
    // `call::` wrapper there - plain text that is only a call link.
    // `content` is already `previewContent`'s own unwrapped "content"
    // field, exactly what `CallLink.inMessage` expects.
    val callLink = remember(content, row.type) {
        engine.callServer()?.let { CallLink.inMessage(content, isCallType = row.type == CALL_MESSAGE_TYPE, server = it) }
    }
    // The device's own 12/24-hour setting, not a hardcoded format -
    // PeelCore's `Formatting.time` honors `Date.FormatStyle`'s locale
    // the same way; `SimpleDateFormat("HH:mm", ...)` ignored it outright.
    val timeContext = androidx.compose.ui.platform.LocalContext.current
    val time = row.timestamp?.let { android.text.format.DateFormat.getTimeFormat(timeContext).format(it) }
    var menuOpen by remember { mutableStateOf(false) }
    val clipboardContext = androidx.compose.ui.platform.LocalContext.current

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (row.fromMe) Theme.colors.accent.copy(alpha = 0.07f) else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(onClick = {}, onLongClick = { if (row.uuid != null && !row.deleted) menuOpen = true })
                .padding(top = if (startsRun) 8.dp else 1.dp, bottom = if (endsRun) 2.dp else 7.dp),
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
                        if (row.fromMe && row.status == "DELIVERED") {
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                Icons.Filled.Bolt,
                                contentDescription = "Delivered",
                                tint = Theme.colors.confirmed,
                                modifier = Modifier.size(10.dp),
                            )
                        }
                    }
                }
            }
            if (replyUuid != null) {
                Spacer(Modifier.height(2.dp))
                QuoteBox(parent = parent, myAlias = myAlias)
                Spacer(Modifier.height(2.dp))
            }
            if (row.deleted) {
                Text(
                    "This message has been deleted",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    color = Theme.colors.secondaryText,
                )
            } else if (row.type == ATTACHMENT_MESSAGE_TYPE) {
                AttachmentView(row, engine, audioPlayer)
                if (content.isNotEmpty()) Spacer(Modifier.height(4.dp))
            }
            if (!row.deleted && row.type == DIRECT_PAYMENT_MESSAGE_TYPE) {
                PaymentMessage(row, name)
            } else if (!row.deleted && row.type == INVOICE_MESSAGE_TYPE) {
                RequestMessage(row, name, engine, engine.storage, onError = onMoneyError)
            } else if (!row.deleted && callLink != null) {
                CallBlock(callLink, engine, onJoinCall)
            } else if (!row.deleted && (content.isNotEmpty() || row.type != ATTACHMENT_MESSAGE_TYPE)) {
                // Markdown as the iOS app reads it; an @mention of this
                // account (or @all) lights up. None in the agent chat.
                val mentionAliases = remember(myAlias, isAgent) {
                    if (isAgent) emptySet() else com.sphinx.peel.engine.Mentions.ownAliases(myAlias)
                }
                com.sphinx.peel.ui.components.MessageText(
                    content,
                    myAliases = mentionAliases,
                    modifier = Modifier.alpha(if (row.status == "PENDING") 0.6f else 1f),
                )
            }
            if (row.status == "FAILED") {
                Text("Not delivered", style = MaterialTheme.typography.labelSmall, color = Theme.colors.destructive)
            }
            if (boosts != null && boosts.count > 0) {
                Spacer(Modifier.height(2.dp))
                BoostChip(boosts)
            }
            if (threadSummary != null) {
                Spacer(Modifier.height(2.dp))
                ThreadSummaryRow(threadSummary, onClick = onOpenThread)
            }
        }
        }
        // Long-press: Reply, Boost (someone else's content message this
        // account hasn't boosted yet), Copy, Copy link, Delete (your own
        // deletable content) — PeelCore's own `MessageMenu`. Opens only
        // for a content row with a uuid at all (the `combinedClickable`
        // above), so nothing here needs to re-check that.
        val linkToCopy = callLink?.url ?: MessageMarkup.firstLink(content)
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Reply") }, onClick = { menuOpen = false; onReply() })
            if (onBoost != null) {
                // Just "Boost", not "Boost N sats" — the user's own
                // choice, unlike PeelCore's own menu
                // (`model.quickBoostLabel`), which does name the amount.
                DropdownMenuItem(text = { Text("Boost") }, onClick = { menuOpen = false; onBoost() })
            }
            if (content.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = { menuOpen = false; com.sphinx.peel.ClipboardUtil.copySensitive(clipboardContext, "message", content) },
                )
            }
            if (linkToCopy != null) {
                DropdownMenuItem(
                    text = { Text("Copy link") },
                    onClick = { menuOpen = false; com.sphinx.peel.ClipboardUtil.copySensitive(clipboardContext, "link", linkToCopy) },
                )
            }
            if (onDelete != null && canDelete(row, deleteReplyCount)) {
                DropdownMenuItem(
                    text = { Text("Delete", color = Theme.colors.destructive) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/// A call message: the call card's words, and Join through CallScreen —
/// PeelCore's own `CallBlock`, trimmed: no "Open" in place of "Join" (this
/// screen is fully replaced by CallScreen while you're on a call, so its
/// own call card is never visible to ask that of in the first place — see
/// `CallScreen`'s own doc comment on why there's no persistent background
/// call yet to need that distinction for). The live participant count is
/// new here, not something PeelCore's own card shows inline — a
/// reasonable use for presence given there's no sidebar indicator or call
/// banner to spend it on yet.
@Composable
private fun CallBlock(link: CallLink, engine: Engine, onJoinCall: (CallLink) -> Unit) {
    var peopleHere by remember(link.room) { mutableStateOf<Int?>(null) }
    LaunchedEffect(link.room) {
        engine.callPresence(link).collect { presence ->
            peopleHere = (presence as? CallPresence.Live)?.people?.size
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Theme.colors.fieldFill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (link.audioOnly) Icons.Filled.Call else Icons.Filled.Videocam,
            contentDescription = null, tint = Theme.colors.secondaryText,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (link.audioOnly) "Audio call" else "Video call", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
            val count = peopleHere
            if (count != null && count > 0) {
                Text(
                    if (count == 1) "1 person here now" else "$count people here now",
                    style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText,
                )
            }
        }
        TextButton(onClick = { onJoinCall(link) }) { Text("Join") }
    }
}

/// "N replies" in the accent colour, with up to three small overlapping
/// avatars of who's recently replied — PeelCore's own
/// `ThreadSummaryButton`.
@Composable
private fun ThreadSummaryRow(summary: ThreadSummaryData, onClick: () -> Unit) {
    val timeContext = androidx.compose.ui.platform.LocalContext.current
    val time = summary.lastTimestamp?.let { android.text.format.DateFormat.getTimeFormat(timeContext).format(it) }
    Column(Modifier.clickable(onClick = onClick).padding(vertical = 2.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (summary.participants.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                summary.participants.forEach { participant ->
                    Avatar(
                        name = participant.name, url = participant.photoUrl, size = 16.dp,
                        hue = PersonHue.of(participant.hueKey), shape = AvatarShape.CIRCLE,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (summary.replyCount == 1) "1 reply" else "${summary.replyCount} replies",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Theme.colors.accent,
        )
        if (time != null) {
            Spacer(Modifier.width(6.dp))
            Text(time, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
        }
    }
    summary.lastReply?.takeIf { it.text.isNotEmpty() }?.let { last ->
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = PersonHue.of(last.hueKey).fill, fontWeight = FontWeight.Bold))
                append("${last.senderName}: ")
                pop()
                append(last.text)
            },
            style = MaterialTheme.typography.labelSmall,
            color = Theme.colors.secondaryText,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
    }
}

/// Two messages on the same device-local calendar day, by `Calendar`
/// (not a raw 24h-bucket division) — PeelCore's own `startsDay` check,
/// `Calendar.current.isDate(_:inSameDayAs:)`.
private fun isSameDay(a: Long?, b: Long?): Boolean {
    if (a == null || b == null) return false
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/// "Today", "Yesterday", else a weekday and date — PeelCore's own
/// `Formatting.dayHeading`.
private fun dayHeading(timestamp: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    if (isSameDay(now.timeInMillis, timestamp)) return "Today"
    now.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (isSameDay(now.timeInMillis, timestamp)) return "Yesterday"
    return SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(then.time)
}

@Composable
private fun DayDivider(timestamp: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme.colors.divider.copy(alpha = 0.25f))
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), thickness = 0.5.dp, color = Theme.colors.divider)
        Text(
            dayHeading(timestamp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Theme.colors.secondaryText,
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .border(0.5.dp, Theme.colors.divider, RoundedCornerShape(50))
                .background(Theme.colors.background, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        HorizontalDivider(Modifier.weight(1f), thickness = 0.5.dp, color = Theme.colors.divider)
    }
}

/// The one-time boundary where this chat's unread messages began, the
/// moment this screen opened — PeelCore's own `NewMessagesLine`.
@Composable
private fun NewMessagesDivider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Theme.colors.accent)
        Text(
            stringResource(R.string.new_messages),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Theme.colors.accent,
            modifier = Modifier.padding(start = 10.dp),
        )
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
        var zoomed by remember(attachment.file) { mutableStateOf(false) }
        if (zoomed) ZoomableImageDialog(attachment.file) { zoomed = false }
        val size by produceState<Pair<Int, Int>?>(null, attachment.file) { value = readImageSize(attachment.file) }
        AsyncImage(
            model = attachment.file,
            contentDescription = attachment.fileName ?: "Image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .mediaBubble(size?.first ?: 0, size?.let { it.first.toFloat() / it.second } ?: (4f / 3f))
                .clip(RoundedCornerShape(10.dp))
                .background(Theme.colors.fieldFill)
                .clickable { zoomed = true },
        )
        return
    }
    if (attachment.kind == AttachmentKind.VIDEO) {
        VideoAttachment(attachment.file)
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
    val fileDuration by produceState(0, attachment.file) { value = readMediaDurationMs(attachment.file) }
    val duration = if (isThis && audioPlayer.durationMs > 0) audioPlayer.durationMs else fileDuration
    // Dragging sets this so the thumb follows the finger instead of
    // snapping back to the player's own (slower-to-update) position
    // until the drag lands.
    var dragPosition by remember(attachment.file) { mutableStateOf<Float?>(null) }
    val shown = dragPosition?.let { (it * duration).toInt() } ?: position

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Theme.colors.fieldFill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Theme.colors.accent)
                .clickable { scope.launch { audioPlayer.toggle(attachment.file) } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Slider(
                value = dragPosition ?: (if (duration > 0) position.toFloat() / duration else 0f),
                onValueChange = { dragPosition = it },
                onValueChangeFinished = {
                    val target = dragPosition
                    if (target != null && duration > 0) {
                        if (!isThis) scope.launch { audioPlayer.toggle(attachment.file); audioPlayer.seekTo((target * duration).toInt()) }
                        else audioPlayer.seekTo((target * duration).toInt())
                    }
                    dragPosition = null
                },
                modifier = Modifier.fillMaxWidth().height(24.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Theme.colors.accent,
                    activeTrackColor = Theme.colors.accent,
                    inactiveTrackColor = Theme.colors.inputBorder,
                ),
            )
            Text(
                "${formatDuration(shown)} / ${formatDuration(duration)}",
                style = MaterialTheme.typography.labelSmall,
                color = Theme.colors.secondaryText,
            )
        }
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
            if (parent.deleted) "Message deleted" else MessageMarkup.plainText(previewContent(parent)),
            style = MaterialTheme.typography.labelSmall,
            color = Theme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/// A bolt and a sats total, on `fieldFill` — PeelCore's own `BoostChipView`,
/// lit (the accent colour) when this device's own history has a boost on
/// it, plain otherwise. No pending/failed states: this port never sends
/// a new boost yet, so everything shown here already reached its final
/// state before this device ever saw it.
@Composable
private fun BoostChip(boosts: BoostSummary) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Theme.colors.fieldFill)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Bolt, contentDescription = null, tint = Theme.colors.attention, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            "${formattedSats(boosts.totalMsat)} sats",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (boosts.boostedByMe) Theme.colors.accent else Theme.colors.text,
        )
    }
}

/// "1,200" — msat to whole sats, thousands-grouped, as PeelCore's own
/// `Sats.number` shows a boost's total.
private fun formattedSats(msat: Long): String =
    "%,d".format(Locale.US, msat / 1000)

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

/// The card of `@mention` matches floating above the composer's capsule —
/// rounded, scrollable, capped at about four and a half rows.
@Composable
private fun MentionPopup(suggestions: List<MentionMember>, onPick: (MentionMember) -> Unit) {
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    val above = remember(gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ) = IntOffset(anchorBounds.left, (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0))
        }
    }
    Popup(popupPositionProvider = above) {
        LazyColumn(
            Modifier
                .width(280.dp)
                .heightIn(max = 216.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Theme.colors.fieldFill),
        ) {
            items(suggestions, key = { it.name }) { member ->
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clickable { onPick(member) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name = member.name, url = member.photoUrl, size = 28.dp, hue = PersonHue.of(member.hueKey), shape = AvatarShape.CIRCLE)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        member.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Theme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        mentionHandle(member.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = Theme.colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/// The capsule text field, attach menu, mic and send button —
/// PeelCore's `PhoneComposer`, trimmed: no sats bolt.
/// Typing `@` shows `members` matching what's typed in a card above the
/// capsule; tapping one swaps the `@query` for `@Handle ` (PeelCore's
/// `MentionQuery`).
/// `replyTarget` (name, preview) shows PeelCore's `ReplyingTo` above the
/// capsule, with its own dismiss; long-pressing a row sets it. Shared by
/// the main screen and `ThreadScreen` alike, each supplying its own
/// send/record/attach callbacks addressed wherever that screen's own
/// reply belongs.
@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    members: List<MentionMember> = emptyList(),
    replyTarget: Pair<String, String>?,
    onCancelReply: () -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    recorder: VoiceRecorder,
    onPressRecord: () -> Unit,
    onReleaseRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    sendingLabel: String?,
    onSend: () -> Unit,
    /// Sats to a person (a DM, not a tribe or the agent): the attach menu's
    /// "Send sats" and "Request sats". Null hides them.
    onSendSats: (() -> Unit)? = null,
    onRequestSats: (() -> Unit)? = null,
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
        if (sendingLabel != null) {
            Text(
                sendingLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Theme.colors.secondaryText,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var attachMenuOpen by remember { mutableStateOf(false) }
            // The field's selection lives here (the cursor decides which
            // `@word` is being typed); `text` stays the source of truth, so
            // an outside reset (cleared after send) replaces this too.
            var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
            if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
            Box {
                IconButton(onClick = { attachMenuOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach", tint = Theme.colors.secondaryText)
                }
                DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("Photo") }, onClick = { attachMenuOpen = false; onPickPhoto() })
                    DropdownMenuItem(text = { Text("Camera") }, onClick = { attachMenuOpen = false; onTakePhoto() })
                    // Any file, including a video — PeelCore's own iPhone
                    // app has no dedicated video picker either; a video
                    // reaches the send path through this same option there.
                    DropdownMenuItem(text = { Text("File") }, onClick = { attachMenuOpen = false; onPickFile() })
                    if (onSendSats != null) DropdownMenuItem(text = { Text("Send sats") }, onClick = { attachMenuOpen = false; onSendSats() })
                    if (onRequestSats != null) DropdownMenuItem(text = { Text("Request sats") }, onClick = { attachMenuOpen = false; onRequestSats() })
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Theme.colors.fieldFill)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (recorder.isRecording) {
                    // A red dot, the clock and the way to cancel —
                    // PeelCore's own `VoiceRecordingIndicator`.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Theme.colors.destructive))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatDuration(recorder.elapsedMs.toInt()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Theme.colors.text,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (recorder.isCancelling) "Release to cancel" else "‹ Slide to cancel",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (recorder.isCancelling) Theme.colors.destructive else Theme.colors.secondaryText,
                        )
                    }
                } else {
                    if (text.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.secondaryText)
                    }
                    val word = if (field.selection.collapsed) mentionWord(field.text, field.selection.end) else null
                    val suggestions = if (word != null) mentionSuggestions(members, word.query) else emptyList()
                    if (word != null && suggestions.isNotEmpty()) {
                        MentionPopup(suggestions) { member ->
                            val (newText, cursor) = insertMention(field.text, word, member)
                            field = TextFieldValue(newText, TextRange(cursor))
                            onTextChange(newText)
                        }
                    }
                    BasicTextField(
                        value = field,
                        onValueChange = {
                            field = it
                            if (it.text != text) onTextChange(it.text)
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Theme.colors.text),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Theme.colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val canSend = text.isNotBlank()
            if (canSend) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Theme.colors.button)
                        .clickable(onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Theme.colors.buttonText)
                }
            } else {
                // Press and hold to record, release to send, slide left
                // or up to cancel — PeelCore's own `HoldToRecordButton`/
                // `cancelDistance`. A custom pointer loop, not
                // `detectTapGestures`: that gives a press and a release,
                // nothing about where the finger moved in between, which
                // the slide-to-cancel distance needs live.
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (recorder.isRecording) Theme.colors.destructive else Theme.colors.fieldFill)
                        .pointerInput(Unit) {
                            val cancelDistancePx = VOICE_CANCEL_DISTANCE_DP.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                onPressRecord()
                                recorder.updateCancelling(false)
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed) break
                                        val drag = change.position - down.position
                                        recorder.updateCancelling(-drag.x > cancelDistancePx || -drag.y > cancelDistancePx)
                                        change.consume()
                                    }
                                } finally {
                                    if (recorder.isCancelling) onCancelRecord() else onReleaseRecord()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Record a voice message",
                        tint = if (recorder.isRecording) Theme.colors.buttonText else Theme.colors.secondaryText,
                    )
                }
            }
        }
    }
}
