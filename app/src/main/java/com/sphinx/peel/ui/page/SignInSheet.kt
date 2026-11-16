package com.sphinx.peel.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.sphinx.peel.engine.SignInAsk
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.TextLine
import com.sphinx.peel.engine.WalletPerson
import com.sphinx.peel.ui.theme.Theme
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/// The person's side of the wallet host on a page (iOS `PhoneWalletSheets`): one sign-in sheet at a time ([ask] is what
/// `SignInSheet` draws), and the notice of a connection that did something with no sheet ([toast], over the page for a moment
/// and said to TalkBack). State is read on the main thread by Compose and written by the wallet host's coroutine (the main
/// one, from the page host).
class PhoneWalletSheets(private val scope: CoroutineScope, private val announce: (String) -> Unit = {}) : WalletPerson {
    var ask by mutableStateOf<SignInAsk?>(null)
        private set
    /// What a connection did with no sheet, for a moment over the page.
    var toast by mutableStateOf<String?>(null)
        private set
    private var pending: CancellableContinuation<SignInKey?>? = null
    private var toastShown: Job? = null

    /// Shows [words] for a moment over the page, and says them to TalkBack.
    fun show(words: String) {
        toast = words
        announce(words)
        toastShown?.cancel()
        toastShown = scope.launch {
            delay(TOAST_MS)
            toast = null
        }
    }

    override suspend fun askSignIn(ask: SignInAsk): SignInKey? = suspendCancellableCoroutine { continuation ->
        // One at a time: another asked while this is up is a no.
        if (pending != null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        pending = continuation
        this.ask = ask
        // The host cancels it on timeout (or the page closes): the sheet comes down with it.
        continuation.invokeOnCancellation {
            if (pending === continuation) {
                pending = null
                this.ask = null
            }
        }
    }

    /// The person's answer: a key to sign in with, or null for "Don't sign in" (or dismissing the sheet).
    fun answer(key: SignInKey?) {
        val continuation = pending ?: return
        pending = null
        ask = null
        if (continuation.isActive) continuation.resume(key)
    }

    /// Taken down unanswered: a no.
    fun dismiss() = answer(null)

    override fun signedIn(host: String, key: SignInKey) = show(SignInWords.toast(host, key))

    companion object {
        /// How long the notice stays over the page (iOS 2.5 s).
        const val TOAST_MS = 2_500L
    }
}

/// Copy of iOS `WalletWords` for the connect sheet of a stranger's page.
object SignInWords {
    fun title(host: String) = "Sign in to $host?"
    fun toast(host: String, key: SignInKey) =
        if (key == SignInKey.root) "Signed in to $host as you" else "Signed in to $host as its own identity"
    fun who(ask: SignInAsk) = "An app on ${ask.host}, which calls itself ${clean(ask.app.title)}"
    fun note(key: SignInKey) = if (key == SignInKey.root) AS_YOU_NOTE else AS_NEW_NOTE
    fun shared(host: String) = "Every app on $host shares this connection."
    const val CONFIRM = "Sign in"
    const val CANCEL = "Don't sign in"
    const val AS_YOU = "As you"
    const val AS_YOU_NOTE = "Your Sphinx account: every app you sign in to this way sees the same you."
    const val AS_NEW = "As a new identity for this app"
    const val AS_NEW_NOTE = "A key for this app alone: no other app can tell it is you. Payments still come from your account."

    /// The page's words, on one line and not too long.
    private fun clean(text: String) = TextLine.clean(text, 80)
}

/// How long the confirm button stays off after the sheet shows: a tap meant for the page must never be the yes
/// (iOS `TouchPayButton.settle`).
const val SIGN_IN_ARM_DELAY_MS = 700L

/// The sign-in sheet (iOS `StrutSignInSheet`). Dismissing it, by Back, is "Don't sign in"; a tap outside does nothing.
@Composable
fun SignInSheet(ask: SignInAsk, onAnswer: (SignInKey?) -> Unit) {
    var chosen by remember(ask) { mutableStateOf(ask.keys.first()) }
    var armed by remember(ask) { mutableStateOf(false) }
    LaunchedEffect(ask) {
        delay(SIGN_IN_ARM_DELAY_MS)
        armed = true
    }
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
        title = { Text(SignInWords.title(ask.host)) },
        text = {
            Column {
                Text(SignInWords.who(ask), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                Spacer(Modifier.height(4.dp))
                Text(SignInWords.shared(ask.host), style = MaterialTheme.typography.bodyMedium, color = Theme.colors.secondaryText)
                Spacer(Modifier.height(12.dp))
                ask.keys.forEach { key ->
                    val label = if (key == SignInKey.root) SignInWords.AS_YOU else SignInWords.AS_NEW
                    Row(
                        Modifier.selectable(selected = chosen == key, onClick = { chosen = key }, role = Role.RadioButton).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == key, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                    }
                }
                // One note, for the key chosen now.
                Spacer(Modifier.height(4.dp))
                Text(SignInWords.note(chosen), style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(chosen) }, enabled = armed) { Text(SignInWords.CONFIRM) } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(SignInWords.CANCEL) } },
    )
}
