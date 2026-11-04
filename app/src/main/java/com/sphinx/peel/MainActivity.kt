package com.sphinx.peel

import android.os.Bundle
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
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastHost
import com.sphinx.peel.ui.toast.rememberToastState

/// A FragmentActivity (still a ComponentActivity, `setContent` works) because
/// BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No chat contents in the Recents thumbnail (API 33+). Screens that show
        // a secret are additionally FLAG_SECURE (`SecureWindow`).
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
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
                            PeelApp(secretStore, storage)
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
private fun PeelApp(secretStore: SecretStore, storage: Storage) {
    var screen by remember {
        mutableStateOf<Screen>(if (secretStore.hasMnemonic) Screen.Lock else Screen.Onboarding)
    }

    when (val current = screen) {
        Screen.Onboarding -> OnboardingScreen(secretStore, storage) { engine ->
            screen = if (engine.restoreProgress.value.restoring) Screen.Restoring(engine) else Screen.Home(engine)
        }
        Screen.Lock -> LockScreen(secretStore, storage) { engine -> screen = Screen.Home(engine) }
        is Screen.Relock -> LockScreen(secretStore, storage, existing = current.engine) { engine -> screen = Screen.Home(engine) }
        is Screen.Restoring -> RestoringAccountScreen(current.engine) { screen = Screen.Home(current.engine) }
        is Screen.Home -> {
            ResumeOnForeground(current.engine)
            RelockWhenIdle(current.engine) { screen = Screen.Relock(current.engine) }
            BiometricHousekeeping(current.engine, secretStore)
            MainTabs(current.engine, storage, secretStore) { screen = Screen.Onboarding }
        }
    }
}

/// Calls `Engine.resume()` each time the app returns to the foreground —
/// PeelCore's own reconnect-on-foreground plus `cameOnline`'s catch-up,
/// triggered the Android way. Skips the very first `ON_RESUME`: the
/// lifecycle is already resumed when this observer is added (right after
/// `start()` just ran, making this screen reachable at all), so Android
/// replays that event into it immediately, and `resume()` would only
/// race `start()`'s own first connection.
@Composable
private fun ResumeOnForeground(engine: Engine) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(engine, lifecycleOwner) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (first) first = false else engine.resume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/// The PIN timeout (`PinTimeout`): remembers when the app left the screen
/// and, on coming back after longer than the setting, asks for the PIN (or a
/// biometric) again. Monotonic time, so changing the clock does not help.
/// iOS also re-locks an app left open in front; here only time spent in the
/// background counts (a gap, docs/phases/17-lock-hardening.md).
@Composable
private fun RelockWhenIdle(engine: Engine, relock: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(engine, lifecycleOwner) {
        var stoppedAt = -1L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stoppedAt = android.os.SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    if (stoppedAt >= 0) {
                        val idle = android.os.SystemClock.elapsedRealtime() - stoppedAt
                        if (com.sphinx.peel.account.PinTimeout.isLockDue(engine.pinTimeoutHours(), idle)) relock()
                    }
                    stoppedAt = -1L
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
        if (engine.biometricWish() == false && secretStore.biometricEnrolled) secretStore.disableBiometric()
    }
}
