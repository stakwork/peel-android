package com.sphinx.peel.ui.lock

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.account.SecretStoreFailure
import com.sphinx.peel.ui.components.PinPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Asks for the PIN to open the account already sealed on this device.
/// Ported from PeelCore's PinPad.swift (`PinPrompt` as the lock screen).
@Composable
fun LockScreen(secretStore: SecretStore, onUnlocked: (mnemonic: String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PinPrompt(
        title = "Enter your PIN",
        detail = problem,
        pin = pin,
        onPinChange = { pin = it },
        onComplete = {
            working = true
            // PBKDF2 at a deliberately slow iteration count: off the main
            // thread, or the keypad would freeze (SecretStore.kt).
            scope.launch {
                try {
                    val mnemonic = withContext(Dispatchers.Default) { secretStore.loadMnemonic(pin) }
                    onUnlocked(mnemonic)
                } catch (e: SecretStoreFailure.WrongPin) {
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
