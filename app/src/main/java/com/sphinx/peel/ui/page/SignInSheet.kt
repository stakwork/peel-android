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
import com.sphinx.peel.engine.WalletPerson
import com.sphinx.peel.engine.WorkspaceApp
import com.sphinx.peel.ui.theme.Theme
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/// The person's side of the wallet host: one sign-in sheet at a time ([ask] is what `SignInSheet` draws), and the
/// notice of a sign-in that went through silently. State is read on the main thread by Compose and written by the
/// wallet host's coroutine (the main one, from `PublicPageScreen`).
class SignInSheets(private val onSignedIn: (host: String, key: SignInKey) -> Unit) : WalletPerson {
    var ask by mutableStateOf<SignInAsk?>(null)
        private set
    private var pending: CancellableContinuation<SignInKey?>? = null

    override suspend fun askSignIn(ask: SignInAsk): SignInKey? = suspendCancellableCoroutine { continuation ->
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

    override fun signedIn(host: String, key: SignInKey) = onSignedIn(host, key)
}

/// Exact copy of iOS `WalletWords`.
object SignInWords {
    fun title(host: String) = "Sign in to $host?"
    fun toast(host: String, key: SignInKey) =
        if (key == SignInKey.root) "Signed in to $host as you" else "Signed in to $host as its own identity"
    fun who(ask: SignInAsk) =
        "${clean(ask.app.title)} · ${clean(ask.app.name)}@${clean(ask.app.version)}, as ${ask.host} names it"
    fun shared(host: String) = "Every app on $host shares this connection."
    const val CONFIRM = "Sign in"
    const val CANCEL = "Don't sign in"
    const val AS_YOU = "As you"
    const val AS_YOU_NOTE = "Your Sphinx account: every app you sign in to this way sees the same you."
    const val AS_NEW = "As a new identity for this app"
    const val AS_NEW_NOTE = "A key for this app alone: no other app can tell it is you. Payments still come from your account."

    /// The page's words, on one line and not too long.
    private fun clean(text: String) = WorkspaceApp.clean(text, 80)
}

/// How long the confirm button stays off after the sheet shows: a tap meant for the page must never be the yes.
const val SIGN_IN_ARM_DELAY_MS = 1000L

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
                    val (label, note) = if (key == SignInKey.root) SignInWords.AS_YOU to SignInWords.AS_YOU_NOTE
                    else SignInWords.AS_NEW to SignInWords.AS_NEW_NOTE
                    Row(
                        Modifier.selectable(selected = chosen == key, onClick = { chosen = key }, role = Role.RadioButton).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = chosen == key, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
                        Column {
                            Text(label, style = MaterialTheme.typography.bodyLarge, color = Theme.colors.text)
                            Text(note, style = MaterialTheme.typography.bodySmall, color = Theme.colors.secondaryText)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(chosen) }, enabled = armed) { Text(SignInWords.CONFIRM) } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(SignInWords.CANCEL) } },
    )
}
