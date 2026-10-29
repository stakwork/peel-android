package com.sphinx.peel.ui.you

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// "You" (docs/design/iphone.md, screen 6 — the real app's profile
/// card, Agent and Stadeum rows, Account settings, Debug): this port's
/// own name/photo, connection state, the boost amount
/// (`Engine.boostAmountMsat`/`setBoostAmount`, synced across this
/// account's devices — `docs/phases/11-profile-preferences.md`), and
/// "Forget this account". Twelve words, Change PIN, Face ID, "Ask for
/// the PIN", Confirm payments, Server, the Agent/Stadeum rows and
/// Debug are all still the real app's own screen, not this one's yet —
/// see that phase doc's "Open".
@Composable
fun YouScreen(engine: Engine, storage: Storage, secretStore: SecretStore, onForgotten: () -> Unit) {
    val connection by engine.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmingForget by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var editingBoostAmount by remember { mutableStateOf(false) }
    var alias by remember { mutableStateOf(storage.value(Setting.ALIAS)?.takeIf { it.isNotEmpty() }) }
    var boostAmountMsat by remember { mutableStateOf(engine.boostAmountMsat()) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(
                    name = alias ?: "You", url = storage.value(Setting.PHOTO_URL)?.takeIf { it.isNotEmpty() },
                    size = 72.dp, hue = PersonHue.of(engine.accountKey), shape = AvatarShape.CIRCLE,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.clickable { editingName = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        alias ?: "Set your name",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (alias != null) Theme.colors.text else Theme.colors.secondaryText,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Edit, contentDescription = "Edit name", tint = Theme.colors.secondaryText, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("pubkey", color = Theme.colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                Text(engine.accountKey, color = Theme.colors.text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                when (connection) {
                    ConnectionState.Idle -> Text("Not connected yet.", color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                    ConnectionState.Connecting -> Text("Connecting…", color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                    ConnectionState.Connected -> Text(
                        "Connected and registered with the server.",
                        color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodySmall,
                    )
                    is ConnectionState.Failed -> Text(
                        (connection as ConnectionState.Failed).message,
                        color = Theme.colors.destructive, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    )
                }
            }
        }

        item {
            Text(
                "Account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = Theme.colors.secondaryText, modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.colors.fieldFill)) {
                Row(
                    Modifier.fillMaxWidth().clickable { editingBoostAmount = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Boost amount", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, modifier = Modifier.weight(1f))
                    Text("${boostAmountMsat / 1000} sats", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                }
            }
        }

        item { Spacer(Modifier.height(40.dp)) }
        item {
            Button(
                onClick = { confirmingForget = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.destructive),
            ) { Text("Forget this account (dev)") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editingName) {
        var draft by remember { mutableStateOf(alias ?: "") }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your name") },
            text = {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Theme.colors.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Theme.colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = draft.trim()
                    if (trimmed.isNotEmpty()) {
                        storage.apply(Storage.Commit(settings = mapOf(Setting.ALIAS to trimmed)))
                        alias = trimmed
                    }
                    editingName = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } },
        )
    }

    if (editingBoostAmount) {
        var draft by remember { mutableStateOf((boostAmountMsat / 1000).toString()) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editingBoostAmount = false },
            title = { Text("Boost amount") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it.filter(Char::isDigit) },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Theme.colors.text),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Theme.colors.accent),
                            modifier = Modifier.weight(1f),
                        )
                        Text(" sats", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.secondaryText)
                    }
                    error?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Theme.colors.destructive)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val sats = draft.toLongOrNull()
                    if (sats == null || sats <= 0) {
                        error = "Enter a whole number of sats, more than zero."
                    } else {
                        boostAmountMsat = sats * 1000
                        editingBoostAmount = false
                        scope.launch {
                            withContext(Dispatchers.Default) { engine.setBoostAmount(sats) }
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingBoostAmount = false }) { Text("Cancel") } },
        )
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
