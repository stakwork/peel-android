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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.Account
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.ui.theme.Theme

/// Stands in for the real chat list until the engine (storage, MQTT,
/// contacts, tribes) exists on Android. Proves the account is real, not
/// just stored: it re-derives this account's own pubkey from the sealed
/// mnemonic through sphinxrs, the same core the Mac and iPhone apps use.
@Composable
fun HomeScreen(mnemonic: String, secretStore: SecretStore, onForgotten: () -> Unit) {
    val pubkey = remember(mnemonic) { Account.pubkeyOf(Account.seedOf(mnemonic)) }

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
        Spacer(Modifier.height(16.dp))
        Text("pubkey", color = Theme.colors.secondaryText, style = MaterialTheme.typography.labelMedium)
        Text(pubkey, color = Theme.colors.text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        Text(
            "No server connection yet: this is local-only until the engine (storage, MQTT, contacts) is built.",
            color = Theme.colors.secondaryText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = { secretStore.deleteMnemonic(); onForgotten() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.destructive),
        ) { Text("Forget this account (dev)") }
    }
}
