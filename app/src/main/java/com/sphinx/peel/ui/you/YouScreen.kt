package com.sphinx.peel.ui.you

import androidx.activity.compose.rememberLauncherForActivityResult
import com.sphinx.peel.ui.str
import com.sphinx.peel.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.sphinx.peel.ui.payments.QrCodeImage
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
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
import com.sphinx.peel.ui.voice.labelRes
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.account.LockoutPolicy
import com.sphinx.peel.account.PinTimeout
import com.sphinx.peel.account.SecretStoreFailure
import com.sphinx.peel.engine.ConnectedAppRow
import com.sphinx.peel.engine.ConnectionState
import com.sphinx.peel.engine.OverBudget
import com.sphinx.peel.engine.WalletConnection
import androidx.compose.foundation.lazy.items
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.ProfilePhoto
import com.sphinx.peel.ui.components.prepareImageForSending
import com.sphinx.peel.engine.PaymentRules
import com.sphinx.peel.engine.Sats
import com.sphinx.peel.engine.Stadeum
import com.sphinx.peel.engine.StadeumModel
import com.sphinx.peel.engine.BoxLink
import com.sphinx.peel.engine.Boxes
import com.sphinx.peel.engine.BoxesModel
import com.sphinx.peel.engine.BoxWords
import androidx.compose.foundation.combinedClickable
import com.sphinx.peel.engine.paymentConfirmationMsat
import com.sphinx.peel.engine.setPaymentConfirmation
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.engine.PageLocation
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.SecureWindow
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.AgentTag
import com.sphinx.peel.ui.components.PeelLogo
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.components.PinPrompt
import com.sphinx.peel.ui.components.WordCheck
import com.sphinx.peel.ui.components.WordCheckBoxes
import com.sphinx.peel.ui.findActivity
import com.sphinx.peel.ui.lock.BiometricUnlock
import com.sphinx.peel.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class YouRoute { WORDS, CHANGE_PIN, DIAGNOSTICS, CONNECTED_APPS, SITE_LOCATION }

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
        YouRoute.WORDS -> TwelveWordsScreen(secretStore, storage, onBack = { route = null })
        YouRoute.CHANGE_PIN -> ChangePinScreen(secretStore, onBack = { route = null })
        YouRoute.DIAGNOSTICS -> com.sphinx.peel.ui.diagnostics.DiagnosticsScreen(onBack = { route = null })
        YouRoute.CONNECTED_APPS -> ConnectedAppsScreen(storage, onBack = { route = null })
        YouRoute.SITE_LOCATION -> SiteLocationScreen(storage, onBack = { route = null })
        null -> YouMain(
            engine, storage, secretStore, onForgotten,
            onOpenWords = { route = YouRoute.WORDS },
            onOpenChangePin = { route = YouRoute.CHANGE_PIN },
            onOpenDiagnostics = { route = YouRoute.DIAGNOSTICS },
            onOpenConnectedApps = { route = YouRoute.CONNECTED_APPS },
            onOpenSiteLocation = { route = YouRoute.SITE_LOCATION },
        )
    }
}

private val CardShape = RoundedCornerShape(24.dp)

/// "Your contact info" (the Mac app's Account › Profile card): a QR and a copy
/// button for the full `<pubkey>_<lsp>_<scid>`; never a bare pubkey.
@Composable
private fun ContactInfoCard(contactInfo: String?) {
    val clipboard = LocalClipboardManager.current
    val toast = LocalToast.current
    val copiedText = stringResource(R.string.misc_copied)
    Box(Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill).padding(16.dp)) {
        if (contactInfo == null) {
            Column {
                Text(stringResource(R.string.misc_you_contact_info), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.misc_you_contact_info_not_ready), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
            }
            return@Box
        }
        val qr: @Composable () -> Unit = { QrCodeImage(contactInfo, 120.dp, stringResource(R.string.misc_you_contact_info_qr)) }
        val text: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                Text(stringResource(R.string.misc_you_contact_info), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(contactInfo))
                    toast.show(copiedText, ToastType.Success)
                }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.misc_you_contact_info_copy), fontSize = 14.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 320.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    qr()
                    Spacer(Modifier.height(12.dp))
                    text(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    qr()
                    Spacer(Modifier.width(16.dp))
                    text(Modifier.weight(1f))
                }
            }
        }
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
    onOpenDiagnostics: () -> Unit,
    onOpenConnectedApps: () -> Unit,
    onOpenSiteLocation: () -> Unit,
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
    val agentName by engine.agentNameFlow.collectAsState()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(
                stringResource(R.string.misc_you_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = Theme.colors.text, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(
                    name = alias ?: stringResource(R.string.misc_you_title), url = photoUrl,
                    size = 88.dp, hue = PersonHue.of(engine.accountKey), shape = AvatarShape.CIRCLE,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !uploadingPhoto,
                    ) { Text(if (uploadingPhoto) stringResource(R.string.misc_you_uploading) else if (photoUrl == null) stringResource(R.string.misc_you_add_photo) else stringResource(R.string.misc_you_change_photo)) }
                    if (photoUrl != null && !uploadingPhoto) {
                        TextButton(onClick = { confirmingRemovePhoto = true }) { Text(stringResource(R.string.misc_you_remove_photo), color = Theme.colors.destructive) }
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
                        alias ?: stringResource(R.string.misc_you_set_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (alias != null) Theme.colors.text else Theme.colors.secondaryText,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.misc_you_edit_name), tint = Theme.colors.secondaryText, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    balanceMsat?.let { Sats.format(it) } ?: "–",
                    color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            ContactInfoCard(YouOptions.contactInfoToShare(storage.value(Setting.CONTACT_INFO)))
            Spacer(Modifier.height(24.dp))
        }

        item { SectionHeader(stringResource(R.string.misc_you_agent)) }
        item {
            Row(
                Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill)
                    .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeelLogo(size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Text(agentName, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                Spacer(Modifier.width(8.dp))
                AgentTag()
                Spacer(Modifier.weight(1f))
                Text(
                    // This port has no agent-setup flow of its own (bridge,
                    // device request) — just whether the shared account
                    // already has the agent's chat as a contact, set up
                    // from wherever that flow does exist.
                    if (hasAgent) stringResource(R.string.misc_you_ready) else stringResource(R.string.misc_you_agent_setup),
                    color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item { SectionHeader(stringResource(R.string.voice_setting)) }
        item { VoiceModeSection(appContext) }
        item { Spacer(Modifier.height(24.dp)) }

        item { SectionHeader("Stadeum") }
        item { StadeumSection(Stadeum.model) }
        item { Spacer(Modifier.height(24.dp)) }
        item { BoxesSection(Boxes.model) }

        item { SectionHeader(stringResource(R.string.misc_you_account)) }
        item {
            Column(Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill)) {
                // Read again each time You shows (it leaves composition while Twelve words is open).
                val wordsChecked = remember { Account.wordsChecked(storage.value(Setting.WORDS_CONFIRMED)) }
                AccountRow(
                    label = stringResource(R.string.misc_you_twelve_words), value = if (wordsChecked) "" else stringResource(R.string.misc_you_not_checked_yet),
                    onClick = onOpenWords, chevron = true,
                )
                AccountDivider()
                AccountRow(label = stringResource(R.string.misc_you_change_pin), onClick = onOpenChangePin, chevron = true)
                AccountDivider()
                AccountRow(label = stringResource(R.string.misc_you_ask_pin), value = PinTimeout.label(pinTimeoutHours), onClick = { editingPinTimeout = true })
                if (biometricsAvailable || biometricOn) {
                    AccountDivider()
                    val everyTime = pinTimeoutHours == 0
                    AccountRow(
                        label = stringResource(R.string.misc_you_biometric), value = if (biometricOn && !everyTime) stringResource(R.string.misc_you_on) else stringResource(R.string.misc_you_off),
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
                                    if (BiometricUnlock.check(host, context.str(R.string.misc_you_biometric_prompt))) {
                                        secretStore.setBiometricEnabled(true)
                                        engine.setBiometricEnabled(true)
                                        biometricOn = true
                                    }
                                }
                            }
                        },
                    )
                    Text(
                        if (everyTime) stringResource(R.string.misc_you_pin_every_time)
                        else stringResource(R.string.misc_you_biometric_note, BiometricUnlock.name()),
                        style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                AccountDivider()
                AccountRow(
                    label = stringResource(R.string.misc_you_boost_amount), value = "${boostAmountMsat / 1000} sats",
                    onClick = { editingBoostAmount = true },
                )
                AccountDivider()
                AccountRow(
                    label = stringResource(R.string.misc_you_confirm_payments),
                    value = if (confirmationMsat == 0L) stringResource(R.string.misc_always) else stringResource(R.string.misc_you_over, Sats.format(confirmationMsat)),
                    onClick = { editingConfirmation = true },
                )
                AccountDivider()
                val walletVersion by storage.walletConnectionsVersion.collectAsState()
                AccountRow(
                    label = stringResource(R.string.misc_you_connected_apps), value = ConnectedAppRow.summary(remember(walletVersion) { storage.walletConnections().size }),
                    onClick = onOpenConnectedApps, chevron = true,
                )
                AccountDivider()
                val siteVersion by storage.siteLocationsVersion.collectAsState()
                AccountRow(
                    label = stringResource(R.string.misc_you_site_location), value = remember(siteVersion) { storage.siteLocations().size }.let { if (it == 0) stringResource(R.string.misc_none) else "$it" },
                    onClick = onOpenSiteLocation, chevron = true,
                )
                AccountDivider()
                var autoDownload by remember { mutableStateOf(engine.autoDownloadsMedia()) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.misc_you_auto_download), style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, modifier = Modifier.weight(1f))
                    Switch(checked = autoDownload, onCheckedChange = {
                        autoDownload = it
                        engine.setAutoDownloadsMedia(it)
                    })
                }
                Text(
                    stringResource(R.string.misc_you_auto_download_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = Theme.colors.secondaryText,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                )
                AccountDivider()
                AccountRow(label = stringResource(R.string.misc_you_diagnostics), onClick = onOpenDiagnostics, chevron = true)
                AccountDivider()
                UpdateRow()
                AccountDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.misc_you_server), style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(
                            engine.serverLabel() ?: stringResource(R.string.misc_none),
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
                    stringResource(R.string.misc_you_forget_account), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    color = Theme.colors.destructive,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmingRemovePhoto) {
        AlertDialog(
            onDismissRequest = { confirmingRemovePhoto = false },
            title = { Text(stringResource(R.string.misc_you_remove_photo_title)) },
            text = { Text(stringResource(R.string.misc_you_remove_photo_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemovePhoto = false
                    runCatching { engine.setPhotoUrl("") }
                        .onSuccess { photoUrl = null; photoProblem = null }
                        .onFailure { photoProblem = ProfilePhoto.message(it) }
                }) { Text(stringResource(R.string.misc_remove), color = Theme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemovePhoto = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (editingName) {
        var draft by remember { mutableStateOf(alias ?: "") }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text(stringResource(R.string.misc_you_your_name)) },
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
                }) { Text(stringResource(R.string.misc_save)) }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text(stringResource(R.string.common_cancel)) } },
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
            title = { Text(stringResource(R.string.misc_you_boost_amount)) },
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
                        error = context.str(R.string.misc_you_boost_error_whole)
                    } else if (PaymentRules.needsConfirmation(sats * 1000, askedFor, confirmationMsat)) {
                        askedFor = sats * 1000
                        error = context.str(R.string.misc_you_boost_error_limit, Sats.format(sats * 1000))
                    } else {
                        boostAmountMsat = sats * 1000
                        editingBoostAmount = false
                        scope.launch {
                            withContext(Dispatchers.Default) { engine.setBoostAmount(sats) }
                        }
                    }
                }) { Text(stringResource(R.string.misc_save)) }
            },
            dismissButton = { TextButton(onClick = { editingBoostAmount = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (editingPinTimeout) {
        AlertDialog(
            onDismissRequest = { editingPinTimeout = false },
            title = { Text(stringResource(R.string.misc_you_ask_pin)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.misc_you_pin_timeout_body),
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
            dismissButton = { TextButton(onClick = { editingPinTimeout = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (editingConfirmation) {
        val choices = listOf(0L, 100L, 1_000L, 10_000L).map { it * 1000 }.let { if (confirmationMsat in it) it else it + confirmationMsat }
        AlertDialog(
            onDismissRequest = { editingConfirmation = false },
            title = { Text(stringResource(R.string.misc_you_confirm_payments)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.misc_you_confirm_body),
                        style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
                    )
                    for (msat in choices) {
                        TextButton(onClick = {
                            engine.setPaymentConfirmation(msat)
                            confirmationMsat = msat
                            editingConfirmation = false
                        }) {
                            Text(
                                (if (msat == 0L) stringResource(R.string.misc_always) else stringResource(R.string.misc_you_over, Sats.format(msat))) + if (msat == confirmationMsat) "  ✓" else "",
                                color = Theme.colors.text,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editingConfirmation = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (confirmingForget) {
        AlertDialog(
            onDismissRequest = { confirmingForget = false },
            title = { Text(stringResource(R.string.misc_you_forget_title)) },
            text = {
                Text(
                    stringResource(R.string.misc_you_forget_body),
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
                        // The Stadeum pairing goes with the account, and so do the boxes linked to its key (docs/phases/45-box-link.md).
                        Stadeum.model.disconnect()
                        Boxes.model.forgetAll()
                        // The public sites' kept sign-ins too (docs/phases/42-page-host-logs.md), on the main thread.
                        com.sphinx.peel.ui.apps.forgetKeptWebData()
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
                }) { Text(stringResource(R.string.misc_you_forget_it), color = Theme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmingForget = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun connectionLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.Idle -> stringResource(R.string.misc_you_conn_idle)
    ConnectionState.Connecting -> stringResource(R.string.misc_you_conn_connecting)
    ConnectionState.Connected -> stringResource(R.string.misc_you_conn_connected)
    is ConnectionState.Offline -> stringResource(R.string.misc_you_conn_offline, connection.retryInSeconds)
    is ConnectionState.Failed -> connection.message
}


@Composable
private fun SectionHeader(title: String) {
    Text(
        title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = Theme.colors.secondaryText, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
    )
}

/// "Check for updates" (docs/phases/53-in-app-updates.md): the installed version, then the result of a tap. The update prompt
/// itself is `UpdateHost`'s; an available version opens it again from here.
@Composable
private fun UpdateRow() {
    val controller = com.sphinx.peel.update.Updates.get(androidx.compose.ui.platform.LocalContext.current)
    val state by controller.state.collectAsState()
    AccountRow(
        label = "Check for updates",
        value = com.sphinx.peel.update.UpdateText.rowValue(state, com.sphinx.peel.BuildConfig.VERSION_NAME),
        onClick = { if (state is com.sphinx.peel.update.UpdateState.Available) controller.showPrompt() else controller.checkNow() },
    )
    com.sphinx.peel.update.UpdateText.rowDetail(state)?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
    }
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
internal fun BackButton(onBack: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(1.dp, Theme.colors.inputBorder, CircleShape)
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Theme.colors.text)
    }
}

@Composable
internal fun BackButtonRow(onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
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

    val strCtx = androidx.compose.ui.platform.LocalContext.current
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
                title = stringResource(R.string.misc_you_pin_old), detail = note, pin = old,
                onPinChange = { old = it }, onComplete = { note = null; step = ChangePinStep.NEW },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            ChangePinStep.NEW -> PinPrompt(
                title = stringResource(R.string.misc_you_pin_new), detail = note, pin = new,
                onPinChange = { new = it }, onComplete = { note = null; step = ChangePinStep.CONFIRM },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
            ChangePinStep.CONFIRM -> PinPrompt(
                title = stringResource(R.string.misc_you_pin_again), detail = note, pin = confirmation, working = working,
                onPinChange = { confirmation = it },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                onComplete = {
                    if (confirmation != new) {
                        reset(ChangePinStep.NEW, strCtx.str(R.string.misc_you_pin_mismatch))
                        return@PinPrompt
                    }
                    working = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.Default) { secretStore.changePin(old, new) }
                            onBack()
                        } catch (e: SecretStoreFailure.WrongPin) {
                            reset(ChangePinStep.OLD, strCtx.str(R.string.misc_you_wrong_pin))
                        } catch (e: Exception) {
                            reset(ChangePinStep.NEW, strCtx.str(R.string.misc_you_pin_change_failed_again, e.message ?: strCtx.str(R.string.misc_you_pin_change_failed)))
                        }
                        working = false
                    }
                },
            )
        }
    }
}

private enum class WordsStage { INTRO, PIN, WORDS, CHECK }

/// The twelve words: an intro with "Show words", the PIN, then the words
/// masked behind "Reveal", hidden again after 30 s (counted down on screen),
/// on "Hide", or when this screen/app leaves the foreground — PeelCore's own
/// `WordsScreen`, without Face ID. A signup that skipped onboarding's check
/// ("Back up later", "Skip for now") is "Not checked against your paper yet":
/// "Check words" asks the PIN, then three of the words, as iOS does.
@Composable
private fun TwelveWordsScreen(secretStore: SecretStore, storage: Storage, onBack: () -> Unit) {
    val strCtx = androidx.compose.ui.platform.LocalContext.current
    val host = strCtx.findActivity() as? androidx.fragment.app.FragmentActivity
    val shownForSeconds = 30
    var stage by remember { mutableStateOf(WordsStage.INTRO) }
    var pin by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var visible by remember { mutableStateOf(false) }
    var revealedAt by remember { mutableStateOf(0L) }
    var remaining by remember { mutableStateOf(shownForSeconds) }
    var checked by remember { mutableStateOf(Account.wordsChecked(storage.value(Setting.WORDS_CONFIRMED))) }
    // After the PIN: three words asked back, not the twelve shown.
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf(WordCheck()) }
    val scope = rememberCoroutineScope()
    SecureWindow()

    // A check that ends (passed, or left) goes back to the intro; shown words go back to the PIN.
    fun hide() {
        words = emptyList()
        visible = false
        pin = ""
        problem = null
        revealedAt = 0L
        check = WordCheck()
        stage = if (checking) WordsStage.INTRO else WordsStage.PIN
        checking = false
    }

    fun start(toCheck: Boolean) {
        checking = toCheck
        pin = ""
        problem = null
        stage = WordsStage.PIN
    }

    fun confirm() {
        if (!check.isFilled) return
        val wrong = Account.wrongPlaces(check.byPosition, words)
        if (wrong.isEmpty()) {
            storage.apply(Storage.Commit(settings = mapOf(Setting.WORDS_CONFIRMED to "1")))
            checked = true
            hide()
        } else {
            problem = Account.wrongPlacesProblem(wrong)
            check = WordCheck()
        }
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
                RouteHeader(
                    stringResource(R.string.misc_you_twelve_words),
                    if (checked) stringResource(R.string.misc_you_words_checked) else stringResource(R.string.misc_you_words_unchecked),
                    onBack,
                )
                Spacer(Modifier.weight(1f))
                if (!checked) {
                    PillButton(
                        stringResource(R.string.misc_you_check_words), onClick = { start(toCheck = true) },
                        container = Theme.colors.accent, content = Theme.colors.buttonText,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                PillButton(
                    stringResource(R.string.misc_you_show_words), onClick = { start(toCheck = false) },
                    container = Theme.colors.accent.copy(alpha = 0.25f), content = Theme.colors.text,
                )
                Spacer(Modifier.height(24.dp))
            }
            WordsStage.PIN -> {
                BackButtonRow(onBack = { hide(); stage = WordsStage.INTRO }) {
                    PillButton(
                        stringResource(R.string.common_cancel), onClick = { hide(); stage = WordsStage.INTRO },
                        container = Color.Transparent, content = Theme.colors.accent,
                        modifier = Modifier, border = Theme.colors.inputBorder,
                    )
                }
                PinPrompt(
                    title = stringResource(R.string.misc_you_enter_pin),
                    detail = problem ?: if (checking) stringResource(R.string.misc_you_pin_to_check) else stringResource(R.string.misc_you_pin_to_show),
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
                                if (checking) {
                                    pin = ""
                                    check = WordCheck()
                                    stage = WordsStage.CHECK
                                } else {
                                    stage = WordsStage.WORDS
                                    revealedAt = System.currentTimeMillis()
                                }
                            } catch (e: SecretStoreFailure.LockedOut) {
                                problem = strCtx.str(R.string.misc_you_too_many_pins, LockoutPolicy.describe(e.remainingMs))
                                pin = ""
                            } catch (e: SecretStoreFailure.WrongPin) {
                                problem = strCtx.str(R.string.misc_you_wrong_pin)
                                pin = ""
                            } catch (e: Exception) {
                                problem = e.message ?: strCtx.str(R.string.misc_you_could_not_open)
                                pin = ""
                            }
                            working = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 40.dp),
                )
            }
            WordsStage.CHECK -> {
                RouteHeader(stringResource(R.string.misc_you_check_written), stringResource(R.string.misc_you_check_three), onBack = { hide() })
                Spacer(Modifier.height(20.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    WordCheckBoxes(check = check, onSubmit = ::confirm)
                    problem?.let {
                        Text(
                            it, color = Theme.colors.destructive, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                PillButton(
                    stringResource(R.string.misc_you_check), onClick = ::confirm, enabled = check.isFilled,
                    container = Theme.colors.accent, content = Theme.colors.buttonText,
                )
                Spacer(Modifier.height(24.dp))
            }
            WordsStage.WORDS -> {
                RouteHeader(
                    stringResource(R.string.misc_you_your_twelve),
                    stringResource(R.string.misc_you_words_warning),
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
                        stringResource(R.string.misc_you_hides_in, remaining), style = MaterialTheme.typography.bodySmall,
                        color = Theme.colors.secondaryText, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                PillButton(
                    stringResource(R.string.misc_you_reveal), onClick = { visible = true },
                    container = Theme.colors.accent, content = Theme.colors.buttonText,
                )
                Spacer(Modifier.height(10.dp))
                PillButton(
                    stringResource(R.string.misc_you_hide), onClick = { hide() },
                    container = Theme.colors.accent.copy(alpha = 0.25f), content = Theme.colors.text,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/// You › Voice Mode, its own section (docs/phases/55-voice-mode.md): the setting, which interaction mode, and subtitles
/// once a speech-out mode is picked.
@Composable
private fun VoiceModeSection(context: android.content.Context) {
    val voicePrefs = remember { com.sphinx.peel.engine.VoiceModePrefs.of(context) }
    var voiceMode by remember { mutableStateOf(voicePrefs.enabled) }
    var interaction by remember { mutableStateOf(voicePrefs.interaction) }
    var subtitles by remember { mutableStateOf(voicePrefs.subtitles) }
    var pickingInteraction by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.voice_setting), style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, modifier = Modifier.weight(1f))
            Switch(checked = voiceMode, onCheckedChange = { voiceMode = it; voicePrefs.enabled = it })
        }
        Text(
            stringResource(R.string.voice_setting_note),
            style = MaterialTheme.typography.labelSmall,
            color = Theme.colors.secondaryText,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
        AccountRow(
            label = stringResource(R.string.voice_interaction_label), value = stringResource(interaction.labelRes()),
            onClick = { pickingInteraction = true },
        )
        androidx.compose.material3.DropdownMenu(expanded = pickingInteraction, onDismissRequest = { pickingInteraction = false }) {
            for (mode in com.sphinx.peel.engine.VoiceModeInteraction.entries) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(mode.labelRes())) },
                    onClick = { interaction = mode; voicePrefs.interaction = mode; pickingInteraction = false },
                )
            }
        }
        if (interaction.speaksReply) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.voice_subtitles), style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                    Text(stringResource(R.string.voice_subtitles_note), style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
                }
                Switch(checked = subtitles, onCheckedChange = { subtitles = it; voicePrefs.subtitles = it })
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
                StadeumNote(stringResource(R.string.misc_you_stadeum_intro))
                if (model.reconnectHint) {
                    StadeumNote(stringResource(R.string.misc_you_stadeum_lost), Theme.colors.attention)
                }
                AccountDivider()
                StadeumButton(stringResource(R.string.misc_you_stadeum_connect), Theme.colors.accent, ::connect)
            }
            is StadeumModel.Status.Pairing -> {
                StadeumNote(stringResource(R.string.misc_you_stadeum_approve))
                Text(
                    status.pairing.userCode, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Theme.colors.text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                AccountDivider()
                StadeumButton(stringResource(R.string.misc_you_stadeum_open), Theme.colors.accent) { open(status.pairing.verificationUriComplete) }
                AccountDivider()
                StadeumButton(stringResource(R.string.common_cancel), Theme.colors.secondaryText) { model.cancelPairing() }
            }
            StadeumModel.Status.Connected, StadeumModel.Status.NeedsPairing -> {
                if (status == StadeumModel.Status.NeedsPairing) {
                    StadeumNote(stringResource(R.string.misc_you_stadeum_unknown), Theme.colors.attention)
                    StadeumButton(stringResource(R.string.misc_you_connect_again), Theme.colors.accent, ::connect)
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
                StadeumButton(stringResource(R.string.misc_you_disconnect), Theme.colors.destructive) { model.disconnect() }
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

/// You › Boxes (iOS `BoxesSection`, docs/phases/45-box-link.md): the strut boxes this account is linked to, each as its member. A tap
/// opens the box's own strut UI as them, where its apps are ([BoxesModel.page]); a long press offers Forget. Shown once there is
/// something to show: a box is linked from its own Connect, by its QR code or Open in Peel.
@Composable
private fun BoxesSection(model: BoxesModel) {
    val links = model.links
    val linking = model.linking
    val lines = listOfNotNull(model.notice, model.problem)
    if (links.isEmpty() && linking == null && lines.isEmpty()) return
    val openPage = com.sphinx.peel.ui.apps.LocalPageOpener.current
    val scope = rememberCoroutineScope()
    // The box whose page is being opened, and the one whose menu is open.
    var opening by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    fun open(box: BoxLink) {
        opening = box.url
        scope.launch {
            try { model.page(box)?.let(openPage) } finally { opening = null }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.misc_you_boxes))
        if (links.isNotEmpty() || linking != null) {
            Column(Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill)) {
                links.forEachIndexed { i, box ->
                    if (i > 0) AccountDivider()
                    Box {
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    enabled = opening == null,
                                    onClickLabel = stringResource(R.string.misc_you_box_open, box.host, box.person),
                                    onLongClickLabel = stringResource(R.string.misc_forget),
                                    onLongClick = { menuFor = box.url },
                                    onClick = { open(box) },
                                )
                                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(box.host, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text, modifier = Modifier.weight(1f))
                            if (opening == box.url) {
                                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(box.person, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText, textAlign = TextAlign.End)
                            }
                        }
                        DropdownMenu(expanded = menuFor == box.url, onDismissRequest = { menuFor = null }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.misc_forget), color = Theme.colors.destructive) },
                                onClick = { menuFor = null; model.forget(box) },
                            )
                        }
                    }
                }
                if (linking != null) {
                    if (links.isNotEmpty()) AccountDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.misc_you_linking, linking.host), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                    }
                }
            }
        }
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.attention, modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
        }
        Text(
            BoxWords.HOW_TO_LINK, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
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

/// You › Connected apps (iOS `ConnectedAppsScreen`, docs/phases/34-wallet-host.md): each org or app the person connected, its spending
/// limit (editable) and what it spent, what happens over it, Disconnect. Live from storage; the rows' rules are `ConnectedAppRow`.
@Composable
internal fun ConnectedAppsScreen(storage: Storage, onBack: () -> Unit) {
    val version by storage.walletConnectionsVersion.collectAsState()
    val connections = remember(version) { storage.walletConnections() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        item { RouteHeader(stringResource(R.string.misc_you_connected_apps), null, onBack) }
        item { Spacer(Modifier.height(24.dp)) }
        if (connections.isEmpty()) item { StadeumNote(ConnectedAppRow.EMPTY) }
        items(connections, key = { it.key }) { connection ->
            ConnectedAppCard(storage, connection)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ConnectedAppCard(storage: Storage, connection: WalletConnection) {
    var usedMsat by remember(connection) { mutableStateOf<Long?>(null) }
    var limitText by remember(connection) { mutableStateOf(ConnectedAppRow(connection).limitText) }
    LaunchedEffect(connection) { usedMsat = withContext(Dispatchers.IO) { storage.walletUsage(connection.key)?.usedMsat } }
    val row = ConnectedAppRow(connection, usedMsat, limitText)
    Column(Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill).padding(vertical = 8.dp)) {
        Text(row.title, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
        Text(row.place, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
        row.signInLine?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text) }
        if (row.hasWallet) {
            Text(row.limitSentence, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.text)
            Text(ConnectedAppRow.OVER_LIMIT, Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            OverBudget.entries.forEach { choice ->
                Row(
                    Modifier.fillMaxWidth().clickable { storage.setOverLimit(connection.key, choice) }.heightIn(min = 44.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = row.overBudget == choice, onClick = null)
                    Text(ConnectedAppRow.overLimitChoice(choice), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = limitText, onValueChange = { limitText = it.filter(Char::isDigit) }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(stringResource(R.string.misc_you_no_limit)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("sats", Modifier.padding(horizontal = 8.dp), color = Theme.colors.secondaryText)
                TextButton(onClick = { storage.setSpendingLimit(connection.key, row.typedMsat) }, enabled = row.canSave) { Text(stringResource(R.string.misc_save)) }
            }
            row.spentLine?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText) }
        }
        // A stranger's app leaves the Apps list with its connection (docs/phases/41-worker-onboarding.md).
        TextButton(onClick = { storage.deleteWalletConnection(connection.key); storage.forgetPublicAppsOf(connection.key) }, Modifier.padding(horizontal = 8.dp)) {
            Text(ConnectedAppRow.DISCONNECT, color = Theme.colors.destructive)
        }
    }
}

/// You › Site location (docs/phases/42-page-host-logs.md): each site's answer about its location. Tap one to allow, block, make it precise
/// or remove it. Android has one location permission for the whole app, so precise applies to every allowed site (the note says so).
@Composable
internal fun SiteLocationScreen(storage: Storage, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version by storage.siteLocationsVersion.collectAsState()
    val sites = remember(version) { storage.siteLocations() }
    var open by remember { mutableStateOf<String?>(null) }
    val grant = sites.firstOrNull { it.origin == open }
    val settings = {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    fun forget(origin: String) {
        runCatching { android.webkit.GeolocationPermissions.getInstance().clear(origin) }
        storage.removeSiteLocation(origin)
    }
    if (grant != null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            item { RouteHeader(com.sphinx.peel.engine.Seller.host(grant.origin) ?: grant.origin, null, { open = null }) }
            item { Spacer(Modifier.height(24.dp)) }
            item {
                Column(Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(PageLocation.summary(grant), color = Theme.colors.text, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.misc_you_allowed), Modifier.weight(1f), color = Theme.colors.text)
                        androidx.compose.material3.Switch(checked = grant.allowed, onCheckedChange = { storage.putSiteLocation(grant.copy(allowed = it, decidedAt = System.currentTimeMillis() / 1000)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(PageLocation.PRECISE_LABEL, Modifier.weight(1f), color = Theme.colors.text)
                        androidx.compose.material3.Switch(checked = grant.precise, onCheckedChange = { storage.putSiteLocation(grant.copy(precise = it, decidedAt = System.currentTimeMillis() / 1000)) })
                    }
                    Text(PageLocation.PRECISE_NOTE, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
                    TextButton(onClick = { forget(grant.origin); open = null }) { Text(stringResource(R.string.misc_remove), color = Theme.colors.destructive) }
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        item { RouteHeader(stringResource(R.string.misc_you_site_location), null, onBack) }
        item { Spacer(Modifier.height(24.dp)) }
        if (sites.isEmpty()) item { StadeumNote(stringResource(R.string.misc_you_no_site)) }
        items(sites, key = { it.origin }) { site ->
            Column(Modifier.fillMaxWidth().clip(CardShape).background(Theme.colors.fieldFill).clickable { open = site.origin }.padding(16.dp)) {
                Text(com.sphinx.peel.engine.Seller.host(site.origin) ?: site.origin, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Theme.colors.text)
                Text(PageLocation.summary(site), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
            }
            Spacer(Modifier.height(12.dp))
        }
        if (sites.isNotEmpty()) item {
            TextButton(onClick = {
                runCatching { android.webkit.GeolocationPermissions.getInstance().clearAll() }
                storage.clearSiteLocations()
            }) { Text(stringResource(R.string.misc_you_forget_all), color = Theme.colors.destructive) }
        }
        item { TextButton(onClick = { settings() }) { Text(stringResource(R.string.misc_you_phone_location)) } }
    }
}
