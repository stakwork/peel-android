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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sphinx.peel.account.FileSecretStore
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.dashboard.MainTabs
import com.sphinx.peel.ui.lock.LockScreen
import com.sphinx.peel.ui.onboarding.OnboardingScreen
import com.sphinx.peel.ui.onboarding.RestoringAccountScreen
import com.sphinx.peel.ui.theme.PeelTheme

class MainActivity : ComponentActivity() {
    /// What a tapped notification asked for, until `MainTabs` has opened it.
    private var deepLink by mutableStateOf<DeepLink?>(null)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = DeepLink.from(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        PeelApp(secretStore, storage, deepLink) { deepLink = null }
                    }
                }
            }
        }
    }
}

private sealed class Screen {
    object Onboarding : Screen()
    object Lock : Screen()
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
private fun PeelApp(secretStore: SecretStore, storage: Storage, deepLink: DeepLink?, onDeepLinkHandled: () -> Unit) {
    var screen by remember {
        mutableStateOf<Screen>(if (secretStore.hasMnemonic) Screen.Lock else Screen.Onboarding)
    }

    when (val current = screen) {
        Screen.Onboarding -> OnboardingScreen(secretStore, storage) { engine ->
            screen = if (engine.restoreProgress.value.restoring) Screen.Restoring(engine) else Screen.Home(engine)
        }
        Screen.Lock -> LockScreen(secretStore, storage) { engine -> screen = Screen.Home(engine) }
        is Screen.Restoring -> RestoringAccountScreen(current.engine) { screen = Screen.Home(current.engine) }
        is Screen.Home -> {
            KeepConnected(current.engine, storage)
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
