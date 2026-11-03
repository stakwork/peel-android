package com.sphinx.peel.ui.lock

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.LockoutPolicy
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.account.SecretStoreFailure
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.PinPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Asks for the PIN to open the account already sealed on this device,
/// then opens its `Engine` and starts it — connecting (restoring too, if
/// an earlier restore never finished) before `onUnlocked` even fires, the
/// same as a fresh onboarding does. Ported from PeelCore's PinPad.swift
/// (`PinPrompt` as the lock screen).
@Composable
fun LockScreen(secretStore: SecretStore, storage: Storage, onUnlocked: (engine: Engine) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var waitMs by remember { mutableLongStateOf(secretStore.lockoutRemainingMs()) }
    val scope = rememberCoroutineScope()

    // Counts a wrong-PIN wait down, once a second, while there is one.
    LaunchedEffect(waitMs > 0) {
        while (waitMs > 0) {
            delay(1000)
            waitMs = secretStore.lockoutRemainingMs()
        }
    }

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
                    val engine = withContext(Dispatchers.Default) {
                        // The Engine zeroes these words once it has its seed.
                        val mnemonic = secretStore.loadMnemonicChars(pin)
                        Engine(mnemonic, storage).also { it.start() }
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
        modifier = Modifier.fillMaxSize().padding(top = 80.dp),
    )
}
