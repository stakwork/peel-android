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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sphinx.peel.account.Account
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.account.SecretStoreFailure
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.AgentTag
import com.sphinx.peel.ui.components.PeelLogo
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.components.PinPrompt
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class YouRoute { WORDS, CHANGE_PIN }

/// "You" (docs/design/iphone.md, screen 6 — the real app's profile card,
/// Agent and Stadeum rows, Account settings, Debug): this port's own
/// name/photo/balance, the agent row, the account's own Twelve
/// words/Change PIN/boost amount/Server, and "Forget this account". Face
/// ID, "Ask for the PIN", Confirm payments, Stadeum and Debug are still
/// the real app's own screen, not this one's yet — see
/// `docs/phases/11-profile-preferences.md`'s "Open".
@Composable
fun YouScreen(engine: Engine, storage: Storage, secretStore: SecretStore, onForgotten: () -> Unit) {
    var route by remember { mutableStateOf<YouRoute?>(null) }
    when (route) {
        YouRoute.WORDS -> TwelveWordsScreen(secretStore, onBack = { route = null })
        YouRoute.CHANGE_PIN -> ChangePinScreen(secretStore, onBack = { route = null })
        null -> YouMain(
            engine, storage, secretStore, onForgotten,
            onOpenWords = { route = YouRoute.WORDS },
            onOpenChangePin = { route = YouRoute.CHANGE_PIN },
        )
    }
}

@Composable
private fun YouMain(
    engine: Engine,
    storage: Storage,
    secretStore: SecretStore,
    onForgotten: () -> Unit,
    onOpenWords: () -> Unit,
    onOpenChangePin: () -> Unit,
) {
    val connection by engine.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmingForget by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var editingBoostAmount by remember { mutableStateOf(false) }
    var alias by remember { mutableStateOf(storage.value(Setting.ALIAS)?.takeIf { it.isNotEmpty() }) }
    var boostAmountMsat by remember { mutableStateOf(engine.boostAmountMsat()) }
    // `balanceMsat` only ever moves as a side effect of some other call's
    // `RunReturn` (`Engine.commitLocked`) — nothing here asks for it on
    // its own, so re-reading it whenever the connection state changes
    // (a fresh connect, a reconnect's catch-up) is the closest this
    // screen gets to "live" without its own poll.
    var balanceMsat by remember { mutableStateOf(engine.balanceMsat()) }
    LaunchedEffect(connection) { balanceMsat = engine.balanceMsat() }
    val wordsConfirmed = remember { storage.value(Setting.WORDS_CONFIRMED) == "1" }
    val hasAgent = remember { storage.contact(engine.agentPubkey) != null }

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
                Spacer(Modifier.height(6.dp))
                Text(
                    balanceMsat?.let { "${it / 1000} sats" } ?: "–",
                    color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text("pubkey", color = Theme.colors.secondaryText, style = MaterialTheme.typography.labelMedium)
                Text(engine.accountKey, color = Theme.colors.text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }

        item {
            Text(
                "Agent", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = Theme.colors.secondaryText, modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.colors.fieldFill)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeelLogo(size = 30.dp)
                Spacer(Modifier.width(12.dp))
                Text("Peel", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                Spacer(Modifier.width(8.dp))
                AgentTag()
                Spacer(Modifier.weight(1f))
                Text(
                    // This port has no agent-setup flow of its own (bridge,
                    // device request) — just whether the shared account
                    // already has the agent's chat as a contact, set up
                    // from wherever that flow does exist.
                    if (hasAgent) "Ready" else "Set up on your Mac",
                    color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            Text(
                "Account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = Theme.colors.secondaryText, modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.colors.fieldFill)) {
                AccountRow(label = "Twelve words", value = if (wordsConfirmed) "" else "Not checked yet", onClick = onOpenWords)
                AccountDivider()
                AccountRow(label = "Change PIN", value = "", onClick = onOpenChangePin)
                AccountDivider()
                AccountRow(
                    label = "Boost amount", value = "${boostAmountMsat / 1000} sats",
                    onClick = { editingBoostAmount = true },
                )
                AccountDivider()
                AccountRow(
                    label = "Server",
                    value = (engine.serverLabel() ?: "None") + "\n" + connectionLabel(connection),
                    onClick = null,
                )
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

private fun connectionLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.Idle -> "Not connected yet"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Connected -> "Connected"
    is ConnectionState.Failed -> connection.message
}

@Composable
private fun AccountRow(label: String, value: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(
                value, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun AccountDivider() {
    HorizontalDivider(color = Theme.colors.background, thickness = 1.dp)
}

@Composable
private fun RouteHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Theme.colors.text)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Theme.colors.text)
    }
}

/// Old PIN, new PIN, the new one again, on the keypad — PeelCore's own
/// `ChangePIN`: a wrong *old* PIN is only ever discovered at the very end
/// (`SecretStore.changePin` tries to open the sealed words with it first),
/// matching that screen's own shape rather than checking it a step early.
private enum class ChangePinStep { OLD, NEW, CONFIRM }

@Composable
private fun ChangePinScreen(secretStore: SecretStore, onBack: () -> Unit) {
    var step by remember { mutableStateOf(ChangePinStep.OLD) }
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reset(to: ChangePinStep, message: String?) {
        if (to == ChangePinStep.OLD) old = ""
        new = ""
        confirmation = ""
        note = message
        step = to
    }

    Column(Modifier.fillMaxSize()) {
        RouteHeader("Change PIN", onBack)
        when (step) {
            ChangePinStep.OLD -> PinPrompt(
                title = "Enter your current PIN", detail = note, pin = old,
                onPinChange = { old = it }, onComplete = { note = null; step = ChangePinStep.NEW },
            )
            ChangePinStep.NEW -> PinPrompt(
                title = "Choose a new PIN", detail = note, pin = new,
                onPinChange = { new = it }, onComplete = { note = null; step = ChangePinStep.CONFIRM },
            )
            ChangePinStep.CONFIRM -> PinPrompt(
                title = "Enter the new PIN again", detail = note, pin = confirmation, working = working,
                onPinChange = { confirmation = it },
                onComplete = {
                    if (confirmation != new) {
                        reset(ChangePinStep.NEW, "The PINs don't match. Choose the new PIN again.")
                        return@PinPrompt
                    }
                    working = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { secretStore.changePin(old, new) }
                            onBack()
                        } catch (e: SecretStoreFailure.WrongPin) {
                            reset(ChangePinStep.OLD, "Wrong PIN")
                        } catch (e: Exception) {
                            reset(ChangePinStep.NEW, (e.message ?: "Could not change the PIN.") + " Choose the new PIN again.")
                        }
                        working = false
                    }
                },
            )
        }
    }
}

/// The twelve words after the PIN, hidden again after half a minute or
/// when this screen is left — PeelCore's own `WordsScreen`, trimmed: no
/// Face ID (this port has none yet), no "check words" flow (every account
/// here already has `WORDS_CONFIRMED` by the time it exists — a fresh
/// signup's own mandatory 3-word check, or a restore's own stronger "typed
/// all twelve" proof — so the row this screen opens from never shows the
/// "not checked" branch iOS's own version still has to handle).
@Composable
private fun TwelveWordsScreen(secretStore: SecretStore, onBack: () -> Unit) {
    val shownForMs = 30_000L
    var asking by remember { mutableStateOf(true) }
    var pin by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var visible by remember { mutableStateOf(false) }
    var revealedAt by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    fun hide() {
        words = emptyList()
        visible = false
        pin = ""
        problem = null
        asking = true
    }

    // Leaving the screen, or the app leaving the foreground, both hide the
    // words the same way letting the 30-second timer run out does —
    // PeelCore's own `onDisappear`/`scenePhase` guard, not just a timer.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) hide()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            hide()
        }
    }

    LaunchedEffect(revealedAt) {
        if (revealedAt == 0L) return@LaunchedEffect
        delay(shownForMs)
        hide()
    }

    Column(Modifier.fillMaxSize()) {
        RouteHeader("Twelve words", onBack)
        if (asking) {
            PinPrompt(
                title = "Enter your PIN", detail = problem ?: "To show your twelve words.",
                pin = pin, onPinChange = { pin = it }, working = working,
                onComplete = {
                    working = true
                    scope.launch {
                        try {
                            val mnemonic = withContext(Dispatchers.Default) { secretStore.loadMnemonic(pin) }
                            words = Account.wordsIn(mnemonic)
                            visible = false
                            problem = null
                            asking = false
                            revealedAt = System.currentTimeMillis()
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
                modifier = Modifier.fillMaxSize().padding(top = 60.dp),
            )
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(
                    "Anyone with these words controls your account. Keep them on paper.",
                    style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
                )
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Theme.colors.fieldFill).padding(16.dp)) {
                    words.forEachIndexed { index, word ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(
                                "${index + 1}.", color = Theme.colors.secondaryText,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(28.dp),
                            )
                            Text(
                                if (visible) word else "••••••",
                                color = if (visible) Theme.colors.text else Theme.colors.secondaryText,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { visible = !visible }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (visible) "Mask" else "Reveal")
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { hide() }, modifier = Modifier.fillMaxWidth()) { Text("Hide") }
            }
        }
    }
}
