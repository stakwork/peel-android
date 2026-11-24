package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.ui.str
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.sphinx.peel.engine.TalkHome
import com.sphinx.peel.engine.CallDevices
import com.sphinx.peel.engine.CallSwitch
import com.sphinx.peel.ui.call.PendingCall
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import android.content.Intent
import com.sphinx.peel.call.CallController
import com.sphinx.peel.call.CallNotice
import androidx.compose.runtime.LaunchedEffect
import com.sphinx.peel.engine.Stadeum
import com.sphinx.peel.engine.Boxes
import com.sphinx.peel.engine.BoxWords
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Apps
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.collectAsState
import com.sphinx.peel.engine.PublicPage
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
/// Read from the intent extras `push/Notifications.openIntent` sets. [you]: a
/// box's `peel://strut/link` opened You, where its question and the boxes are
/// (docs/phases/45-box-link.md).
data class DeepLink(val chat: String?, val payments: Boolean, val you: Boolean = false, val tribe: String? = null) {
    companion object {
        /// You, its own page (iOS `tab = .you; you = []`).
        val YOU = DeepLink(chat = null, payments = false, you = true)

        /// A tribe link opened from outside (`peel://tribe?…`, `sphinx.chat://?action=tribeV2…`), already checked by [com.sphinx.peel.engine.TribeLinks].
        fun tribe(link: String) = DeepLink(chat = null, payments = false, tribe = link)

        fun from(intent: Intent?): DeepLink? {
            val chat = intent?.getStringExtra(com.sphinx.peel.push.Notifications.EXTRA_CHAT)?.takeIf { it.isNotEmpty() }
            val payments = intent?.getBooleanExtra(com.sphinx.peel.push.Notifications.EXTRA_OPEN_PAYMENTS, false) == true
            return if (chat != null || payments) DeepLink(chat, payments) else null
        }
    }
}

/// The tab bar — PeelCore's `MainTabs.swift`: Chats, Payments, Apps, You.
/// Android only: Apps in the bar, between Payments and You (docs/phases/48-tasks-tab.md); it replaced the Tasks tab.
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
    deepLink: DeepLink? = null, onDeepLinkHandled: () -> Unit = {}, pages: com.sphinx.peel.ui.page.PublicPageRouter = remember { com.sphinx.peel.ui.page.PublicPageRouter() },
    // The tab shown before Voice Mode last took over the screen, and where a tab change is reported back to remember
    // for next time (docs/phases/55-voice-mode.md): so its grid button returns to Chats, Payments or You/Profile, not
    // always Chats, when the screen is shown again (`MainActivity.PeelApp` holds the saved value across the swap).
    initialTab: MainTab? = null, onTabChanged: (MainTab) -> Unit = {},
    onForgotten: () -> Unit,
) {
    var tab by remember { mutableStateOf(initialTab ?: MainTab.CHATS) }
    LaunchedEffect(tab) { onTabChanged(tab) }
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
    // The invite whose detail is open (a pending one tapped in the chat list, or the one just made).
    var openInvite by remember { mutableStateOf<String?>(null) }

    // A tribe link, tapped in a message or opened from outside (`TribeRoute`): the tribe's chat if this account is in it, else the Join
    // screen with the link filled in. Nothing is joined or paid until the person taps Join there.
    var joinPrefill by remember { mutableStateOf<String?>(null) }
    val openTribe = { link: String ->
        val route = com.sphinx.peel.engine.TribeRoute.decide(link) { pubkey -> storage.contact(pubkey)?.let { it.isTribe && it.ready } == true }
        when (route) {
            is com.sphinx.peel.engine.TribeRoute.OpenChat -> { openRoute = null; openInvite = null; openThreadRoot = null; openChat = route.chat }
            is com.sphinx.peel.engine.TribeRoute.Join -> {
                openChat = null; openInvite = null; openThreadRoot = null; focusMessage = null; focusThread = null
                joinPrefill = route.link; tab = MainTab.CHATS; openRoute = DashboardRoute.JOIN_TRIBE
            }
            null -> {}
        }
        route != null
    }

    // A tapped notification: open its chat (or Payments), once.
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        if (link.tribe != null) openTribe(link.tribe)
        else if (link.chat != null) openChat = link.chat else if (link.payments) { openChat = null; tab = MainTab.PAYMENTS }
        else if (link.you) { openChat = null; openRoute = null; openInvite = null; openThreadRoot = null; tab = MainTab.YOU }
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
        toast.show(context.str(com.sphinx.peel.R.string.call_notifications_off), ToastType.Warning)
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
            toast.show(context.str(com.sphinx.peel.R.string.call_hang_up_jamie_first), ToastType.Warning)
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
            toast.show(context.str(com.sphinx.peel.R.string.call_leave_first, other.title), ToastType.Warning)
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
            title = { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.call_leave_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.call_leave_body, callPhase?.title ?: androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.call_another_chat), pending.title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingCall = null
                    leaveCall()
                    connectNow(pending.chat, pending.title, pending.link, pending.camera)
                }) { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.call_leave_and_join)) }
            },
            dismissButton = { TextButton(onClick = { pendingCall = null }) { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.call_stay)) } },
        )
    }

    // A box's link waiting for the person's yes (`Boxes.model.asking`, iOS `MainTabs`' alert): nothing is signed without it. Its
    // two buttons answer it; nothing else shuts it (no tap outside, no Back).
    val boxes = Boxes.model
    boxes.asking?.let { offer ->
        AlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(BoxWords.title(offer)) },
            text = { Text(BoxWords.question(offer)) },
            confirmButton = { TextButton(onClick = { boxes.accept() }) { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.box_link)) } },
            dismissButton = { TextButton(onClick = { boxes.decline() }) { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.common_cancel)) } },
        )
    }

    // The call being joined is on another device of this account too: asked before anything connects (iOS wording).
    if (CallController.choosingDevice != null) {
        AlertDialog(
            onDismissRequest = { CallController.answerDeviceChoice(CallController.DeviceChoice.CANCEL) },
            title = { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.call_device_question)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { CallController.answerDeviceChoice(CallController.DeviceChoice.SWITCH) }) { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.call_device_switch)) }
                    TextButton(onClick = { CallController.answerDeviceChoice(CallController.DeviceChoice.STAY) }) { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.call_device_stay)) }
                    TextButton(onClick = { CallController.answerDeviceChoice(CallController.DeviceChoice.CANCEL) }) { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.common_cancel)) }
                }
            },
        )
    }


    // An org's page over everything (iOS `router.orgPage`): a deliverable from the agent's DM, a workspace's app, a tapped Hive link or
    // a Talk card. Its workspace no longer in use (revoked, disconnected, renewed without it): the page and its token go.
    // The state is the router's (one holder of both pages, as iOS's `PhoneRouter`); a Talk card's page is the call screen's own (`talkOpener`).
    // A linked box's page (its own strut UI as the member, docs/phases/45-box-link.md) is held the same way and goes when its box does
    // (forgotten, or let go because the member removed the link there): iOS `closeGoneOrgPage`.
    val live = Stadeum.live
    val orgPage = pages.orgPage
    val shownPage = orgPage?.takeIf { it.isLive(live) || boxes.shows(it) }
    // An app shown inside Apps, every app's home (docs/phases/48-tasks-tab.md, "An app opens inside Apps"): any public page (a
    // worker's Tasks, from its row, an invite or a link), or an org page opened from its Apps row. Titled with the app's own name,
    // never its host. The tab moves to Apps, so leaving it lands on the list; an open chat stays over the tab and is back on leaving.
    val publicLink = pages.page
    val publicPage = remember(publicLink) { publicLink?.let { PublicPage(it) } }
    val appsPage: com.sphinx.peel.engine.PhonePage? = publicPage ?: shownPage?.takeIf { pages.orgInApps }
    val appsPageTitle = remember(appsPage) {
        when {
            publicLink != null -> storage.openedApps().firstOrNull { it.link.seller == publicLink.seller && it.link.app == publicLink.app }
                ?.title?.takeIf { it != publicLink.host }
            else -> shownPage?.title
        }
    }
    LaunchedEffect(appsPage) { if (appsPage != null) tab = MainTab.APPS }
    LaunchedEffect(live.map { it.id }, boxes.links.map { it.url }, orgPage) { pages.workspacesChanged(live, boxes::shows) }
    val talkOpener = remember { com.sphinx.peel.ui.talk.TalkOpener() }
    LaunchedEffect(live.map { it.id }) { talkOpener.workspacesChanged(live) }
    // The call screen gone: its sheet goes with it.
    LaunchedEffect(talk.home == null) { if (talk.home == null) talkOpener.close() }
    // A tapped Hive link to an app opens it when its workspace is paired here. Otherwise it says to connect that workspace first and
    // still goes to the browser, so Hive shows its own sign-in or not-found; any other link is the system's, as before.
    val linkOpener = remember(toast, context, storage) {
        val handler = com.sphinx.peel.engine.AppLinkHandler(
            tap = { Stadeum.openings.tap(it) },
            reopen = { Stadeum.openings.opening(it) },
            openPage = { pages.openOrg(it) },
            announce = { toast.show(it, com.sphinx.peel.ui.toast.ToastType.Warning) },
            system = { com.sphinx.peel.ui.apps.openInBrowser(context, it) },
            tribe = { openTribe(it) },
            // As an external `peel://open` link: not invited, so the page asks for sign-in with Peel's sheet.
            publicApp = { text -> com.sphinx.peel.engine.PublicAppLink.parse(text)?.let { pages.open(it, ready = true); true } ?: false },
        )
        com.sphinx.peel.ui.apps.LinkOpener { handler.open(it) }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.sphinx.peel.ui.apps.LocalLinkOpener provides linkOpener,
        com.sphinx.peel.ui.apps.LocalPageOpener provides { page: com.sphinx.peel.engine.OrgPageRequest -> pages.openOrg(page) },
        com.sphinx.peel.ui.apps.LocalTalkOpener provides (if (talk.home != null) talkOpener else null),
    ) {
    Box(Modifier.fillMaxSize()) {
    val talkHome = talk.home
    if (talkHome != null) TalkScreen(talk, onClose = CallController::hangUpTalk) else Column(Modifier.fillMaxSize()) {
        CallController.notice?.let { CallNoticeBar(it, onDismiss = CallController::dismissNotice) }
        val phase = callPhase
        val callInFront = callScreenShown && phase != null
        if (!callInFront && phase != null) CallBanner(phase, onTap = { CallController.callScreenShown = true }, onLeave = ::leaveCall)
        val chat = openChat
        val route = openRoute
        val inviteCode = openInvite
        // Nothing over the tabs: the bar shows, with the tab in front.
        val onTabs = !callInFront && chat == null && route == null && inviteCode == null && appsPage == null
        // One area for the call screen, an open chat or page, and the tab in front.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (callInFront && phase != null) {
                CallScreen(phase, onHide = { CallController.callScreenShown = false }, onLeave = ::leaveCall, onRetry = ::retryCall)
            } else if (appsPage != null) {
                com.sphinx.peel.ui.apps.OrgPageScreen(
                    appsPage, engine.walletHost, onClose = { if (publicLink != null) pages.close() else pages.closeOrg() },
                    locations = storage, inApps = true, title = appsPageTitle,
                )
            } else if (inviteCode != null && chat == null) {
                com.sphinx.peel.ui.invites.InviteDetailScreen(engine, storage, inviteCode, onClose = { openInvite = null })
            } else if (route != null && chat == null) {
                val back = { openRoute = null }
                when (route) {
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
                        onBack = { joinPrefill = null; back() },
                        initialLink = joinPrefill,
                        onOpenChat = { tribeChat -> openRoute = null; openChat = tribeChat },
                    )
                    DashboardRoute.CREATE_TRIBE -> com.sphinx.peel.ui.tribes.TribeFormScreen(
                        engine,
                        chat = null,
                        onBack = back,
                        onDone = { tribeChat -> openRoute = null; openChat = tribeChat },
                    )
                    DashboardRoute.INVITE -> com.sphinx.peel.ui.invites.NewInviteScreen(
                        engine,
                        onBack = back,
                        onMade = { made -> openRoute = null; openInvite = made.code },
                    )
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
                when (tab) {
                    // A workspace's app opened from its row stays inside Apps (iOS `openInApps`).
                    MainTab.APPS -> androidx.compose.runtime.CompositionLocalProvider(
                        com.sphinx.peel.ui.apps.LocalPageOpener provides { page: com.sphinx.peel.engine.OrgPageRequest -> pages.openOrg(page, inApps = true) },
                    ) {
                        AppsScreen(storage, onBack = null, onTalk = { home -> talkToJamie(home) }, onOpenPublic = { pages.open(it, ready = true) }, modifier = Modifier.fillMaxSize())
                    }
                    MainTab.CHATS -> ChatsScreen(engine, storage, modifier = Modifier.fillMaxSize(), onOpenRoute = { openRoute = it }, onOpenInvite = { openInvite = it }) { openChat = it }
                    MainTab.PAYMENTS -> PaymentsScreen(engine, storage, modifier = Modifier.fillMaxSize(), onOpenChat = { openChat = it })
                    MainTab.YOU -> YouScreen(engine, storage, secretStore, forget)
                }
            }
        }
        if (onTabs) BottomBar(tab) { tab = it }
    }
    // Over the call screen too: the call goes on behind it (the session is the store's, not the screen's).
    shownPage?.takeIf { !pages.orgInApps }?.let { com.sphinx.peel.ui.apps.OrgPageScreen(it, engine.walletHost, onClose = pages::closeOrg, locations = storage) }
    // A Talk card's page: a sheet over the call screen (iOS `.sheet(item: $opener.opened)`); the call goes on behind it.
    talkOpener.page?.takeIf { talk.home != null && it.isLive(live) }?.let {
        com.sphinx.peel.ui.apps.OrgPageScreen(it, engine.walletHost, onClose = talkOpener::close, locations = storage)
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
        Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.common_dismiss), Modifier.clickable(onClick = onDismiss), style = MaterialTheme.typography.labelLarge, color = if (notice.error) Color.White else Theme.colors.accent)
    }
}

/// Chats, Payments, Apps and You in one pill. Search is a button at the top left of Chats, not a tab (iOS `ChatsTab`).
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
            MainTab.pill.forEach { tab ->
                val (icon, label) = when (tab) {
                    MainTab.APPS -> Icons.Filled.Apps to androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.tab_apps)
                    MainTab.CHATS -> Icons.AutoMirrored.Filled.Chat to androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.tab_chats)
                    MainTab.PAYMENTS -> Icons.Filled.Bolt to androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.tab_payments)
                    MainTab.YOU -> Icons.Filled.Person to androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.tab_you)
                }
                TabIcon(icon, label, selected == tab) { onSelect(tab) }
            }
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

/// The bar's tabs, left to right: Chats | Payments | Apps | You. Apps lists the workspace apps and
/// every public app opened here, an employer's Tasks page too (docs/phases/48-tasks-tab.md). Public: `MainTabs` takes one as the tab to
/// return to (`MainActivity.PeelApp` remembers it across Voice Mode, docs/phases/55-voice-mode.md).
enum class MainTab {
    CHATS, PAYMENTS, APPS, YOU;

    companion object {
        /// The tabs in the bar's pill, in order.
        val pill: List<MainTab> = listOf(CHATS, PAYMENTS, APPS, YOU)
    }
}
