package com.sphinx.peel.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// Stands in for the real chat list until there is a server connection
/// (MQTT, contacts, tribes). Proves the account is real, not just stored:
/// it opens the same `Engine` onboarding prepared with `Storage` and shows
/// what it knows — the account's own key, and whether a server has
/// registered it yet (not yet possible: nothing here connects).
@Composable
fun HomeScreen(mnemonic: String, storage: Storage, secretStore: SecretStore, onForgotten: () -> Unit) {
    var engine by remember { mutableStateOf<Engine?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mnemonic) {
        try {
            engine = withContext(Dispatchers.Default) { Engine(mnemonic, storage) }
        } catch (e: Exception) {
            error = e.message ?: "Could not open the account engine."
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Peel", style = MaterialTheme.typography.headlineMedium, color = Theme.colors.text)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your account is stored on this device, sealed under your PIN.",
            color = Theme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        when {
            error != null -> Text(error!!, color = Theme.colors.destructive, textAlign = TextAlign.Center)
            engine == null -> CircularProgressIndicator(color = Theme.colors.accent)
            else -> {
                val current = engine!!
                Text("pubkey", color = Theme.colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                Text(current.accountKey, color = Theme.colors.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (current.isRegistered) "Registered with a server." else "Not registered with a server yet.",
                    color = Theme.colors.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "No server connection yet: this is local-only until MQTT (engine/Transport.kt) is wired up.",
            color = Theme.colors.secondaryText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = {
                // Both stores, not just the mnemonic: otherwise the next
                // account made on this device would start from this one's
                // coreState (Storage.kt, `deleteAccount`).
                storage.deleteAccount()
                secretStore.deleteMnemonic()
                onForgotten()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.destructive),
        ) { Text("Forget this account (dev)") }
    }
}
