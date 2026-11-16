package com.sphinx.peel.ui.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.SignInKey
import com.sphinx.peel.engine.WalletConnectAnswer
import com.sphinx.peel.engine.WalletConnectSheet
import com.sphinx.peel.engine.WalletCreditSheet
import com.sphinx.peel.engine.WalletPaymentSheet
import com.sphinx.peel.engine.WalletPerson
import com.sphinx.peel.engine.WalletSheet
import com.sphinx.peel.engine.WalletSheetAnswer
import com.sphinx.peel.engine.WalletWords
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/// The person's side of the wallet host on a page (iOS `PhoneWalletSheets` + `PhoneWalletPerson`): one sheet at a time, answered once
/// ([shown] is what `WalletSheets.kt` draws), by the person or as a no when Peel stops waiting; and the notice of a connection that
/// did something with no sheet ([toast], over the page for a moment and said to TalkBack). State is read on the main thread by Compose
/// and written by the wallet host's coroutine (the main one, from the page host).
class PhoneWalletSheets(private val scope: CoroutineScope, private val announce: (String) -> Unit = {}) : WalletPerson {
    var shown by mutableStateOf<WalletSheet?>(null)
        private set

    /// What a connection did with no sheet, for a moment over the page.
    var toast by mutableStateOf<String?>(null)
        private set
    private var pending: CancellableContinuation<WalletSheetAnswer>? = null
    private var toastShown: Job? = null
    private var open = true

    /// iOS's `stillOpen`: whether an answer still counts when it is given. The page closing (which a lock does too) ends it.
    val stillOpen: Boolean get() = open

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

    override suspend fun connect(sheet: WalletConnectSheet): WalletConnectAnswer? = ask(WalletSheet.Connect(sheet)).connect

    override suspend fun pay(sheet: WalletPaymentSheet): Boolean = ask(WalletSheet.Payment(sheet)).paid

    override suspend fun buyCredit(sheet: WalletCreditSheet): Boolean = ask(WalletSheet.Credit(sheet)).paid

    override fun signedIn(host: String, key: SignInKey) = show(WalletWords.signedIn(host, key))

    private suspend fun ask(sheet: WalletSheet): WalletSheetAnswer = suspendCancellableCoroutine { continuation ->
        // One at a time, and none once the page is closed: another asked while this is up is a no.
        if (pending != null || !open || !continuation.isActive) {
            continuation.resume(WalletSheetAnswer())
            return@suspendCancellableCoroutine
        }
        pending = continuation
        shown = sheet
        // The host cancels it on timeout (or the page closes): the sheet comes down with it.
        continuation.invokeOnCancellation {
            if (pending === continuation) {
                pending = null
                shown = null
            }
        }
    }

    /// The person's answer. A yes given once the page closed (or Peel locked) is a no.
    fun answer(answer: WalletSheetAnswer) {
        val continuation = pending ?: return
        pending = null
        shown = null
        if (continuation.isActive) continuation.resume(if (stillOpen) answer else WalletSheetAnswer())
    }

    /// Taken down unanswered: a no.
    fun dismiss() = answer(WalletSheetAnswer())

    /// The page went (closed, or Peel locked): what waits is a no, and nothing is asked again.
    fun close() {
        open = false
        toastShown?.cancel()
        toast = null
        dismiss()
    }

    companion object {
        /// How long the notice stays over the page (iOS 2.5 s).
        const val TOAST_MS = 2_500L
    }
}
