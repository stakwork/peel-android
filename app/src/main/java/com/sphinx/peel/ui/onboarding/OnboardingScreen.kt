package com.sphinx.peel.ui.onboarding

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sphinx.peel.account.Account
import com.sphinx.peel.account.SecretStore
import com.sphinx.peel.account.Signup
import com.sphinx.peel.engine.InviteLink
import com.sphinx.peel.engine.SignupRules
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.NumberedWords
import com.sphinx.peel.ui.components.PinBoxes
import com.sphinx.peel.ui.components.WordCheck
import com.sphinx.peel.ui.components.WordCheckBoxes
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
    object ShowWords : Step()
    object CheckWords : Step()
}

/// New account (invite, name, PIN, the twelve words) or restore (the words,
/// PIN), as PeelCore's Onboarding.swift asks them. An invite is the only way
/// to a new account: there is no sign-up on the default server. Once the PIN
/// is confirmed, a restore builds the account's one `Engine` and calls
/// `start()` — connecting, and paging through its contacts and messages in the
/// background — and hands the engine to `onAccountReady` right away so the
/// caller can show the restoring screen; a new account instead signs up with
/// its invite (`Signup.create`: stores the invite, connects, registers) and
/// continues on to show its words first.
///
/// `inviteLink` is what a `sphinx.chat://` link the app was opened with held:
/// it fills the invite step in (`onInviteLinkUsed` is called once it has).
@Composable
fun OnboardingScreen(
    secretStore: SecretStore,
    storage: Storage,
    inviteLink: String? = null,
    onInviteLinkUsed: () -> Unit = {},
    onAccountReady: (engine: Engine) -> Unit,
) {
    com.sphinx.peel.ui.SecureWindow()
    var path by remember { mutableStateOf(Path.NEW_ACCOUNT) }
    var step by remember { mutableStateOf<Step>(Step.Welcome) }
    var enteredWords by remember { mutableStateOf(List(12) { "" }) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var mnemonic by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf<Engine?>(null) }
    var shownWords by remember { mutableStateOf(listOf<String>()) }
    var check by remember { mutableStateOf(WordCheck()) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var inviteText by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<com.sphinx.peel.engine.Invite?>(null) }
    var name by remember { mutableStateOf("") }
    // The server has not confirmed the register (yet): said on the words screen.
    var unconfirmed by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

    // Sealing the mnemonic runs PBKDF2 at a deliberately slow iteration
    // count (SecretStore.kt), so it has to happen off the main thread or
    // the keypad would freeze for a second or more.
    fun finishPin() {
        if (confirmation != pin) {
            problem = "The PINs don't match. Set it again."
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
                        engine = created.engine
                        mnemonic = created.mnemonic
                        unconfirmed = !created.confirmed
                        shownWords = Account.wordsIn(created.mnemonic)
                        check = WordCheck()
                        step = Step.ShowWords
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
                        // No pairing to show for it: You › Stadeum says to connect again.
                        com.sphinx.peel.engine.Stadeum.model.accountRestored()
                        onAccountReady(restored)
                    }
                }
                pin = ""
                confirmation = ""
            } catch (e: Exception) {
                problem = e.message ?: "Could not save the account."
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

    fun checkWords() {
        val wrong = check.byPosition.filter { (position, answer) ->
            Account.wordsIn(answer) != listOf(shownWords[position])
        }.keys.sorted()
        if (wrong.isEmpty()) {
            // PeelCore's own `confirmWords` writes this on success too
            // (`Engine+Account.swift`) - a flag for "this account's own
            // words have been written down", for a future screen (a
            // settings "back up your words" reminder) to read. Declared
            // on this port since Phase 1 (`Setting.kt`) but never once
            // written until now.
            storage.apply(Storage.Commit(settings = mapOf(Setting.WORDS_CONFIRMED to "1")))
            engine?.let {
                // An unconfirmed signup registers again, with its stored invite, as it connects.
                if (!it.isRegistered) it.resume()
                onAccountReady(it)
            }
        } else {
            problem = "Word${if (wrong.size > 1) "s" else ""} ${wrong.joinToString(", ") { "${it + 1}" }} ${if (wrong.size > 1) "don't" else "doesn't"} match."
            check = WordCheck()
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
                prompt = "Point the camera at the invite's QR code.",
                accept = { text ->
                    // Whatever it holds is untrusted text: only an invite is taken.
                    val ok = runCatching { InviteLink.parse(text) }.isSuccess
                    if (ok) { inviteText = text; scanning = false; null } else "That code isn't a Sphinx invite."
                },
                onCancel = { scanning = false },
            )
        } else StepScaffold(
            title = "Paste your invite",
            detail = "The link or code someone sent you.",
            problem = problem,
            actionLabel = "Continue",
            actionEnabled = inviteText.isNotBlank() && !working,
            working = working,
            stepNumber = 1,
            totalSteps = 4,
            onBack = ::back,
            onAction = {
                // The core reads it (a native call): off the main thread.
                working = true
                scope.launch {
                    try {
                        invite = withContext(Dispatchers.Default) { InviteLink.parse(inviteText) }
                        problem = null
                        step = Step.Name
                    } catch (e: Exception) {
                        problem = e.message ?: "That doesn't look like a Sphinx invite."
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
            title = "What should people call you?",
            detail = invite?.inviterAlias?.let { "$it invited you." },
            problem = problem,
            actionLabel = "Continue",
            actionEnabled = name.isNotBlank(),
            stepNumber = 2,
            totalSteps = 4,
            onBack = ::back,
            onAction = { problem = null; step = Step.SetPin },
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Step.EnterWords -> StepScaffold(
            title = "Restore with recovery words",
            detail = "Type them in order, or paste all twelve.",
            problem = problem,
            actionLabel = "Continue",
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
                    problem = "Those twelve words don't make a valid account. Check each one."
                }
            },
        ) {
            WordEntry(words = enteredWords, onWordsChange = { enteredWords = it })
        }
        Step.SetPin -> StepScaffold(
            title = "Set a PIN",
            problem = problem,
            hideAction = true,
            stepNumber = if (path == Path.RESTORE) 2 else 3,
            totalSteps = if (path == Path.RESTORE) 2 else 4,
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
            title = "Enter your PIN again",
            problem = problem,
            hideAction = true,
            working = working,
            stepNumber = if (path == Path.RESTORE) 2 else 3,
            totalSteps = if (path == Path.RESTORE) 2 else 4,
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
        Step.ShowWords -> StepScaffold(
            title = "Write down your twelve words",
            detail = "Anyone with these words controls your account.",
            problem = problem,
            actionLabel = "I've written them down",
            stepNumber = 4,
            totalSteps = 4,
            onAction = { step = Step.CheckWords },
        ) {
            NumberedWords(places = shownWords.indices.toList()) { index ->
                Text(shownWords[index], color = Theme.colors.text)
            }
            if (unconfirmed) {
                // Signed up, the server's answer not yet in (PeelCore's Onboarding says the same).
                Text(SignupRules.NOT_CONFIRMED, color = Theme.colors.destructive, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Step.CheckWords -> StepScaffold(
            title = "Check what you wrote",
            detail = "Type these three words from your paper.",
            problem = problem,
            actionLabel = "Open Peel",
            actionEnabled = check.isFilled,
            stepNumber = 4,
            totalSteps = 4,
            onAction = { checkWords() },
        ) {
            WordCheckBoxes(check = check, onSubmit = { if (check.isFilled) checkWords() })
        }
    }
}

private const val MAX_NAME_LENGTH = 100

/// The invite's text box, with Paste and Scan beside it. The text is a secret of sorts (anyone
/// holding the link can use the invite): never logged, and the screen is `SecureWindow`.
@Composable
private fun InviteField(text: String, onTextChange: (String) -> Unit, onScan: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("sphinx.chat://?action=i&d=…") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let(onTextChange) }) { Text("Paste") }
            OutlinedButton(onClick = onScan) { Text("Scan QR") }
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
        Text("Welcome to Peel", style = MaterialTheme.typography.headlineMedium, color = Theme.colors.text)
        Spacer(Modifier.height(10.dp))
        Text(
            "Private messages, payments, and an assistant that works for you.",
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
            Text("Join with an invite")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
            Text("Restore with recovery words")
        }
        Spacer(Modifier.height(12.dp))
        Text("No invite? Ask a friend who uses Peel.", style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
    }
}

/// A step's back button and "Step X of Y" progress, its title and detail,
/// its content, a problem line, and a bottom action button. Ported from
/// PeelCore's PhoneControls.swift (`StepPage`) plus the step chrome
/// `Onboarding.swift` draws around it (`progress`, the system back button
/// — Android has no free system back button inside a single Composable
/// screen the way SwiftUI's NavigationStack does, so this draws its own).
@Composable
private fun StepScaffold(
    title: String,
    detail: String? = null,
    problem: String? = null,
    actionLabel: String = "Continue",
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Theme.colors.text)
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
        Text("Step $step of $totalSteps", style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
    }
}
