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
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.storage.Setting
import com.sphinx.peel.storage.Storage
import com.sphinx.peel.ui.components.NumberedWords
import com.sphinx.peel.ui.components.PinPad
import com.sphinx.peel.ui.components.WordCheck
import com.sphinx.peel.ui.components.WordCheckBoxes
import com.sphinx.peel.ui.components.WordEntry
import com.sphinx.peel.ui.theme.Theme

private enum class Path { NEW_ACCOUNT, RESTORE }

private sealed class Step {
    object Welcome : Step()
    object EnterWords : Step()
    object SetPin : Step()
    object ConfirmPin : Step()
    object ShowWords : Step()
    object CheckWords : Step()
}

/// New account (PIN, the twelve words) or restore (the words, PIN), as
/// PeelCore's Onboarding.swift asks them. There is still no "invite" step
/// (Android has no invite flow yet). Once the PIN is confirmed, this
/// builds the account's one `Engine` and calls `start()` — connecting,
/// and for a restored account, paging through its contacts and messages
/// in the background — then, for a restore, hands the engine to
/// `onAccountReady` right away so the caller can show the restoring
/// screen; a new account instead continues on to show its words first.
@Composable
fun OnboardingScreen(secretStore: SecretStore, storage: Storage, onAccountReady: (engine: Engine) -> Unit) {
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
    val scope = rememberCoroutineScope()

    fun back() {
        problem = null
        step = when (step) {
            Step.EnterWords -> Step.Welcome
            Step.SetPin -> if (path == Path.RESTORE) Step.EnterWords else Step.Welcome
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
                        val generated = Account.generateMnemonic()
                        val created = withContext(Dispatchers.Default) {
                            secretStore.save(generated, pin)
                            Engine(generated, storage).also { it.start() }
                        }
                        engine = created
                        mnemonic = generated
                        shownWords = Account.wordsIn(generated)
                        check = WordCheck()
                        step = Step.ShowWords
                    }
                    Path.RESTORE -> {
                        val restored = withContext(Dispatchers.Default) {
                            secretStore.save(mnemonic, pin)
                            storage.apply(Storage.Commit(settings = mapOf(Setting.NEEDS_RESTORE to "1")))
                            Engine(mnemonic, storage).also { it.start() }
                        }
                        onAccountReady(restored)
                    }
                }
                pin = ""
                confirmation = ""
            } catch (e: Exception) {
                problem = e.message ?: "Could not save the account."
                confirmation = ""
            }
            working = false
        }
    }

    fun checkWords() {
        val wrong = check.byPosition.filter { (position, answer) ->
            Account.wordsIn(answer) != listOf(shownWords[position])
        }.keys.sorted()
        if (wrong.isEmpty()) {
            engine?.let(onAccountReady)
        } else {
            problem = "Word${if (wrong.size > 1) "s" else ""} ${wrong.joinToString(", ") { "${it + 1}" }} ${if (wrong.size > 1) "don't" else "doesn't"} match."
            check = WordCheck()
        }
    }

    when (step) {
        Step.Welcome -> Welcome(
            problem = problem,
            onNewAccount = { path = Path.NEW_ACCOUNT; problem = null; step = Step.SetPin },
            onRestore = { path = Path.RESTORE; problem = null; step = Step.EnterWords },
        )
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
            stepNumber = if (path == Path.RESTORE) 2 else 1,
            totalSteps = 2,
            onBack = ::back,
        ) {
            PinPad(pin = pin, onPinChange = { pin = it }, onComplete = { step = Step.ConfirmPin })
        }
        Step.ConfirmPin -> StepScaffold(
            title = "Enter your PIN again",
            problem = problem,
            hideAction = true,
            working = working,
            stepNumber = if (path == Path.RESTORE) 2 else 1,
            totalSteps = 2,
            onBack = ::back,
        ) {
            PinPad(pin = confirmation, onPinChange = { confirmation = it }, onComplete = { finishPin() }, enabled = !working)
        }
        Step.ShowWords -> StepScaffold(
            title = "Write down your twelve words",
            detail = "Anyone with these words controls your account.",
            problem = problem,
            actionLabel = "I've written them down",
            stepNumber = 2,
            totalSteps = 2,
            onAction = { step = Step.CheckWords },
        ) {
            NumberedWords(places = shownWords.indices.toList()) { index ->
                Text(shownWords[index], color = Theme.colors.text)
            }
        }
        Step.CheckWords -> StepScaffold(
            title = "Check what you wrote",
            detail = "Type these three words from your paper.",
            problem = problem,
            actionLabel = "Open Peel",
            actionEnabled = check.isFilled,
            stepNumber = 2,
            totalSteps = 2,
            onAction = { checkWords() },
        ) {
            WordCheckBoxes(check = check, onSubmit = { if (check.isFilled) checkWords() })
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
            Text("Create a new account")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
            Text("Restore with recovery words")
        }
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
