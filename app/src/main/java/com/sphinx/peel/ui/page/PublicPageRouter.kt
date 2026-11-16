package com.sphinx.peel.ui.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.HivePeer
import com.sphinx.peel.engine.OrgPageRequest
import com.sphinx.peel.engine.PublicAppLink

/// The two pages Peel can show over everything, and where a `peel://open?url=…` link goes (iOS `PhoneRouter`: `orgPage`, `publicPage`,
/// `waitingLink`, `open(peelLink:)`, `openWaitingLink()`, `reset()`, and the lock in `PeelPhoneApp`). iOS's rules, no more:
///  - a `peel://` link opens a public page and replaces any org page (`orgPage = nil` first); an org page opens by being set
///    ([openOrg]) and does not touch a public page (nothing in iOS does);
///  - a link that arrives locked only waits (no page is touched), and opens at the unlock;
///  - a lock (or a failed open) closes both pages but keeps a waiting link; a sign-out or wipe ([reset]) drops all three;
///  - an org page whose workspace is no longer in use closes ([workspacesChanged], iOS `MainTabs` `.onChange(of: live)`).
///
/// [opened] is told of each public page as it comes on screen, whichever way it came (a link, a worker's invite link, the Apps list):
/// the account keeps it for the Apps list (`Storage.rememberPublicApp`, docs/phases/41-worker-onboarding.md; Android only).
class PublicPageRouter(private val opened: (PublicAppLink) -> Unit = {}) {
    /// The stranger's page on screen, if any (iOS `publicPage`).
    var page by mutableStateOf<PublicAppLink?>(null)
        private set

    /// An org's page on screen, if any (iOS `orgPage`): a workspace's app, a deliverable, a tapped Hive link.
    var orgPage by mutableStateOf<OrgPageRequest?>(null)
        private set

    /// A link that arrived while the account was locked, opened once it unlocks.
    var waitingLink by mutableStateOf<PublicAppLink?>(null)
        private set

    /// iOS `open(peelLink:)`; [ready]: the account is unlocked (`model.phase == .ready`).
    fun open(link: PublicAppLink, ready: Boolean) {
        if (ready) {
            orgPage = null
            page = link
            opened(link)
        } else {
            waitingLink = link
        }
    }

    /// iOS `openWaitingLink()`: called when Peel unlocked.
    fun openWaitingLink() {
        val link = waitingLink ?: return
        waitingLink = null
        orgPage = null
        page = link
        opened(link)
    }

    /// iOS `router.orgPage = request`.
    fun openOrg(request: OrgPageRequest) {
        orgPage = request
    }

    /// iOS `.onChange(of: live)`: the org page goes when its workspace is no longer in use.
    fun workspacesChanged(live: List<HivePeer>) {
        val shown = orgPage ?: return
        if (!shown.isLive(live)) orgPage = null
    }

    fun close() {
        page = null
    }

    fun closeOrg() {
        orgPage = null
    }

    /// The account locked (or failed to open): both pages close; a waiting link stays for the next unlock.
    fun locked() {
        orgPage = null
        page = null
    }

    /// The app is back at onboarding (signed out or wiped): no page, no waiting link.
    fun reset() {
        orgPage = null
        page = null
        waitingLink = null
    }
}
