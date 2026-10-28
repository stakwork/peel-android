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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.ServerAddress
import com.sphinx.peel.engine.ServerConfig
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class ConnectionState {
    object Idle : ConnectionState()
    object Connecting : ConnectionState()
    object Registered : ConnectionState()
    object ConnectedNotRegistered : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}

/// Stands in for the real chat list until there are `message`/`contact`
/// tables to show (nothing here persists what a connection fetches yet).
/// Proves the account is real, not just stored: it opens the same
/// `Engine` onboarding prepared with `Storage` and shows what it knows —
/// the account's own key, and, once "Connect" is pressed, whatever the
/// production server answers. `Engine.connect` is the minimal "does this
/// account reach the server and get registered" path (docs/phases/
/// 02-connecting.md); it is not the full online pipeline, so a registered
/// result here still shows no messages or contacts.
@Composable
fun HomeScreen(mnemonic: String, storage: Storage, secretStore: SecretStore, onForgotten: () -> Unit) {
    var engine by remember { mutableStateOf<Engine?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var connection by remember { mutableStateOf<ConnectionState>(ConnectionState.Idle) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(mnemonic) {
        try {
            engine = withContext(Dispatchers.Default) { Engine(mnemonic, storage) }
        } catch (e: Exception) {
            error = e.message ?: "Could not open the account engine."
        }
    }

    fun connect(current: Engine) {
        connection = ConnectionState.Connecting
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val config = ServerConfig.fetch(current.network)
                    val server = ServerAddress.named(config.defaultLsp)
                    current.connect(server)
                }
                connection = if (current.isRegistered) {
                    ConnectionState.Registered
                } else {
                    ConnectionState.ConnectedNotRegistered
                }
            } catch (e: Exception) {
                connection = ConnectionState.Failed(e.message ?: "Could not connect to the server.")
            }
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

                when (val state = connection) {
                    ConnectionState.Idle -> Text(
                        if (current.isRegistered) "Registered with a server." else "Not registered with a server yet.",
                        color = Theme.colors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ConnectionState.Connecting -> Text(
                        "Connecting…",
                        color = Theme.colors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ConnectionState.Registered -> Text(
                        "Connected and registered with the server.",
                        color = Theme.colors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ConnectionState.ConnectedNotRegistered -> Text(
                        "Connected, but no registration answer came back in time.",
                        color = Theme.colors.secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    is ConnectionState.Failed -> Text(
                        state.message,
                        color = Theme.colors.destructive,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { connect(current) },
                    enabled = connection != ConnectionState.Connecting,
                    colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
                ) { Text("Connect to server") }
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "No messages or contacts yet: connecting only proves the account reaches " +
                "the server, there is nowhere to show what it sends back.",
            color = Theme.colors.secondaryText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = {
                scope.launch {
                    engine?.disconnect()
                    // Both stores, not just the mnemonic: otherwise the
                    // next account made on this device would start from
                    // this one's coreState (Storage.kt, `deleteAccount`).
                    storage.deleteAccount()
                    secretStore.deleteMnemonic()
                    onForgotten()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.destructive),
        ) { Text("Forget this account (dev)") }
    }
}
