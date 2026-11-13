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
import com.sphinx.peel.account.RememberedPIN
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.InviteLink
import com.sphinx.peel.engine.InviterFlow
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
    private var inviteLink by mutableStateOf<String?>(null)

    /// What a tapped notification asked for, until `MainTabs` has opened it.
    private var deepLink by mutableStateOf<DeepLink?>(null)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = DeepLink.from(intent)
        takeInviteLink(intent)
    }

    private fun takeInviteLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        // Only an invite link, and only ever held in memory: never logged or stored.
        InviteLink.fromDeepLink(data.scheme, data.getQueryParameter("action"), data.toString())?.let { inviteLink = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) takeInviteLink(intent)
        // No chat contents in the Recents thumbnail (API 33+). Screens that show
        // a secret are additionally FLAG_SECURE (`SecureWindow`).
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        Notifications.createChannels(applicationContext)
        // Android 13+: asked once, here, as iOS asks once when an account opens.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.permitted(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        deepLink = DeepLink.from(intent)
        val secretStore = FileSecretStore(applicationContext)
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
                            PeelApp(secretStore, storage, inviteLink, { inviteLink = null }, deepLink) { deepLink = null }
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
) {
    var screen by remember {
        mutableStateOf<Screen>(if (secretStore.hasMnemonic) Screen.Lock else Screen.Onboarding)
    }

    // The engine keeps running behind the lock, so its connection watcher,
    // push registration and notifications do too (one composition spot, so
    // Home <-> Relock does not restart them).
    val kept = (screen as? Screen.Home)?.engine ?: (screen as? Screen.Relock)?.engine
    if (kept != null) KeepConnected(kept, storage)
    when (val current = screen) {
        Screen.Onboarding -> OnboardingScreen(secretStore, storage, inviteLink, onInviteLinkUsed) { engine ->
            screen = if (engine.restoreProgress.value.restoring) Screen.Restoring(engine) else Screen.Home(engine)
        }
        Screen.Lock -> LockScreen(secretStore, storage) { engine -> screen = Screen.Home(engine) }
        is Screen.Relock -> LockScreen(secretStore, storage, existing = current.engine) { engine -> screen = Screen.Home(engine) }
        is Screen.Restoring -> RestoringAccountScreen(current.engine) { screen = Screen.Home(current.engine) }
        is Screen.Home -> {
            RelockWhenIdle(current.engine, secretStore) { screen = Screen.Relock(current.engine) }
            BiometricHousekeeping(current.engine, secretStore)
            StadeumHousekeeping(current.engine)
            InviterNotice(current.engine)
            MainTabs(current.engine, storage, secretStore, deepLink, onDeepLinkHandled) { screen = Screen.Onboarding }
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
        val observer = LifecycleEventObserver { _, event ->
            val remembered = secretStore.remembered
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stopped = true
                    remembered?.touch(RememberedPIN.nowSeconds())
                    // A synced change of the setting reaches what is kept.
                    remembered?.timeoutChanged(engine.pinTimeoutHours())
                }
                Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    val step = LockDecision.decide(
                        rememberedPinValid = remembered?.pin(RememberedPIN.nowSeconds()) != null,
                        biometricsEnabled = secretStore.biometricEnrolled,
                        biometricsAvailable = com.sphinx.peel.ui.lock.BiometricUnlock.available(context),
                    )
                    if (step != LockDecision.Step.OpenWithRememberedPin) relock()
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
        secretStore.remembered?.timeoutChanged(engine.pinTimeoutHours())
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
