package com.sphinx.peel

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.sphinx.peel.engine.ConnectionWatcher
import com.sphinx.peel.push.MessageNotifier
import com.sphinx.peel.push.Notifications
import com.sphinx.peel.push.PushRegistrar
import com.sphinx.peel.ui.dashboard.DeepLink
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sphinx.peel.account.FileSecretStore
import com.sphinx.peel.account.LockDecision
import com.sphinx.peel.diagnostics.DiagnosticEvent
import com.sphinx.peel.diagnostics.Diagnostics
import com.sphinx.peel.diagnostics.LockTrace
import com.sphinx.peel.account.RememberedPIN
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.InviteLink
import com.sphinx.peel.engine.InviterFlow
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.StorageWalletConnections
import com.sphinx.peel.engine.WalletHost
import com.sphinx.peel.ui.page.PublicPageRouter
import com.sphinx.peel.ui.apps.OrgPageScreen
import com.sphinx.peel.engine.PublicPage
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.dashboard.MainTabs
import com.sphinx.peel.ui.lock.LockScreen
import com.sphinx.peel.ui.onboarding.OnboardingScreen
import com.sphinx.peel.ui.onboarding.RestoringAccountScreen
import com.sphinx.peel.ui.theme.PeelTheme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastHost
import com.sphinx.peel.ui.toast.rememberToastState

/// A FragmentActivity (still a ComponentActivity, `setContent` works) because
/// BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    /// The invite a `sphinx.chat://?action=i&d=…` link opened the app with, until the
    /// onboarding's invite step takes it (open question: the scheme is the official apps').
    /// A worker's link names an app too (`&a=`), which goes to [publicLink].
    private var inviteLink by mutableStateOf<String?>(null)

    /// What a tapped notification asked for, until `MainTabs` has opened it.
    private var deepLink by mutableStateOf<DeepLink?>(null)

    /// A `peel://open?url=…` link (a stranger's public strut app to open, docs/phases/27-sign-in-with-sphinx.md),
    /// until `PeelApp` has taken it: opened at once when the account is unlocked, else once it unlocks.
    private var publicLink by mutableStateOf<PublicAppLink?>(null)

    /// Whether a worker's invite link named [publicLink] (its `a=`), not a `peel://open` link: that page signs the account in with no sheet
    /// (docs/phases/49-invite-signs-in.md). In memory only, like the link; never logged.
    private var publicLinkInvited by mutableStateOf(false)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = DeepLink.from(intent)
        takeInviteLink(intent)
        takePublicLink(intent)
        takeBoxLink(intent)
    }

    /// A strut box's `peel://strut/link?url=…&challenge=…` (docs/phases/45-box-link.md): its question waits for the person's yes
    /// (`Boxes.model.asking`, asked over the tabs once unlocked), or why Peel won't sign it is said; either way You opens, where
    /// the boxes are (iOS `PhoneRouter.open(peelLink:)`). Held in memory only.
    private fun takeBoxLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        if (data.scheme?.lowercase() != "peel" || data.host?.lowercase() != "strut") return
        val offer = com.sphinx.peel.engine.BoxLinkOffer.parse(data.toString())
        if (offer != null) com.sphinx.peel.engine.Boxes.model.take(offer) else com.sphinx.peel.engine.Boxes.model.takeBroken()
        deepLink = DeepLink.YOU
    }

    /// Only a strictly well-formed `peel://open?url=…`; anything else is ignored. Held in memory only.
    private fun takePublicLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        if (data.scheme?.lowercase() != "peel") return
        PublicAppLink.parse(data.toString())?.let {
            publicLink = it
            publicLinkInvited = false
        }
    }

    private fun takeInviteLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        // Only an invite link, and only ever held in memory: never logged or stored.
        val link = InviteLink.fromDeepLink(data.scheme, data.getQueryParameter("action"), data.toString()) ?: return
        // A tapped link wins: the clipboard is not read for an invite after one.
        clipboardInviteChecked = true
        takeInvite(link)
    }

    private fun takeInvite(link: String) {
        inviteLink = link
        // A worker's invite link (`&a=`, docs/phases/41-worker-onboarding.md) also names a public app: it goes the way a
        // `peel://open` link does, so it opens once the account is unlocked: at once for an account already here (whose invite
        // part is ignored, as before), after signup for a new one. A link with no valid `a=` is only the invite, as before. Either way
        // its page signs the account in as you with no sheet (docs/phases/49-invite-signs-in.md).
        InviteLink.appOf(link)?.let {
            publicLink = it
            publicLinkInvited = true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) takeClipboardInvite()
    }

    /// The invite strut's join page copied as it downloaded Peel (docs/phases/46-invite-from-clipboard.md): nothing carries a
    /// tapped link across an install, so a Peel with no account and no invite in hand looks on the clipboard, once a process,
    /// at its first focus (Android lets only the focused app read it). A whole `sphinx.chat://?action=i&…` link alone is
    /// taken, as a tapped one is (its `a=` included), and cleared from the clipboard; it is never logged or stored.
    private fun takeClipboardInvite() {
        if (clipboardInviteChecked || inviteLink != null) return
        clipboardInviteChecked = true
        if (secretStore.hasMnemonic) return
        val text = ClipboardUtil.readText(this) ?: return
        val link = InviteLink.fromClipboard(text) ?: return
        takeInvite(link)
        ClipboardUtil.clearIfUnchanged(this, text)
    }

    private lateinit var secretStore: FileSecretStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            takeInviteLink(intent)
            takePublicLink(intent)
        }
        // No chat contents in the Recents thumbnail (API 33+). Screens that show
        // a secret are additionally FLAG_SECURE (`SecureWindow`).
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        Notifications.createChannels(applicationContext)
        // Android 13+: asked once, here, as iOS asks once when an account opens.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.permitted(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        deepLink = DeepLink.from(intent)
        if (savedInstanceState == null) takeBoxLink(intent)
        secretStore = FileSecretStore(applicationContext)
        val storage = Storage(applicationContext)
        setContent {
            PeelTheme {
                // The background paints the whole screen, behind the system
                // bars too (edge-to-edge is mandatory from API 35); only the
                // content inside is pushed clear of them.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val toast = rememberToastState()
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        CompositionLocalProvider(LocalToast provides toast) {
                            PeelApp(secretStore, storage, inviteLink, { inviteLink = null }, deepLink, { deepLink = null }, publicLink, publicLinkInvited) { publicLink = null; publicLinkInvited = false }
                        }
                        ToastHost(toast, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

private sealed class Screen {
    object Onboarding : Screen()
    object Lock : Screen()
    /// Idle past the PIN timeout: the engine keeps running behind the lock.
    data class Relock(val engine: Engine) : Screen()
    data class Restoring(val engine: Engine) : Screen()
    data class Home(val engine: Engine) : Screen()
}

/// Routes between the lock screen (an account is already sealed on this
/// device), onboarding (none is), the restore screen (a restored account
/// whose history hasn't finished) and the account placeholder, mirroring
/// what AppModel does on the Mac and iPhone once the engine exists. The
/// `Engine` for whichever account is open is made exactly once — by
/// onboarding or by the lock screen — and threaded through from there, so
/// `Engine.start()` is never called twice for the same account and its
/// connection survives moving between these screens.
@Composable
private fun PeelApp(
    secretStore: SecretStore, storage: Storage, inviteLink: String?, onInviteLinkUsed: () -> Unit,
    deepLink: DeepLink?, onDeepLinkHandled: () -> Unit,
    publicLink: PublicAppLink?, publicLinkInvited: Boolean, onPublicLinkTaken: () -> Unit,
) {
    var screen by remember {
        mutableStateOf<Screen>(if (secretStore.hasMnemonic) Screen.Lock else Screen.Onboarding)
    }
    // The PIN asked at a cold launch (not again for a rotation: the process has noted it once).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!coldLockNoted && screen == Screen.Lock) Diagnostics.recordLock(DiagnosticEvent.LockReason.coldLaunch)
        coldLockNoted = true
    }

    // A stranger's public app (`peel://open?url=…`, or a worker's invite link's `&a=`): opened when the account is unlocked,
    // kept until it is (also across a lock: only the open page closes then). Page and waiting link both go on sign-out or wipe.
    // Each page opened is kept for the Apps list (docs/phases/41-worker-onboarding.md). The page a worker's invite link named signs
    // the account in as you with no sheet: granted as it opens, before it loads (docs/phases/49-invite-signs-in.md).
    val router = remember {
        PublicPageRouter(opened = { link, invited ->
            storage.rememberPublicApp(link)
            if (invited) WalletHost.grantInvitePage(link, StorageWalletConnections(storage), System.currentTimeMillis() / 1000)
        })
    }
    val unlocked = screen is Screen.Home
    androidx.compose.runtime.LaunchedEffect(publicLink, unlocked) {
        publicLink?.let {
            router.open(it, unlocked, invited = publicLinkInvited)
            onPublicLinkTaken()
        }
    }

    // The engine keeps running behind the lock, so its connection watcher,
    // push registration and notifications do too (one composition spot, so
    // Home <-> Relock does not restart them).
    val kept = (screen as? Screen.Home)?.engine ?: (screen as? Screen.Relock)?.engine
    if (kept != null) KeepConnected(kept, storage)
    when (val current = screen) {
        // A worker's invite pasted or scanned in names its app too (`a=`): it waits to open after signup, marked as a tapped
        // link's is (docs/phases/51-pasted-invite.md).
        Screen.Onboarding -> OnboardingScreen(
            secretStore, storage, inviteLink, onInviteLinkUsed,
            onInviteApp = { router.open(it, ready = false, invited = true) },
        ) { engine ->
            screen = if (engine.restoreProgress.value.restoring) Screen.Restoring(engine) else Screen.Home(engine)
        }
        Screen.Lock -> LockScreen(secretStore, storage) { engine -> screen = Screen.Home(engine) }
        is Screen.Relock -> LockScreen(secretStore, storage, existing = current.engine) { engine -> screen = Screen.Home(engine) }
        is Screen.Restoring -> RestoringAccountScreen(current.engine) { screen = Screen.Home(current.engine) }
        is Screen.Home -> {
            RelockWhenIdle(current.engine, secretStore) { screen = Screen.Relock(current.engine) }
            BiometricHousekeeping(current.engine, secretStore)
            StadeumHousekeeping(current.engine)
            BoxesHousekeeping(current.engine, storage)
            InviterNotice(current.engine)
            DisposableEffect(Unit) {
                router.openWaitingLink()
                onDispose { router.locked() }
            }
            Box(Modifier.fillMaxSize()) {
                MainTabs(current.engine, storage, secretStore, deepLink, onDeepLinkHandled, pages = router) { router.reset(); screen = Screen.Onboarding }
                router.page?.let { link -> val page = remember(link) { PublicPage(link) }; OrgPageScreen(page, current.engine.walletHost, onClose = router::close, locations = storage) }
            }
        }
    }
}

/// Everything that keeps an open account reachable: reconnect on network
/// change and when the app comes to the front (`ConnectionWatcher`), the
/// push token registered with the server (`PushRegistrar`) and the
/// notifications for what arrives in the background (`MessageNotifier`).
/// The engine itself reconnects with backoff after a drop.
@Composable
private fun KeepConnected(engine: Engine, storage: Storage) {
    val context = LocalContext.current.applicationContext
    DisposableEffect(engine) {
        val watcher = ConnectionWatcher(context, engine).also { it.start() }
        val notifier = MessageNotifier(context, storage, engine).also { it.attach() }
        PushRegistrar.accountOpened(context, engine, storage)
        onDispose {
            watcher.stop()
            notifier.detach()
            PushRegistrar.accountClosed()
        }
    }
}

/// The PIN timeout (`PinTimeout`, `RememberedPIN`): leaving the foreground
/// records when the app was last in use; coming back, the lock decision
/// (`LockDecision`) says whether to open straight on (remembered PIN valid,
/// biometrics off) or lock again (biometric cover, or the PIN keypad when the
/// timeout is 0 or has run out).
@Composable
private fun RelockWhenIdle(engine: Engine, secretStore: SecretStore, relock: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current.applicationContext
    DisposableEffect(engine, lifecycleOwner) {
        var stopped = false
        var stoppedAt = System.currentTimeMillis()
        val observer = LifecycleEventObserver { _, event ->
            val remembered = secretStore.remembered
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stopped = true
                    stoppedAt = System.currentTimeMillis()
                    remembered?.touch(RememberedPIN.nowSeconds())
                    // A synced change of the setting reaches what is kept.
                    engine.pinTimeoutHoursOrNull()?.let { remembered?.timeoutChanged(it) }
                }
                Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    val step = LockDecision.decide(
                        rememberedPinValid = remembered?.pin(RememberedPIN.nowSeconds()) != null,
                        biometricsEnabled = secretStore.biometricEnrolled,
                        biometricsAvailable = com.sphinx.peel.ui.lock.BiometricUnlock.available(context),
                    )
                    if (step != LockDecision.Step.OpenWithRememberedPin) {
                        val hours = engine.pinTimeoutHours()
                        Diagnostics.recordLock(
                            LockTrace.reason(step, hours), idleSeconds = ((System.currentTimeMillis() - stoppedAt) / 1000).toInt(), timeoutHours = hours,
                        )
                        relock()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/// Tells the engine whether this device can offer biometrics (so
/// `biometric_enabled` syncs), and applies the synced wish's "off": another
/// device turning biometric unlock off turns it off here too. A synced "on"
/// is only offered in You, never enrolled without the PIN (PeelCore decision 10).
@Composable
private fun BiometricHousekeeping(engine: Engine, secretStore: SecretStore) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(engine) {
        engine.syncsBiometrics = com.sphinx.peel.ui.lock.BiometricUnlock.available(context)
        // The timeout may have been synced from another device since the PIN was kept.
        engine.pinTimeoutHoursOrNull()?.let { secretStore.remembered?.timeoutChanged(it) }
        if (engine.biometricWish() == false && secretStore.biometricEnrolled) secretStore.disableBiometric()
    }
}

/// Opens the Stadeum pairing kept for this account while it is on screen
/// (renewed at once and then hourly), renews it when due on coming back to
/// the front, and lets it go when the account closes or locks (iOS opens it
/// at every unlock).
@Composable
private fun StadeumHousekeeping(engine: Engine) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(engine, lifecycleOwner) {
        val model = com.sphinx.peel.engine.Stadeum.model
        model.sphinxProof = { runCatching { engine.sphinxProof() }.getOrNull() }
        model.open(com.sphinx.peel.account.KeystoreHiveStore(context.applicationContext))
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (first) first = false else model.cameToFront()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.close()
            model.sphinxProof = null
        }
    }
}

/// Opens the strut boxes this account is linked to while it is on screen (renewed when due, hourly, and on coming back to the
/// front), and lets them go when the account closes or locks (iOS opens them at every unlock; docs/phases/45-box-link.md).
@Composable
private fun BoxesHousekeeping(engine: Engine, storage: Storage) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(engine, lifecycleOwner) {
        val model = com.sphinx.peel.engine.Boxes.model
        // Signing off the main thread: the native core's work, not the screen's.
        model.sign = { challenge -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { engine.signBoxLink(challenge) } }
        model.accountName = { storage.value(com.sphinx.peel.storage.Setting.ALIAS)?.takeIf { it.isNotBlank() } }
        model.open(com.sphinx.peel.account.KeystoreBoxStore(context.applicationContext))
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (first) first = false else model.cameToFront()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.close()
            model.sign = null
            model.accountName = null
        }
    }
}

/// Says so when adding the inviter of a new account did not work: a failed key exchange is not
/// retried (it may have gone), one the core refused is (`InviterFlow.Outcome.WillRetry`).
@Composable
private fun InviterNotice(engine: Engine) {
    val toast = com.sphinx.peel.ui.toast.LocalToast.current
    androidx.compose.runtime.LaunchedEffect(engine) {
        engine.inviterOutcome.collect { outcome ->
            when (outcome) {
                is InviterFlow.Outcome.Failed ->
                    toast.show("Adding the person who invited you didn't work: ${outcome.message}", com.sphinx.peel.ui.toast.ToastType.Error)
                is InviterFlow.Outcome.WillRetry ->
                    toast.show("Adding the person who invited you didn't work yet. Peel will try again.", com.sphinx.peel.ui.toast.ToastType.Warning)
                else -> {}
            }
        }
    }
}

/// Whether this process has already noted its cold-launch lock (`Diagnostics.recordLock`).
private var coldLockNoted = false

/// Whether this process has looked on the clipboard for an invite (`MainActivity.takeClipboardInvite`): once, not again
/// on a rotation or at every return to the app.
private var clipboardInviteChecked = false
