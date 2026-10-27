package com.sphinx.peel.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sphinx.peel.account.Account
import com.sphinx.peel.account.SecretStore
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
/// PeelCore's Onboarding.swift asks them. There is no "invite" step and no
/// restore-progress screen yet: those need the account engine and its
/// server connection, which Android doesn't have yet (see peel-android's
/// README). This covers what is local-only: generating or validating the
/// twelve words, and sealing them under a PIN with `SecretStore`.
@Composable
fun OnboardingScreen(secretStore: SecretStore, onAccountReady: (mnemonic: String) -> Unit) {
    var path by remember { mutableStateOf(Path.NEW_ACCOUNT) }
    var step by remember { mutableStateOf<Step>(Step.Welcome) }
    var enteredWords by remember { mutableStateOf(List(12) { "" }) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var mnemonic by remember { mutableStateOf("") }
    var shownWords by remember { mutableStateOf(listOf<String>()) }
    var check by remember { mutableStateOf(WordCheck()) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Sealing or opening the mnemonic runs PBKDF2 at a deliberately slow
    // iteration count (SecretStore.kt), so it has to happen off the main
    // thread or the keypad would freeze for a second or more.
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
                        withContext(Dispatchers.Default) { secretStore.save(generated, pin) }
                        mnemonic = generated
                        shownWords = Account.wordsIn(generated)
                        check = WordCheck()
                        step = Step.ShowWords
                    }
                    Path.RESTORE -> {
                        withContext(Dispatchers.Default) { secretStore.save(mnemonic, pin) }
                        onAccountReady(mnemonic)
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
            onAccountReady(mnemonic)
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
        Step.SetPin -> StepScaffold(title = "Set a PIN", problem = problem, hideAction = true) {
            PinPad(pin = pin, onPinChange = { pin = it }, onComplete = { step = Step.ConfirmPin })
        }
        Step.ConfirmPin -> StepScaffold(title = "Enter your PIN again", problem = problem, hideAction = true, working = working) {
            PinPad(pin = confirmation, onPinChange = { confirmation = it }, onComplete = { finishPin() }, enabled = !working)
        }
        Step.ShowWords -> StepScaffold(
            title = "Write down your twelve words",
            detail = "Anyone with these words controls your account.",
            problem = problem,
            actionLabel = "I've written them down",
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
            actionLabel = "Done",
            actionEnabled = check.isFilled,
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

/// A step's title, an optional detail line, its content, a problem line,
/// and a bottom action button. Ported from PeelCore's PhoneControls.swift
/// (`StepPage`).
@Composable
private fun StepScaffold(
    title: String,
    detail: String? = null,
    problem: String? = null,
    actionLabel: String = "Continue",
    actionEnabled: Boolean = true,
    hideAction: Boolean = false,
    working: Boolean = false,
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Theme.colors.text)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                }
            }
            content()
            if (problem != null) {
                Text(problem, color = Theme.colors.destructive, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
