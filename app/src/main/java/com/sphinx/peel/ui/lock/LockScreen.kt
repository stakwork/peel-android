package com.sphinx.peel.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sphinx.peel.account.LockDecision
import com.sphinx.peel.account.LockoutPolicy
import com.sphinx.peel.account.RememberedPIN
import com.sphinx.peel.ui.theme.Theme
import androidx.compose.material3.MaterialTheme
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.account.SecretStoreFailure
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.SecureWindow
import com.sphinx.peel.ui.components.PinPrompt
import com.sphinx.peel.ui.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Opens the account already sealed on this device, then opens its `Engine`
/// and starts it — connecting (restoring too, if an earlier restore never
/// finished) before `onUnlocked` even fires, the same as a fresh onboarding does.
///
/// What it shows is `LockDecision`: while the PIN timeout has not run out the
/// PIN is remembered (`RememberedPIN`), so no keypad — either it opens at once,
/// or, with biometrics on, behind a "Peel is locked" cover whose check (device
/// passcode as fallback, never the Peel PIN) releases the remembered PIN.
/// Timeout 0 or past it: the keypad, and only the PIN opens (no biometrics
/// here). A remembered PIN that is wrong (changed elsewhere) is dropped and the
/// PIN asked. A typed PIN that works is remembered again.
///
/// With `existing` (a re-lock after the app came back) the engine is already
/// running behind this screen and is handed back untouched.
///
/// Wrong PINs are throttled by the store (`LockoutPolicy`); the pad is inert
/// and shows a countdown while a wait is due.
@Composable
fun LockScreen(
    secretStore: SecretStore,
    storage: Storage,
    existing: Engine? = null,
    onUnlocked: (engine: Engine) -> Unit,
) {
    SecureWindow()
    var phase by remember { mutableStateOf(Phase.DECIDING) }
    var pin by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var waitMs by remember { mutableLongStateOf(secretStore.lockoutRemainingMs()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context.findActivity() as? FragmentActivity
    val remembered = secretStore.remembered

    // Counts the wait down, once a second, while there is one.
    LaunchedEffect(waitMs > 0) {
        while (waitMs > 0) {
            delay(1000)
            waitMs = secretStore.lockoutRemainingMs()
        }
    }

    suspend fun openEngine(mnemonic: String): Engine =
        existing ?: withContext(Dispatchers.Default) { Engine(mnemonic, storage).also { it.start() } }

    /// With the remembered PIN: no prompt. Anything wrong drops to the keypad.
    suspend fun openWithRemembered() {
        working = true
        val kept = withContext(Dispatchers.Default) { remembered?.pin(RememberedPIN.nowSeconds()) }
        if (kept == null) {
            phase = Phase.PIN
            working = false
            return
        }
        try {
            val mnemonic = withContext(Dispatchers.Default) { secretStore.loadMnemonic(kept) }
            val engine = openEngine(mnemonic)
            withContext(Dispatchers.Default) { remembered?.touch(RememberedPIN.nowSeconds()) }
            onUnlocked(engine)
        } catch (e: SecretStoreFailure.LockedOut) {
            waitMs = e.remainingMs
            phase = Phase.PIN
        } catch (e: SecretStoreFailure.WrongPin) {
            // Changed elsewhere: forget it and ask.
            withContext(Dispatchers.Default) { remembered?.forget() }
            waitMs = secretStore.lockoutRemainingMs()
            phase = Phase.PIN
        } catch (e: Exception) {
            problem = e.message ?: "Could not open the account."
            phase = Phase.PIN
        }
        working = false
    }

    fun biometricCheck() {
        val host = activity ?: return
        if (working) return
        scope.launch {
            if (BiometricUnlock.check(host, "Unlock Peel")) openWithRemembered()
        }
    }

    LaunchedEffect(Unit) {
        val step = withContext(Dispatchers.Default) {
            LockDecision.decide(
                rememberedPinValid = remembered?.pin(RememberedPIN.nowSeconds()) != null,
                biometricsEnabled = secretStore.biometricEnrolled,
                biometricsAvailable = BiometricUnlock.available(context),
            )
        }
        when (step) {
            LockDecision.Step.AskPin -> phase = Phase.PIN
            LockDecision.Step.OpenWithRememberedPin -> openWithRemembered()
            LockDecision.Step.BiometricCover -> { phase = Phase.COVER; biometricCheck() }
        }
    }

    when (phase) {
        Phase.DECIDING -> Box(Modifier.fillMaxSize())
        Phase.COVER -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Peel is locked", style = MaterialTheme.typography.titleLarge, color = Theme.colors.text)
            TextButton(onClick = { biometricCheck() }, enabled = !working && activity != null) {
                Text("Unlock with biometrics")
            }
        }
        Phase.PIN -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            PinPrompt(
                title = "Enter your PIN",
                detail = if (waitMs > 0) "Too many wrong PINs. Try again in ${LockoutPolicy.describe(waitMs)}." else problem,
                pin = pin,
                onPinChange = { pin = it },
                enabled = waitMs <= 0,
                onComplete = {
                    working = true
                    // PBKDF2 at a deliberately slow iteration count: off the main
                    // thread, or the keypad would freeze (SecretStore.kt).
                    scope.launch {
                        try {
                            val typed = pin
                            val engine = openEngine(withContext(Dispatchers.Default) { secretStore.loadMnemonic(typed) })
                            withContext(Dispatchers.Default) {
                                remembered?.keep(typed, RememberedPIN.nowSeconds(), engine.pinTimeoutHours())
                            }
                            onUnlocked(engine)
                        } catch (e: SecretStoreFailure.LockedOut) {
                            waitMs = e.remainingMs
                            problem = null
                            pin = ""
                        } catch (e: SecretStoreFailure.WrongPin) {
                            waitMs = secretStore.lockoutRemainingMs()
                            problem = "Wrong PIN"
                            pin = ""
                        } catch (e: Exception) {
                            problem = e.message ?: "Could not open the account."
                            pin = ""
                        }
                        working = false
                    }
                },
                working = working,
                modifier = Modifier.weight(1f, fill = false).padding(top = 80.dp),
            )
        }
    }
}

private enum class Phase { DECIDING, PIN, COVER }
