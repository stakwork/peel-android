package com.sphinx.peel.ui.onboarding

import com.sphinx.peel.ui.str
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sphinx.peel.R
import com.sphinx.peel.ui.ErrorText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sphinx.peel.account.Account
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.account.Signup
import com.sphinx.peel.engine.InviteLink
import com.sphinx.peel.engine.PublicAppLink
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.PinBoxes
import com.sphinx.peel.ui.components.WordEntry
import com.sphinx.peel.ui.theme.Theme

private enum class Path { NEW_ACCOUNT, RESTORE }

private sealed class Step {
    object Welcome : Step()
    object Invite : Step()
    object Name : Step()
    object EnterWords : Step()
    object SetPin : Step()
    object ConfirmPin : Step()
}

/// New account (invite, name, PIN) or restore (the words, PIN). An invite is
/// the only way to a new account: there is no sign-up on the default server.
/// Once the PIN is confirmed, a restore builds the account's one `Engine` and
/// calls `start()` — connecting, and paging through its contacts and messages
/// in the background — and hands the engine to `onAccountReady` right away so
/// the caller can show the restoring screen; a new account instead signs up
/// with its invite (`Signup.create`: stores the invite, connects, registers)
/// and opens Peel. A new account never sees its twelve words here (unlike
/// PeelCore's Onboarding.swift): You › Twelve words shows and checks them
/// later, and says "Not checked yet" until then (phase 47).
///
/// `inviteLink` is what a `sphinx.chat://` link the app was opened with held:
/// it fills the invite step in (`onInviteLinkUsed` is called once it has).
/// `onInviteApp` is told the app a worker's invite names (`InviteLink.appOf`, its
/// `a=`) when the invite step goes on with it, however the invite got there:
/// tapped, off the clipboard, pasted or scanned (docs/phases/51-pasted-invite.md).
@Composable
fun OnboardingScreen(
    secretStore: SecretStore,
    storage: Storage,
    inviteLink: String? = null,
    onInviteLinkUsed: () -> Unit = {},
    onInviteApp: (PublicAppLink) -> Unit = {},
    onAccountReady: (engine: Engine) -> Unit,
) {
    com.sphinx.peel.ui.SecureWindow()
    var path by remember { mutableStateOf(Path.NEW_ACCOUNT) }
    var step by remember { mutableStateOf<Step>(Step.Welcome) }
    var enteredWords by remember { mutableStateOf(List(12) { "" }) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var mnemonic by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var inviteText by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<com.sphinx.peel.engine.Invite?>(null) }
    var name by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(inviteLink) {
        if (inviteLink != null && (step is Step.Welcome || step is Step.Invite)) {
            inviteText = inviteLink
            path = Path.NEW_ACCOUNT
            problem = null
            step = Step.Invite
            onInviteLinkUsed()
        }
    }

    fun back() {
        problem = null
        step = when (step) {
            Step.Invite, Step.EnterWords -> Step.Welcome
            Step.Name -> Step.Invite
            Step.SetPin -> if (path == Path.RESTORE) Step.EnterWords else Step.Name
            Step.ConfirmPin -> {
                pin = ""
                confirmation = ""
                Step.SetPin
            }
            else -> step
        }
    }

    // The system back matches the on-screen back.
    BackHandler(enabled = step != Step.Welcome) {
        when {
            scanning -> scanning = false
            working -> Unit
            else -> back()
        }
    }

    // Sealing the mnemonic runs PBKDF2 at a deliberately slow iteration
    // count (SecretStore.kt), so it has to happen off the main thread or
    // the keypad would freeze for a second or more.
    fun finishPin() {
        if (confirmation != pin) {
            problem = context.str(R.string.onboarding_pins_dont_match)
            pin = ""
            confirmation = ""
            step = Step.SetPin
            return
        }
        working = true
        problem = null
        scope.launch {
            try {
                when (path) {
                    Path.NEW_ACCOUNT -> {
                        val toJoin = invite ?: throw com.sphinx.peel.engine.InviteProblem.NOT_AN_INVITE
                        val created = Signup.create(secretStore, storage, toJoin, name, pin)
                        // An unconfirmed signup registers again, with its stored invite, as it connects.
                        if (!created.isRegistered) created.resume()
                        onAccountReady(created)
                    }
                    Path.RESTORE -> {
                        val restored = withContext(Dispatchers.Default) {
                            secretStore.save(mnemonic, pin)
                            // Typing all twelve words back in to restore is
                            // itself the confirmation a fresh account's own
                            // 3-word spot check exists to get — PeelCore's
                            // own `Account.swift` sets this the same way
                            // (`needsRestore ? "1" : "0"`). Left unset here
                            // before this fix: a restored account's own
                            // Profile would have shown "Not checked yet"
                            // forever, nothing in this port ever having
                            // written it true for that path.
                            storage.apply(
                                Storage.Commit(
                                    settings = mapOf(Setting.NEEDS_RESTORE to "1", Setting.WORDS_CONFIRMED to "1"),
                                ),
                            )
                            Engine(mnemonic, storage).also { it.start() }
                        }
                        // No pairing to show for it: You › Hive says to connect again.
                        com.sphinx.peel.engine.Stadeum.model.accountRestored()
                        onAccountReady(restored)
                    }
                }
                pin = ""
                confirmation = ""
            } catch (e: Exception) {
                problem = ErrorText.text(context, e, R.string.err_could_not_save)
                confirmation = ""
                // A signup that ended with no account (no connection, the broker refusing): back to the
                // invite, which is still filled in; the PIN is asked again after it.
                if (path == Path.NEW_ACCOUNT) {
                    pin = ""
                    step = Step.Invite
                }
            }
            working = false
        }
    }

    when (step) {
        Step.Welcome -> Welcome(
            problem = problem,
            onNewAccount = { path = Path.NEW_ACCOUNT; problem = null; step = Step.Invite },
            onRestore = { path = Path.RESTORE; problem = null; step = Step.EnterWords },
        )
        Step.Invite -> if (scanning) {
            com.sphinx.peel.ui.payments.CodeScanner(
                prompt = stringResource(R.string.onboarding_scan_prompt),
                accept = { text ->
                    // Whatever it holds is untrusted text: only an invite is taken.
                    val ok = runCatching { InviteLink.parse(text) }.isSuccess
                    if (ok) { inviteText = text; scanning = false; null } else context.str(R.string.onboarding_not_an_invite_code)
                },
                onCancel = { scanning = false },
            )
        } else StepScaffold(
            title = stringResource(R.string.onboarding_invite_title),
            detail = stringResource(R.string.onboarding_invite_detail),
            problem = problem,
            actionLabel = stringResource(R.string.common_continue),
            actionEnabled = inviteText.isNotBlank() && !working,
            working = working,
            stepNumber = 1,
            totalSteps = 3,
            onBack = ::back,
            onAction = {
                // The core reads it (a native call): off the main thread.
                working = true
                scope.launch {
                    try {
                        invite = withContext(Dispatchers.Default) { InviteLink.parse(inviteText) }
                        // A worker's invite names the app to open after signup (`a=`): kept as a tapped link's is, so a
                        // pasted or scanned one opens it too.
                        InviteLink.appOf(inviteText.trim())?.let(onInviteApp)
                        problem = null
                        step = Step.Name
                    } catch (e: Exception) {
                        problem = ErrorText.text(context, e, R.string.err_not_an_invite)
                    }
                    working = false
                }
            },
        ) {
            InviteField(
                text = inviteText,
                onTextChange = { inviteText = it.take(com.sphinx.peel.engine.InviteLink.MAX_LENGTH + 1) },
                onScan = { scanning = true },
            )
        }
        Step.Name -> StepScaffold(
            title = stringResource(R.string.onboarding_name_title),
            detail = invite?.inviterAlias?.let { stringResource(R.string.onboarding_invited_you, it) },
            problem = problem,
            actionLabel = stringResource(R.string.common_continue),
            actionEnabled = name.isNotBlank(),
            stepNumber = 2,
            totalSteps = 3,
            onBack = ::back,
            onAction = { problem = null; step = Step.SetPin },
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.onboarding_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Step.EnterWords -> StepScaffold(
            title = stringResource(R.string.onboarding_restore),
            detail = stringResource(R.string.onboarding_words_detail),
            problem = problem,
            actionLabel = stringResource(R.string.common_continue),
            actionEnabled = enteredWords.all { it.isNotBlank() },
            stepNumber = 1,
            totalSteps = 2,
            onBack = ::back,
            onAction = {
                try {
                    mnemonic = Account.validate(enteredWords.joinToString(" "))
                    problem = null
                    step = Step.SetPin
                } catch (e: Exception) {
                    problem = context.str(R.string.onboarding_words_invalid)
                }
            },
        ) {
            WordEntry(words = enteredWords, onWordsChange = { enteredWords = it })
        }
        Step.SetPin -> StepScaffold(
            title = stringResource(R.string.onboarding_set_pin),
            problem = problem,
            hideAction = true,
            stepNumber = if (path == Path.RESTORE) 2 else 3,
            totalSteps = if (path == Path.RESTORE) 2 else 3,
            onBack = ::back,
        ) {
            PinBoxes(
                pin = pin,
                onPinChange = { pin = it },
                onComplete = { step = Step.ConfirmPin },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Step.ConfirmPin -> StepScaffold(
            title = stringResource(R.string.onboarding_confirm_pin),
            problem = problem,
            hideAction = true,
            working = working,
            stepNumber = if (path == Path.RESTORE) 2 else 3,
            totalSteps = if (path == Path.RESTORE) 2 else 3,
            onBack = ::back,
        ) {
            PinBoxes(
                pin = confirmation,
                onPinChange = { confirmation = it },
                onComplete = { finishPin() },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            )
            if (working) {
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Theme.colors.accent)
                }
            }
        }
    }
}

private const val MAX_NAME_LENGTH = 100

/// The invite's text box, with a scan icon in it and Paste below. The text is a secret of sorts (anyone
/// holding the link can use the invite): never logged, and the screen is `SecureWindow`.
@Composable
private fun InviteField(text: String, onTextChange: (String) -> Unit, onScan: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.onboarding_invite_example)) },
            minLines = 2,
            maxLines = 5,
            trailingIcon = {
                IconButton(onClick = onScan) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.onboarding_scan_qr), tint = Theme.colors.accent)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let(onTextChange) }) { Text(stringResource(R.string.common_paste)) }
        }
    }
}

@Composable
private fun Welcome(problem: String?, onNewAccount: () -> Unit, onRestore: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.headlineMedium, color = Theme.colors.text)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = Theme.colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        if (problem != null) {
            Spacer(Modifier.height(16.dp))
            Text(problem, color = Theme.colors.destructive, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(36.dp))
        Button(onClick = onNewAccount, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText)) {
            Text(stringResource(R.string.onboarding_join))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_restore))
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.onboarding_no_invite), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
    }
}

/// A step's back button and "Step X of Y" progress, its title and detail,
/// its content, a problem line, and a bottom action button. Ported from
/// PeelCore's PhoneControls.swift (`StepPage`) plus the step chrome
/// `Onboarding.swift` draws around it (`progress`, the system back button
/// — Android has no free system back button inside a single Composable
/// screen the way SwiftUI's NavigationStack does, so this draws the on-screen
/// back, and OnboardingScreen's BackHandler routes the system back to the same `back()`).
@Composable
private fun StepScaffold(
    title: String,
    detail: String? = null,
    problem: String? = null,
    actionLabel: String = stringResource(R.string.common_continue),
    actionEnabled: Boolean = true,
    hideAction: Boolean = false,
    working: Boolean = false,
    stepNumber: Int? = null,
    totalSteps: Int? = null,
    onBack: (() -> Unit)? = null,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            if (!hideAction) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp)) {
                    Button(
                        onClick = onAction,
                        enabled = actionEnabled && !working,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.button, contentColor = Theme.colors.buttonText),
                    ) { Text(actionLabel) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(Theme.colors.fieldFill),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Theme.colors.text)
                    }
                }
                if (stepNumber != null && totalSteps != null) {
                    StepProgress(step = stepNumber, totalSteps = totalSteps)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = Theme.colors.text)
                    if (detail != null) {
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                    }
                }
            }
            content()
            if (problem != null) {
                Text(problem, color = Theme.colors.destructive, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/// A segment per step, filled up to and including the current one, plus
/// "Step X of Y" underneath. Ported pixel-for-pixel from the iPhone app's
/// onboarding progress bar.
@Composable
private fun StepProgress(step: Int, totalSteps: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(totalSteps) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (index < step) Theme.colors.accent else Theme.colors.divider),
                )
            }
        }
        Text(stringResource(R.string.onboarding_step_of, step, totalSteps), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
    }
}
