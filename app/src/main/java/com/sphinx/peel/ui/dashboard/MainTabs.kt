package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.ui.call.CallConnectionState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.sphinx.peel.engine.HiveClient
import com.sphinx.peel.engine.HiveException
import com.sphinx.peel.engine.Stadeum
import com.sphinx.peel.engine.StadeumModel
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.CallSwitch
import com.sphinx.peel.ui.call.PendingCall
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.sphinx.peel.call.CallService
import androidx.compose.runtime.LaunchedEffect
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.CallLink
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.call.CallBanner
import com.sphinx.peel.ui.call.CallPhase
import com.sphinx.peel.ui.call.CallScreen
import com.sphinx.peel.ui.call.CallSession
import com.sphinx.peel.ui.call.camera
import com.sphinx.peel.ui.call.chat
import com.sphinx.peel.ui.call.link
import com.sphinx.peel.ui.call.title
import com.sphinx.peel.ui.apps.AppsScreen
import com.sphinx.peel.ui.mentions.MentionsScreen
import com.sphinx.peel.ui.threads.ThreadsScreen
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.payments.PaymentsScreen
import com.sphinx.peel.ui.you.YouScreen
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/// What a tapped notification asks `MainTabs` to open: a chat, or Payments.
/// Read from the intent extras `push/Notifications.openIntent` sets.
data class DeepLink(val chat: String?, val payments: Boolean) {
    companion object {
        fun from(intent: Intent?): DeepLink? {
            val chat = intent?.getStringExtra(com.sphinx.peel.push.Notifications.EXTRA_CHAT)?.takeIf { it.isNotEmpty() }
            val payments = intent?.getBooleanExtra(com.sphinx.peel.push.Notifications.EXTRA_OPEN_PAYMENTS, false) == true
            return if (chat != null || payments) DeepLink(chat, payments) else null
        }
    }
}

private enum class MainTab { CHATS, PAYMENTS, YOU, SEARCH }

/// The tab bar — PeelCore's `MainTabs.swift`: Chats, Payments, You, Search.
/// Chats, You and Search are real; Payments is still a placeholder.
///
/// Also where the call this account is on lives — `PeelUI`'s own
/// `CallStore`, trimmed to what this port needs (no watching calls across
/// chats, no Hive/Jamie picks): a call is started or joined from inside a
/// conversation (`onJoinCall`, threaded down to `ConversationScreen`), but
/// the `CallSession` itself is held here, above both the open chat and the
/// tab content, so it keeps running when either changes underneath it —
/// `docs/phases/15-call-background.md`.
@Composable
fun MainTabs(
    engine: Engine, storage: Storage, secretStore: SecretStore,
    deepLink: DeepLink? = null, onDeepLinkHandled: () -> Unit = {}, onForgotten: () -> Unit,
) {
    var tab by remember { mutableStateOf(MainTab.CHATS) }
    // An open chat replaces the whole tab area, bar included — PeelCore's
    // `ConversationScreen` hides the tab bar the same way; Back returns to
    // whichever tab was open, not necessarily Chats.
    var openChat by remember { mutableStateOf<String?>(null) }
    // The message a Mentions row opened `openChat` at, and its thread root
    // if it is in one; cleared when the chat is left.
    var focusMessage by remember { mutableStateOf<String?>(null) }
    var focusThread by remember { mutableStateOf<String?>(null) }
    // The Apps/Threads/Mentions placeholder pages, opened from the rows at
    // the top of Chats; replaces the tab area the same way an open chat does.
    var openRoute by remember { mutableStateOf<DashboardRoute?>(null) }
    // Set when a chat is opened from the Threads list, straight into that
    // thread; `openRoute` stays THREADS underneath, so Back from the chat
    // returns to the list.
    var openThreadRoot by remember { mutableStateOf<String?>(null) }

    // A tapped notification: open its chat (or Payments), once.
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        if (link.chat != null) openChat = link.chat else if (link.payments) { openChat = null; tab = MainTab.PAYMENTS }
        onDeepLinkHandled()
    }

    var callPhase by remember { mutableStateOf<CallPhase?>(null) }
    // The full call screen is in front, rather than just the banner (or
    // nothing, off a call entirely). Independent of `callPhase` itself:
    // hiding the screen (the chevron) never ends the call, only Leave does.
    var callScreenShown by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    // A system permission dialog needs an ActivityResultLauncher, which
    // only exists here — CallSession only ever calls these two closures,
    // whether it's the automatic "microphone on after connecting" or an
    // explicit tap on the control bar or the banner's own mute button.
    var micContinuation by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var cameraContinuation by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micContinuation?.invoke(granted)
        micContinuation = null
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraContinuation?.invoke(granted)
        cameraContinuation = null
    }
    suspend fun askMic(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        return suspendCancellableCoroutine { cont ->
            micContinuation = { granted -> cont.resume(granted) }
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    suspend fun askCamera(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
        return suspendCancellableCoroutine { cont ->
            cameraContinuation = { granted -> cont.resume(granted) }
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Joining or starting a call while on a different one asks first, as
    // PeelCore's `CallStore` does ("Leave the call in #design and join?"):
    // nothing happens until the person says yes. The same call just comes
    // back to the front.
    var pendingCall by remember { mutableStateOf<PendingCall?>(null) }

    fun connectNow(chat: String, title: String, link: CallLink, camera: Boolean) {
        (callPhase as? CallPhase.Active)?.session?.leave()
        callPhase = CallPhase.Connecting(chat, title, link, camera)
        callScreenShown = true
        val requested = callPhase as CallPhase.Connecting
        scope.launch {
            try {
                val ticket = engine.callTicket(link)
                // Stale by the time the ticket came back (left, or moved
                // on to a different call before this one connected).
                if (callPhase !== requested) return@launch
                val session = CallSession(appContext, ticket, link, chat, title, camera, askMicPermission = ::askMic, askCameraPermission = ::askCamera)
                callPhase = CallPhase.Active(session)
            } catch (e: Exception) {
                if (callPhase !== requested) return@launch
                callPhase = CallPhase.Failed(chat, title, link, camera, e.message ?: "Could not join the call.")
            }
        }
    }

    fun request(chat: String, title: String, link: CallLink, camera: Boolean) {
        val current = callPhase
        when (CallSwitch.action(current?.link?.room, link.room)) {
            CallSwitch.Action.JOIN -> connectNow(chat, title, link, camera)
            CallSwitch.Action.SHOW_CURRENT -> callScreenShown = true
            CallSwitch.Action.CONFIRM -> pendingCall = PendingCall(chat, title, link, camera)
        }
    }

    fun retryCall() {
        val phase = callPhase ?: return
        connectNow(phase.chat, phase.title, phase.link, phase.camera)
    }

    // A call with Jamie, started by Stadeum (Hive's strut-device call
    // endpoint) in the workspace picked on Apps, then joined like any call.
    val toast = LocalToast.current
    fun talkToJamie(home: TalkHome) {
        if (home.id == TalkHome.DEVELOPMENT) {
            toast.show("Talking to Jamie on a development workspace is not available on Android.", ToastType.Info)
            return
        }
        val paired = Stadeum.model.link
        val server = engine.callServer()
        if (paired == null || Stadeum.model.status != StadeumModel.Status.Connected || server == null) {
            toast.show("Connect Stadeum in You to talk to Jamie.", ToastType.Warning)
            return
        }
        scope.launch {
            try {
                val url = HiveClient(paired.hive).startCall(paired, home.id)
                val link = CallLink.hive(url, server)
                    ?: throw HiveException("Stadeum started the call on a server this account's calls do not use.")
                request("", "Jamie · ${home.name}", link, false)
            } catch (e: HiveException) {
                toast.show(e.message ?: "Could not start the call.", ToastType.Error)
            } catch (e: Exception) {
                toast.show("Could not start the call.", ToastType.Error)
            }
        }
    }

    fun leaveCall() {
        (callPhase as? CallPhase.Active)?.session?.leave()
        callPhase = null
        callScreenShown = false
    }

    // A running call holds a foreground service, so Android keeps the
    // process (and its LiveKit room) while Peel is in the background; Leave
    // in its notification comes back here as a broadcast.
    val activeCall = (callPhase as? CallPhase.Active)?.session
    val activeTitle = activeCall?.title
    val activeChat = activeCall?.chat
    DisposableEffect(activeCall) {
        if (activeCall == null) return@DisposableEffect onDispose { }
        com.sphinx.peel.push.Notifications.cancelIncomingCall(appContext)
        CallService.start(appContext, activeTitle ?: "Call", activeChat ?: "")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { leaveCall() }
        }
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(CallService.ACTION_LEFT), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            appContext.unregisterReceiver(receiver)
            CallService.stop(appContext)
        }
    }

    // The microphone is asked for after the call connects; the service's
    // type needs that permission, so start it again once connected.
    val activeState = activeCall?.state
    LaunchedEffect(activeState) {
        if (activeCall != null && activeState == CallConnectionState.CONNECTED) {
            CallService.start(appContext, activeTitle ?: "Call", activeChat ?: "")
        }
    }

    // "Forget this account" (or any other path that tears MainTabs down
    // entirely) must not orphan a running Room — nothing else would ever
    // call `leave()` on it once this composable itself is gone.
    DisposableEffect(Unit) {
        onDispose { (callPhase as? CallPhase.Active)?.session?.leave() }
    }

    pendingCall?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingCall = null },
            title = { Text("Leave the current call?") },
            text = { Text("You're on a call in ${callPhase?.title ?: "another chat"}. Leave it and join the call in ${pending.title}?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingCall = null
                    leaveCall()
                    connectNow(pending.chat, pending.title, pending.link, pending.camera)
                }) { Text("Leave and join") }
            },
            dismissButton = { TextButton(onClick = { pendingCall = null }) { Text("Stay") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        val phase = callPhase
        if (callScreenShown && phase != null) {
            CallScreen(phase, onHide = { callScreenShown = false }, onLeave = ::leaveCall, onRetry = ::retryCall)
        } else {
            if (phase != null) CallBanner(phase, onTap = { callScreenShown = true }, onLeave = ::leaveCall)
            val chat = openChat
            val route = openRoute
            if (route != null && chat == null) {
                val back = { openRoute = null }
                when (route) {
                    DashboardRoute.APPS -> AppsScreen(onBack = back, onTalk = { home -> back(); talkToJamie(home) })
                    DashboardRoute.THREADS -> ThreadsScreen(engine, storage, onBack = back, onOpenThread = { threadChat, root ->
                        openThreadRoot = root
                        openChat = threadChat
                    })
                    DashboardRoute.ADD_CONTACT -> com.sphinx.peel.ui.contacts.AddContactScreen(
                        engine,
                        onBack = back,
                        onOpenChat = { contactChat -> openRoute = null; openChat = contactChat },
                    )
                    DashboardRoute.JOIN_TRIBE -> com.sphinx.peel.ui.tribes.JoinTribeScreen(
                        engine,
                        onBack = back,
                        onOpenChat = { tribeChat -> openRoute = null; openChat = tribeChat },
                    )
                    DashboardRoute.CREATE_TRIBE -> com.sphinx.peel.ui.tribes.TribeFormScreen(
                        engine,
                        chat = null,
                        onBack = back,
                        onDone = { tribeChat -> openRoute = null; openChat = tribeChat },
                    )
                    DashboardRoute.INVITES -> com.sphinx.peel.ui.invites.InvitesScreen(engine, storage, onBack = back)
                    DashboardRoute.MENTIONS -> MentionsScreen(
                        storage,
                        onBack = back,
                        onOpenMessage = { messageChat, uuid, threadUuid ->
                            focusMessage = uuid
                            focusThread = threadUuid
                            openChat = messageChat
                        },
                    )
                }
            } else if (chat != null) {
                ConversationScreen(
                    engine, storage, chat,
                    onBack = { openChat = null; openThreadRoot = null; focusMessage = null; focusThread = null },
                    initialThreadRoot = openThreadRoot,
                    onContactRemoved = {
                        // What this window kept for their chat: a call in it is left, its notifications go
                        // (the draft, reply and open thread are the chat screen's own and leave with it).
                        if (callPhase?.chat == chat) leaveCall()
                        com.sphinx.peel.push.Notifications.cancelChat(appContext, chat)
                        openChat = null; openThreadRoot = null; focusMessage = null; focusThread = null
                    },
                    focusMessageUuid = focusMessage, focusThreadUuid = focusThread,
                    onJoinCall = { joinChat, joinTitle, link, camera -> request(joinChat, joinTitle, link, camera) },
                )
            } else {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        MainTab.CHATS -> ChatsScreen(engine, storage, modifier = Modifier.fillMaxSize(), onOpenRoute = { openRoute = it }) { openChat = it }
                        MainTab.PAYMENTS -> PaymentsScreen(engine, storage, modifier = Modifier.fillMaxSize(), onOpenChat = { openChat = it })
                        MainTab.YOU -> YouScreen(engine, storage, secretStore, onForgotten)
                        MainTab.SEARCH -> SearchScreen(engine, storage, modifier = Modifier.fillMaxSize()) { openChat = it }
                    }
                }
                BottomBar(tab) { tab = it }
            }
        }
    }
}

/// Chats, Payments and You in one pill, Search its own circle beside it —
/// the shape the iPhone's native search-role tab gets for free.
@Composable
private fun BottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(28.dp)).background(Theme.colors.fieldFill),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TabIcon(Icons.AutoMirrored.Filled.Chat, "Chats", selected == MainTab.CHATS) { onSelect(MainTab.CHATS) }
            TabIcon(Icons.Filled.Bolt, "Payments", selected == MainTab.PAYMENTS) { onSelect(MainTab.PAYMENTS) }
            TabIcon(Icons.Filled.Person, "You", selected == MainTab.YOU) { onSelect(MainTab.YOU) }
        }
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Theme.colors.fieldFill)
                .clickable { onSelect(MainTab.SEARCH) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search",
                tint = if (selected == MainTab.SEARCH) Theme.colors.accent else Theme.colors.secondaryText,
            )
        }
    }
}

@Composable
private fun RowScope.TabIcon(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Theme.colors.accent else Theme.colors.secondaryText
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
