package com.sphinx.peel.ui.dashboard

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.CallSwitch
import com.sphinx.peel.ui.call.PendingCall
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import android.content.Intent
import com.sphinx.peel.call.CallController
import com.sphinx.peel.call.CallNotice
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.CallLink
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.call.CallBanner
import com.sphinx.peel.ui.call.CallPhase
import com.sphinx.peel.ui.call.CallScreen
import com.sphinx.peel.ui.call.camera
import com.sphinx.peel.ui.call.chat
import com.sphinx.peel.ui.call.link
import com.sphinx.peel.ui.call.title
import com.sphinx.peel.ui.apps.AppsScreen
import com.sphinx.peel.ui.talk.TalkScreen
import com.sphinx.peel.ui.mentions.MentionsScreen
import com.sphinx.peel.ui.threads.ThreadsScreen
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.payments.PaymentsScreen
import com.sphinx.peel.ui.you.YouScreen

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

    // The call is `CallController`'s (application-scoped, `docs/phases/15-call-background.md`):
    // this only observes it, so rotation or a recreated Activity never ends a call. Whether the
    // full call screen is in front, rather than just the banner, lives there too; hiding the
    // screen (the chevron) never ends the call, only Leave does.
    val callPhase = CallController.phase
    val callScreenShown = CallController.callScreenShown

    val context = LocalContext.current
    val appContext = context.applicationContext
    val toast = LocalToast.current

    // A system permission dialog needs an ActivityResultLauncher, which only exists here. The
    // controller asks (before connecting, and for a later camera tap); the answer goes back to
    // it, not into a closure held by a session.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        CallController.permissionResult(granted)
    }
    val permissionRequest = CallController.permissionRequest
    LaunchedEffect(permissionRequest) {
        if (permissionRequest != null) permissionLauncher.launch(permissionRequest)
    }

    // Notifications are off: the call still runs, said once.
    val nudge = CallController.notificationNudge
    LaunchedEffect(nudge) {
        if (!nudge) return@LaunchedEffect
        toast.show("Notifications are off, so the call's controls won't show on the lock screen. The call still runs.", ToastType.Warning)
        CallController.nudgeShown()
    }

    // Joining or starting a call while on a different one asks first, as
    // PeelCore's `CallStore` does ("Leave the call in #design and join?"):
    // nothing happens until the person says yes. The same call just comes
    // back to the front.
    var pendingCall by remember { mutableStateOf<PendingCall?>(null) }

    fun connectNow(chat: String, title: String, link: CallLink, camera: Boolean) =
        CallController.connect(engine, chat, title, link, camera)

    // Talk to Jamie (`docs/phases/26-talk-to-jamie.md`): the voice call with
    // an org's agent, beside the group call, never with it. Held by the controller too.
    val talk = CallController.talk

    fun request(chat: String, title: String, link: CallLink, camera: Boolean) {
        // One call at a time: a group call waits until Talk is hung up.
        if (talk.isOn) {
            toast.show("Hang up on Jamie first, then join the call.", ToastType.Warning)
            return
        }
        val current = callPhase
        when (CallSwitch.action(current?.link?.room, link.room)) {
            CallSwitch.Action.JOIN -> connectNow(chat, title, link, camera)
            CallSwitch.Action.SHOW_CURRENT -> CallController.callScreenShown = true
            CallSwitch.Action.CONFIRM -> pendingCall = PendingCall(chat, title, link, camera)
        }
    }

    fun retryCall() = CallController.retry(engine)

    // Talk to Jamie, picked on Apps: `POST /voice/calls` on the workspace's
    // own strut, then its LiveKit room (not a call link). Never while a group call is on.
    fun talkToJamie(home: TalkHome) {
        val other = callPhase
        if (other != null) {
            toast.show("Leave the call in ${other.title} first, then talk to Jamie.", ToastType.Warning)
            return
        }
        CallController.talkTo(home)
    }

    fun leaveCall() = CallController.leave()

    // "Forget this account" ends any call for good; the Activity going away does not.
    val forget = { CallController.endAll(); onForgotten() }

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

    val talkHome = talk.home
    if (talkHome != null) TalkScreen(talk, onClose = CallController::hangUpTalk) else Column(Modifier.fillMaxSize()) {
        CallController.notice?.let { CallNoticeBar(it, onDismiss = CallController::dismissNotice) }
        val phase = callPhase
        if (callScreenShown && phase != null) {
            CallScreen(phase, onHide = { CallController.callScreenShown = false }, onLeave = ::leaveCall, onRetry = ::retryCall)
        } else {
            if (phase != null) CallBanner(phase, onTap = { CallController.callScreenShown = true }, onLeave = ::leaveCall)
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
                        MainTab.YOU -> YouScreen(engine, storage, secretStore, forget)
                        MainTab.SEARCH -> SearchScreen(engine, storage, modifier = Modifier.fillMaxSize()) { openChat = it }
                    }
                }
                BottomBar(tab) { tab = it }
            }
        }
    }
}

/// A line about the call (it ended in the background, the service could not start), dismissable.
@Composable
private fun CallNoticeBar(notice: CallNotice, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (notice.error) Theme.colors.destructive else Theme.colors.fieldFill)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(notice.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (notice.error) Color.White else Theme.colors.text)
        Text("Dismiss", Modifier.clickable(onClick = onDismiss), style = MaterialTheme.typography.labelLarge, color = if (notice.error) Color.White else Theme.colors.accent)
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
