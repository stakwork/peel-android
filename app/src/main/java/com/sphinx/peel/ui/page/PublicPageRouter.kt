package com.sphinx.peel.ui.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.PublicAppLink

/// Where a `peel://open?url=…` link goes (iOS `router.open(peelLink:)` / `openWaitingLink()`): open now when the
/// account is unlocked, else wait for the unlock. A lock closes the page but keeps a waiting link (it opens at the next
/// unlock); only a sign-out or wipe ([reset]) drops both (iOS `PhoneRouter.reset`).
class PublicPageRouter {
    /// The page on screen, if any.
    var page by mutableStateOf<PublicAppLink?>(null)
        private set

    /// A link that arrived while the account was locked, opened once it unlocks.
    var waitingLink by mutableStateOf<PublicAppLink?>(null)
        private set

    /// [ready]: the account is unlocked. Any page already open is closed first (one page at a time).
    fun open(link: PublicAppLink, ready: Boolean) {
        page = null
        if (ready) {
            waitingLink = null
            page = link
        } else {
            waitingLink = link
        }
    }

    fun openWaitingLink() {
        val link = waitingLink ?: return
        waitingLink = null
        page = link
    }

    fun close() {
        page = null
    }

    /// The account locked (or failed to open): the page on screen closes; a waiting link stays for the next unlock.
    fun locked() {
        page = null
    }

    /// The app is back at onboarding (signed out or wiped): no page, no waiting link.
    fun reset() {
        page = null
        waitingLink = null
    }
}
