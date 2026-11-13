package com.sphinx.peel.ui.you

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.sphinx.peel.account.LockoutPolicy
import com.sphinx.peel.account.PinTimeout
import com.sphinx.peel.account.SecretStoreFailure
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.ProfilePhoto
import com.sphinx.peel.ui.components.prepareImageForSending
import com.sphinx.peel.engine.PaymentRules
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.Stadeum
import com.sphinx.peel.engine.StadeumModel
import com.sphinx.peel.engine.paymentConfirmationMsat
import com.sphinx.peel.engine.setPaymentConfirmation
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.SecureWindow
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.AgentTag
import com.sphinx.peel.ui.components.PeelLogo
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.components.PinPrompt
import com.sphinx.peel.ui.findActivity
import com.sphinx.peel.ui.lock.BiometricUnlock
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class YouRoute { WORDS, CHANGE_PIN }

/// "You" (docs/design/iphone.md, screen 6), reworked to match the iOS app's
/// own Profile: a centred "You" title, the avatar/name/balance header, an
/// Agent card, one Account card (Twelve words, Change PIN, Ask for the PIN,
/// Biometric unlock, Boost amount, Confirm payments, Server), a Debug card
/// and "Forget this account" in its own card. Twelve words and Change PIN
/// open their own full screens (round back button, big title); Ask for the
/// PIN, Boost amount and Confirm payments are dialogs, since each is one
/// choice from a short list rather than its own flow. Biometric unlock's
/// own screen is the PIN check `beginBiometric` needs before enrolling it.
///
/// Not ported (see `docs/phases/11-profile-preferences.md`):
///  - "Auto download media": Android has no auto-download yet; the row is
///    in the code, commented out, with a TODO.
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

private val CardShape = RoundedCornerShape(24.dp)

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
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var confirmingForget by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var editingBoostAmount by remember { mutableStateOf(false) }
    var editingConfirmation by remember { mutableStateOf(false) }
    var editingPinTimeout by remember { mutableStateOf(false) }
    var pinTimeoutHours by remember { mutableStateOf(engine.pinTimeoutHours()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val biometricsAvailable = remember { BiometricUnlock.available(context) }
    var biometricOn by remember { mutableStateOf(secretStore.biometricEnrolled) }
    var confirmationMsat by remember { mutableStateOf(engine.paymentConfirmationMsat()) }
    var alias by remember { mutableStateOf(storage.value(Setting.ALIAS)?.takeIf { it.isNotEmpty() }) }
    var boostAmountMsat by remember { mutableStateOf(engine.boostAmountMsat()) }
    // `balanceMsat` only ever moves as a side effect of some other call's
    // `RunReturn` (`Engine.commitLocked`) — nothing here asks for it on
    // its own, so re-reading it whenever the connection state changes
    // (a fresh connect, a reconnect's catch-up) is the closest this
    // screen gets to "live" without its own poll.
    var balanceMsat by remember { mutableStateOf(engine.balanceMsat()) }
    LaunchedEffect(connection) { balanceMsat = engine.balanceMsat() }
    var photoUrl by remember { mutableStateOf(storage.value(Setting.PHOTO_URL)?.takeIf { it.isNotEmpty() }) }
    var uploadingPhoto by remember { mutableStateOf(false) }
    var photoProblem by remember { mutableStateOf<String?>(null) }
    var confirmingRemovePhoto by remember { mutableStateOf(false) }
    // Choose, redraw as a small JPEG (iOS `ProfilePhoto.jpeg`), upload to the public route and
    // store the link; the photo changes only once all of that worked.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploadingPhoto = true
        photoProblem = null
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) {
                    val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
                        ?.takeIf { it >= 0 }
                    ProfilePhoto.refusal(size)?.let { throw IllegalStateException(it) }
                    try {
                        prepareImageForSending(context, uri, ProfilePhoto.MAX_PIXELS, ProfilePhoto.JPEG_QUALITY)
                    } catch (e: OutOfMemoryError) {
                        throw IllegalStateException("That picture is too large.")
                    }
                } ?: throw IllegalStateException("That is not a picture.")
                engine.uploadPhoto(jpeg)
                photoUrl = storage.value(Setting.PHOTO_URL)?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                photoProblem = ProfilePhoto.message(e)
            } finally {
                uploadingPhoto = false
            }
        }
    }
    val hasAgent = remember { storage.contact(engine.agentPubkey) != null }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(
                "You", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = Theme.colors.text, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(
                    name = alias ?: "You", url = photoUrl,
                    size = 88.dp, hue = PersonHue.of(engine.accountKey), shape = AvatarShape.CIRCLE,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !uploadingPhoto,
                    ) { Text(if (uploadingPhoto) "Uploading…" else if (photoUrl == null) "Add photo" else "Change photo") }
                    if (photoUrl != null && !uploadingPhoto) {
                        TextButton(onClick = { confirmingRemovePhoto = true }) { Text("Remove photo", color = Theme.colors.destructive) }
                    }
                }
                photoProblem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Theme.colors.destructive, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(12.dp))
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
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Edit, contentDescription = "Edit name", tint = Theme.colors.secondaryText, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    balanceMsat?.let { Sats.format(it) } ?: "–",
                    color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item { SectionHeader("Agent") }
        item {
            Row(
                Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill)
                    .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeelLogo(size = 32.dp)
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

        item { SectionHeader("Stadeum") }
        item { StadeumSection(Stadeum.model) }
        item { Spacer(Modifier.height(24.dp)) }

        item { SectionHeader("Account") }
        item {
            Column(Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill)) {
                AccountRow(label = "Twelve words", onClick = onOpenWords, chevron = true)
                AccountDivider()
                AccountRow(label = "Change PIN", onClick = onOpenChangePin, chevron = true)
                AccountDivider()
                AccountRow(label = "Ask for the PIN", value = PinTimeout.label(pinTimeoutHours), onClick = { editingPinTimeout = true })
                if (biometricsAvailable || biometricOn) {
                    AccountDivider()
                    val everyTime = pinTimeoutHours == 0
                    AccountRow(
                        label = "Biometric unlock", value = if (biometricOn && !everyTime) "On" else "Off",
                        onClick = {
                            if (everyTime) return@AccountRow
                            if (biometricOn) {
                                secretStore.setBiometricEnabled(false)
                                engine.setBiometricEnabled(false)
                                biometricOn = false
                            } else {
                                // Turning it on needs a successful check, not the PIN.
                                val host = context.findActivity() as? androidx.fragment.app.FragmentActivity
                                if (host != null) scope.launch {
                                    if (BiometricUnlock.check(host, "Turn on biometric unlock")) {
                                        secretStore.setBiometricEnabled(true)
                                        engine.setBiometricEnabled(true)
                                        biometricOn = true
                                    }
                                }
                            }
                        },
                    )
                    Text(
                        if (everyTime) "Peel asks for your PIN every time it opens."
                        else "Asks for ${BiometricUnlock.name()} when Peel opens, until it asks for your PIN.",
                        style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                AccountDivider()
                AccountRow(
                    label = "Boost amount", value = "${boostAmountMsat / 1000} sats",
                    onClick = { editingBoostAmount = true },
                )
                AccountDivider()
                AccountRow(
                    label = "Confirm payments",
                    value = if (confirmationMsat == 0L) "Always" else "Over ${Sats.format(confirmationMsat)}",
                    onClick = { editingConfirmation = true },
                )
                AccountDivider()
                var autoDownload by remember { mutableStateOf(engine.autoDownloadsMedia()) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Auto download media", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, modifier = Modifier.weight(1f))
                    Switch(checked = autoDownload, onCheckedChange = {
                        autoDownload = it
                        engine.setAutoDownloadsMedia(it)
                    })
                }
                AccountDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Server", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(
                            engine.serverLabel() ?: "None",
                            style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
                            textAlign = TextAlign.End,
                        )
                        Text(
                            connectionLabel(connection),
                            style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        item { SectionHeader("Debug") }
        item {
            Row(
                Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill)
                    .clickable {
                        // Debug aid, kept visible like iOS's own: proves a
                        // crash reaches whatever reporter is eventually wired.
                        throw RuntimeException("Test crash")
                    }
                    .heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Force test crash", style = MaterialTheme.typography.bodyLarge, color = Theme.colors.accent)
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            Box(
                Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill)
                    .clickable { confirmingForget = true }
                    .heightIn(min = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Forget this account", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    color = Theme.colors.destructive,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmingRemovePhoto) {
        AlertDialog(
            onDismissRequest = { confirmingRemovePhoto = false },
            title = { Text("Remove your photo?") },
            text = { Text("Others see your initials instead, from your next message on.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemovePhoto = false
                    runCatching { engine.setPhotoUrl("") }
                        .onSuccess { photoUrl = null; photoProblem = null }
                        .onFailure { photoProblem = ProfilePhoto.message(it) }
                }) { Text("Remove", color = Theme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemovePhoto = false }) { Text("Cancel") } },
        )
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
        // The amount typed that the person has already been told is over the
        // confirmation limit: saving it again is the confirmation (a payment
        // over the threshold takes a second tap, PeelCore's `setBoostAmount`).
        var askedFor by remember { mutableStateOf<Long?>(null) }
        AlertDialog(
            onDismissRequest = { editingBoostAmount = false },
            title = { Text("Boost amount") },
            text = {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (preset in listOf(10L, 50L, 100L, 500L, 1_000L)) {
                            TextButton(
                                onClick = { draft = preset.toString(); askedFor = null; error = null },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text("$preset", maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                    } else if (PaymentRules.needsConfirmation(sats * 1000, askedFor, confirmationMsat)) {
                        askedFor = sats * 1000
                        error = "Every boost sends ${Sats.format(sats * 1000)}, more than your confirmation limit. Save again to confirm."
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

    if (editingPinTimeout) {
        AlertDialog(
            onDismissRequest = { editingPinTimeout = false },
            title = { Text("Ask for the PIN") },
            text = {
                Column {
                    Text(
                        "Peel remembers your PIN for this long after you last used it, and opens without asking.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    (PinTimeout.OPTIONS + PinTimeout.NEVER).forEach { hours ->
                        TextButton(onClick = {
                            engine.setPinTimeout(hours)
                            secretStore.remembered?.timeoutChanged(hours)
                            pinTimeoutHours = hours
                            editingPinTimeout = false
                        }) { Text(PinTimeout.label(hours) + if (hours == pinTimeoutHours) "  ✓" else "") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editingPinTimeout = false }) { Text("Cancel") } },
        )
    }

    if (editingConfirmation) {
        val choices = listOf(0L, 100L, 1_000L, 10_000L).map { it * 1000 }.let { if (confirmationMsat in it) it else it + confirmationMsat }
        AlertDialog(
            onDismissRequest = { editingConfirmation = false },
            title = { Text("Confirm payments") },
            text = {
                Column {
                    Text(
                        "Payments and boosts over this amount ask you to confirm first.",
                        style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
                    )
                    for (msat in choices) {
                        TextButton(onClick = {
                            engine.setPaymentConfirmation(msat)
                            confirmationMsat = msat
                            editingConfirmation = false
                        }) {
                            Text(
                                (if (msat == 0L) "Always" else "Over ${Sats.format(msat)}") + if (msat == confirmationMsat) "  ✓" else "",
                                color = Theme.colors.text,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editingConfirmation = false }) { Text("Cancel") } },
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
                        // Best effort, before cancel and any wipe (no engine exists after):
                        // drop this install's token on the server. Offline, a failure or a
                        // timeout is only logged and never blocks the forget.
                        val unregistered = runCatching {
                            kotlinx.coroutines.withTimeoutOrNull(3_000) { engine.unregisterPushToken() }
                        }.getOrNull()
                        if (unregistered != true) android.util.Log.d("YouScreen", "server push unregister skipped or failed")
                        engine.cancel()
                        // The Stadeum pairing goes with the account.
                        Stadeum.model.disconnect()
                        // Both stores, not just the mnemonic: otherwise the
                        // next account made on this device would start from
                        // this one's coreState (Storage.kt, `deleteAccount`).
                        withContext(Dispatchers.Default) {
                            storage.deleteAccount()
                            com.sphinx.peel.push.PushNameStore.forContext(appContext).clear()
                            com.sphinx.peel.push.PushRegistrar.accountForgotten(appContext)
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
    is ConnectionState.Offline -> "Offline, retrying in ${connection.retryInSeconds}s…"
    is ConnectionState.Failed -> connection.message
}


@Composable
private fun SectionHeader(title: String) {
    Text(
        title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = Theme.colors.secondaryText, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
    )
}

@Composable
private fun AccountRow(label: String, value: String = "", onClick: (() -> Unit)? = null, chevron: Boolean = false, upDown: Boolean = false) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(
                value, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText,
                textAlign = TextAlign.End,
            )
        }
        if (upDown) {
            Icon(
                Icons.Filled.UnfoldMore, contentDescription = null,
                tint = Theme.colors.secondaryText, modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = Theme.colors.secondaryText, modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun AccountDivider() {
    // Inset, not full-bleed, like iOS's own list separators.
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Theme.colors.divider, thickness = 1.dp)
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(1.dp, Theme.colors.inputBorder, CircleShape)
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Theme.colors.text)
    }
}

@Composable
private fun BackButtonRow(onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/// Back button, then a large bold left-aligned title and a muted subtitle.
@Composable
private fun RouteHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BackButtonRow(onBack)
        Spacer(Modifier.height(24.dp))
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Theme.colors.text)
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.secondaryText)
        }
    }
}

/// A full-width pill button. `border` for the outlined variants.
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier.fillMaxWidth(),
    border: Color? = null,
    enabled: Boolean = true,
) {
    Box(
        modifier.heightIn(min = 52.dp).clip(CircleShape).background(container)
            .then(if (border != null) Modifier.border(1.dp, border, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = content.copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}

/// Old PIN, new PIN, the new one again, on the keypad — PeelCore's own
/// `ChangePIN`: a wrong *old* PIN is only ever discovered at the very end
/// (`SecretStore.changePin` tries to open the sealed words with it first),
/// matching that screen's own shape rather than checking it a step early.
private enum class ChangePinStep { OLD, NEW, CONFIRM }

@Composable
private fun ChangePinScreen(secretStore: SecretStore, onBack: () -> Unit) {
    SecureWindow()
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

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        BackButtonRow(onBack)
        when (step) {
            ChangePinStep.OLD -> PinPrompt(
                title = "Enter your current PIN", detail = note, pin = old,
                onPinChange = { old = it }, onComplete = { note = null; step = ChangePinStep.NEW },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            ChangePinStep.NEW -> PinPrompt(
                title = "Choose a new PIN", detail = note, pin = new,
                onPinChange = { new = it }, onComplete = { note = null; step = ChangePinStep.CONFIRM },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            ChangePinStep.CONFIRM -> PinPrompt(
                title = "Enter the new PIN again", detail = note, pin = confirmation, working = working,
                onPinChange = { confirmation = it },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
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

private enum class WordsStage { INTRO, PIN, WORDS }

/// The twelve words: an intro with "Show words", the PIN, then the words
/// masked behind "Reveal", hidden again after 30 s (counted down on screen),
/// on "Hide", or when this screen/app leaves the foreground — PeelCore's own
/// `WordsScreen`, trimmed: no Face ID (this port has none yet), no "check
/// words" flow (every account here already has `WORDS_CONFIRMED` by the
/// time it exists — a fresh signup's own mandatory 3-word check, or a
/// restore's own stronger "typed all twelve" proof — so the row this screen
/// opens from never shows the "not checked" branch iOS's own version still
/// has to handle).
@Composable
private fun TwelveWordsScreen(secretStore: SecretStore, onBack: () -> Unit) {
    val host = androidx.compose.ui.platform.LocalContext.current.findActivity() as? androidx.fragment.app.FragmentActivity
    val shownForSeconds = 30
    var stage by remember { mutableStateOf(WordsStage.INTRO) }
    var pin by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var visible by remember { mutableStateOf(false) }
    var revealedAt by remember { mutableStateOf(0L) }
    var remaining by remember { mutableStateOf(shownForSeconds) }
    val scope = rememberCoroutineScope()
    SecureWindow()

    fun hide() {
        words = emptyList()
        visible = false
        pin = ""
        problem = null
        revealedAt = 0L
        stage = WordsStage.PIN
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
        remaining = shownForSeconds
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        hide()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        when (stage) {
            WordsStage.INTRO -> {
                RouteHeader("Twelve words", "Checked against your paper.", onBack)
                Spacer(Modifier.weight(1f))
                PillButton(
                    "Show words", onClick = { stage = WordsStage.PIN },
                    container = Theme.colors.accent.copy(alpha = 0.25f), content = Theme.colors.text,
                )
                Spacer(Modifier.height(24.dp))
            }
            WordsStage.PIN -> {
                BackButtonRow(onBack = { hide(); stage = WordsStage.INTRO }) {
                    PillButton(
                        "Cancel", onClick = { hide(); stage = WordsStage.INTRO },
                        container = Color.Transparent, content = Theme.colors.accent,
                        modifier = Modifier, border = Theme.colors.inputBorder,
                    )
                }
                PinPrompt(
                    title = "Enter your PIN", detail = problem ?: "To show your twelve words.",
                    pin = pin, onPinChange = { pin = it }, working = working,
                    onComplete = {
                        working = true
                        scope.launch {
                            try {
                                words = withContext(Dispatchers.Default) {
                                    val chars = secretStore.loadMnemonicChars(pin)
                                    try { Account.wordsIn(String(chars)) } finally { chars.fill('\u0000') }
                                }
                                visible = false
                                problem = null
                                stage = WordsStage.WORDS
                                revealedAt = System.currentTimeMillis()
                            } catch (e: SecretStoreFailure.LockedOut) {
                                problem = "Too many wrong PINs. Try again in ${LockoutPolicy.describe(e.remainingMs)}."
                                pin = ""
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
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 40.dp),
                )
            }
            WordsStage.WORDS -> {
                RouteHeader(
                    "Your twelve words",
                    "Anyone with these words controls your account. Keep them on paper.",
                    onBack = { hide(); stage = WordsStage.INTRO },
                )
                Spacer(Modifier.height(20.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    words.chunked(2).forEachIndexed { rowIndex, pair ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEachIndexed { col, word ->
                                Row(
                                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                        .border(1.dp, Theme.colors.inputBorder, RoundedCornerShape(16.dp))
                                        .padding(horizontal = 14.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${rowIndex * 2 + col + 1}", color = Theme.colors.secondaryText,
                                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(28.dp),
                                    )
                                    Text(
                                        if (visible) word else "••••••",
                                        color = if (visible) Theme.colors.text else Theme.colors.secondaryText,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "Hides in $remaining s", style = MaterialTheme.typography.bodySmall,
                        color = Theme.colors.secondaryText, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                PillButton(
                    "Reveal", onClick = { visible = true },
                    container = Theme.colors.accent, content = Theme.colors.buttonText,
                )
                Spacer(Modifier.height(10.dp))
                PillButton(
                    "Hide", onClick = { hide() },
                    container = Theme.colors.accent.copy(alpha = 0.25f), content = Theme.colors.text,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/// You › Stadeum (iOS `StadeumSection`): Connect Stadeum (a code to approve
/// there, its page opened in the browser), each workspace paired, Connect
/// again once Stadeum no longer knows this phone, and Disconnect.
@Composable
private fun StadeumSection(model: StadeumModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val status = model.status
    val link = model.link
    val problem = model.problem
    fun connect() = model.connect(android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android")
    fun open(url: String) {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    // The approval page opens by itself once a code is ready; Open Stadeum opens it again.
    val pairing = (status as? StadeumModel.Status.Pairing)?.pairing
    LaunchedEffect(pairing?.userCode) { pairing?.let { open(it.verificationUriComplete) } }

    Column(Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill)) {
        when (status) {
            StadeumModel.Status.Off -> {
                StadeumNote("Open what your workspaces' assistants make for you, and their apps, here on this phone.")
                if (model.reconnectHint) {
                    StadeumNote("Your account is back, but not its Stadeum connection. Connect Stadeum again.", Theme.colors.attention)
                }
                AccountDivider()
                StadeumButton("Connect Stadeum", Theme.colors.accent, ::connect)
            }
            is StadeumModel.Status.Pairing -> {
                StadeumNote("Approve this phone in Stadeum. Check the code matches:")
                Text(
                    status.pairing.userCode, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Theme.colors.text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                AccountDivider()
                StadeumButton("Open Stadeum", Theme.colors.accent) { open(status.pairing.verificationUriComplete) }
                AccountDivider()
                StadeumButton("Cancel", Theme.colors.secondaryText) { model.cancelPairing() }
            }
            StadeumModel.Status.Connected, StadeumModel.Status.NeedsPairing -> {
                if (status == StadeumModel.Status.NeedsPairing) {
                    StadeumNote("Stadeum no longer knows this phone. Connect it again to open your workspaces' work here.", Theme.colors.attention)
                    StadeumButton("Connect again", Theme.colors.accent, ::connect)
                    AccountDivider()
                }
                link?.peers?.forEach { peer ->
                    AccountRow(label = peer.name, value = "@" + peer.id)
                    AccountDivider()
                }
                link?.errors?.forEach { error ->
                    AccountRow(label = error.name, value = error.reason)
                    AccountDivider()
                }
                if (model.identified && status == StadeumModel.Status.Connected) {
                    StadeumNote(Stadeum.IDENTIFIED_LINE)
                    AccountDivider()
                }
                StadeumButton("Disconnect", Theme.colors.destructive) { model.disconnect() }
            }
        }
    }
    if (problem != null) {
        Text(
            problem, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.attention,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp),
        )
    }
}

@Composable
private fun StadeumNote(text: String, color: Color = Theme.colors.secondaryText) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
}

@Composable
private fun StadeumButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 16.dp),
    )
}
