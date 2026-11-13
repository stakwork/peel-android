package com.sphinx.peel.ui.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.PublicAppLink

/// Where a `peel://open?url=…` link goes (iOS `router.open(peelLink:)` / `openWaitingLink()`): open now when the
/// account is unlocked, else wait for the unlock; nothing outlives a lock or a sign-out.
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

    /// The account locked or signed out: no page, no waiting link.
    fun locked() {
        page = null
        waitingLink = null
    }
}
