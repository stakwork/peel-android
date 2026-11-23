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
///  - an org page whose workspace is no longer in use closes ([workspacesChanged], iOS `MainTabs` `.onChange(of: live)`), unless it
///    is a linked box's page and the box is still linked (iOS `closeGoneOrgPage`, docs/phases/45-box-link.md).
///
/// [opened] is told of each public page as it comes on screen, whichever way it came (a link, a worker's invite link, the Apps list):
/// the account keeps it for the Apps list (`Storage.rememberPublicApp`, docs/phases/41-worker-onboarding.md; Android only). It is told
/// too whether a worker's invite link named it (`invited`): that page signs the account in with no sheet (`WalletHost.grantInvitePage`,
/// docs/phases/49-invite-signs-in.md). The mark is held only in memory, with the waiting link, and never logged.
///
/// An employer's Tasks page (strut's `strut/worker`) opens as any public page does; it is a row in the Apps tab
/// (docs/phases/48-tasks-tab.md), which replaced the Tasks tab.
class PublicPageRouter(private val opened: (link: PublicAppLink, invited: Boolean) -> Unit = { _, _ -> }) {
    /// The stranger's page on screen, if any (iOS `publicPage`).
    var page by mutableStateOf<PublicAppLink?>(null)
        private set

    /// An org's page on screen, if any (iOS `orgPage`): a workspace's app, a deliverable, a tapped Hive link.
    var orgPage by mutableStateOf<OrgPageRequest?>(null)
        private set

    /// A link that arrived while the account was locked, opened once it unlocks.
    var waitingLink by mutableStateOf<PublicAppLink?>(null)
        private set

    /// Whether a worker's invite link named [waitingLink] (not a `peel://open` link).
    private var waitingInvited = false

    /// iOS `open(peelLink:)`; [ready]: the account is unlocked (`model.phase == .ready`). [invited]: a worker's invite link named it
    /// (its `a=`), and nothing else.
    fun open(link: PublicAppLink, ready: Boolean, invited: Boolean = false) {
        if (ready) {
            show(link, invited)
        } else {
            waitingLink = link
            waitingInvited = invited
        }
    }

    /// iOS `openWaitingLink()`: called when Peel unlocked.
    fun openWaitingLink() {
        val link = waitingLink ?: return
        val invited = waitingInvited
        waitingLink = null
        waitingInvited = false
        show(link, invited)
    }

    private fun show(link: PublicAppLink, invited: Boolean) {
        // Told first: an invite's grant is written before the page is set, so before it loads.
        opened(link, invited)
        orgPage = null
        page = link
    }

    /// iOS `router.orgPage = request`.
    fun openOrg(request: OrgPageRequest) {
        orgPage = request
    }

    /// iOS `closeGoneOrgPage` (`.onChange(of: live)` and of the boxes): the org page goes when its workspace is no longer in use,
    /// and a box's page when [shows] (`BoxesModel.shows`) no longer has its box.
    fun workspacesChanged(live: List<HivePeer>, shows: (OrgPageRequest) -> Boolean = { false }) {
        val shown = orgPage ?: return
        if (!shown.isLive(live) && !shows(shown)) orgPage = null
    }

    fun close() {
        page = null
    }

    fun closeOrg() {
        orgPage = null
    }

    /// The account locked (or failed to open): both pages close; a waiting link stays for the
    /// next unlock.
    fun locked() {
        orgPage = null
        page = null
    }

    /// The app is back at onboarding (signed out or wiped): no page, no waiting link.
    fun reset() {
        orgPage = null
        page = null
        waitingLink = null
        waitingInvited = false
    }
}
