package com.sphinx.peel.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Stands in for the real chat list until there is somewhere to show what
/// a connection fetches (the `contact`/`message` tables exist now, since
/// `Engine.runRestore` fills them, but nothing reads them into a list
/// yet). Proves the account is real, not just stored: it opens the same
/// `Engine` onboarding (or the lock screen) already started, and shows
/// what it knows — the account's own key, and whatever
/// `Engine.connectionState` reports. There is no "Connect" button any
/// more: by the time this screen can appear, `Engine.start()` has already
/// been called once, wherever the engine was made.
@Composable
fun HomeScreen(engine: Engine, storage: Storage, secretStore: SecretStore, onForgotten: () -> Unit) {
    val connection by engine.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmingForget by remember { mutableStateOf(false) }

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

        Text("pubkey", color = Theme.colors.secondaryText, style = MaterialTheme.typography.labelMedium)
        Text(engine.accountKey, color = Theme.colors.text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        when (connection) {
            ConnectionState.Idle -> Text(
                "Not connected yet.",
                color = Theme.colors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
            ConnectionState.Connecting -> Text(
                "Connecting…",
                color = Theme.colors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
            ConnectionState.Connected -> Text(
                "Connected and registered with the server.",
                color = Theme.colors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
            is ConnectionState.Failed -> Text(
                (connection as ConnectionState.Failed).message,
                color = Theme.colors.destructive,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "No messages or contacts to show yet: restoring fills the database, " +
                "but nothing reads it into a list here yet.",
            color = Theme.colors.secondaryText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = { confirmingForget = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.destructive),
        ) { Text("Forget this account (dev)") }
    }

    if (confirmingForget) {
        AlertDialog(
            onDismissRequest = { confirmingForget = false },
            title = { Text("Forget this account?") },
            text = {
                Text(
                    "This wipes everything stored on this device for it. Your twelve words " +
                        "still restore it — on this device or another — but anything only here, " +
                        "unsaved, is gone for good.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingForget = false
                    scope.launch {
                        engine.cancel()
                        // Both stores, not just the mnemonic: otherwise the
                        // next account made on this device would start from
                        // this one's coreState (Storage.kt, `deleteAccount`).
                        withContext(Dispatchers.Default) {
                            storage.deleteAccount()
                            secretStore.deleteMnemonic()
                        }
                        onForgotten()
                    }
                }) { Text("Forget it", color = Theme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmingForget = false }) { Text("Cancel") } },
        )
    }
}
