package com.sphinx.peel.ui.lock

import androidx.compose.foundation.layout.Column
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
import com.sphinx.peel.account.LockoutPolicy
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

/// Asks for the PIN (or a biometric match, when it is set up) to open the
/// account already sealed on this device, then opens its `Engine` and starts
/// it — connecting (restoring too, if an earlier restore never finished)
/// before `onUnlocked` even fires, the same as a fresh onboarding does.
/// Ported from PeelCore's PinPad.swift (`PinPrompt` as the lock screen).
///
/// With `existing` (an idle re-lock, `PinTimeout`) the engine is already
/// running behind this screen and is handed back untouched once the words
/// open — nothing is rebuilt.
///
/// A biometric match releases the stored derived key, so the multi-second
/// PBKDF2 is skipped; the typed PIN path is unchanged. Wrong PINs are
/// throttled by the store (`LockoutPolicy`); the pad is inert and shows a
/// countdown while a wait is due. Biometrics are not throttled by it (the OS
/// has its own lockout), and never reset it.
@Composable
fun LockScreen(
    secretStore: SecretStore,
    storage: Storage,
    existing: Engine? = null,
    onUnlocked: (engine: Engine) -> Unit,
) {
    SecureWindow()
    var pin by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var waitMs by remember { mutableLongStateOf(secretStore.lockoutRemainingMs()) }
    var offerBiometric by remember { mutableStateOf(secretStore.biometricEnrolled) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current.findActivity() as? FragmentActivity

    // Counts the wait down, once a second, while there is one.
    LaunchedEffect(waitMs > 0) {
        while (waitMs > 0) {
            delay(1000)
            waitMs = secretStore.lockoutRemainingMs()
        }
    }

    suspend fun open(mnemonic: String) {
        val engine = existing ?: withContext(Dispatchers.Default) { Engine(mnemonic, storage).also { it.start() } }
        onUnlocked(engine)
    }

    fun tryBiometric() {
        val host = activity ?: return
        scope.launch {
            working = true
            try {
                val mnemonic = BiometricUnlock.unlockWords(host, secretStore, "Unlock Peel", null)
                if (mnemonic != null) open(mnemonic) else offerBiometric = secretStore.biometricEnrolled
            } catch (e: Exception) {
                problem = e.message ?: "Could not open the account."
                offerBiometric = secretStore.biometricEnrolled
            }
            working = false
        }
    }

    // Asks for the biometric straight away, once, when it is on and no PIN wait is due.
    LaunchedEffect(Unit) { if (offerBiometric) tryBiometric() }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
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
                        open(withContext(Dispatchers.Default) { secretStore.loadMnemonic(pin) })
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
        if (offerBiometric && activity != null) {
            TextButton(onClick = { tryBiometric() }, enabled = !working) { Text("Use biometrics") }
        }
    }
}
