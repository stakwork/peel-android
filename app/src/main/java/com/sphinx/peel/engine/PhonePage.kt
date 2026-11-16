package com.sphinx.peel.engine

import java.util.UUID

/// What one page host shows (iOS `PhonePage`, `Apps/iOS/Peel/OrgPageSheet.swift`): an org's page on its own strut with the
/// person's token ([OrgPageRequest]), or a stranger's public app Peel loaded itself with no credential ([PublicPage]). The host
/// (`ui/apps/OrgPageHost.kt`) asks the page everything that differs between them; the rest is the same web view.
interface PhonePage {
    /// The address it opens at.
    val url: String

    /// The origin Peel loaded, as a page reads it (`location.origin`): the only origin the wallet answers to.
    val origin: String

    /// The request that loads it.
    val request: OrgRequest

    /// The request that loads it again where the page went; null where it is not loaded that way.
    fun request(at: String?): OrgRequest?

    /// Whether the page may go to `other`.
    fun serves(other: String): Boolean

    /// A script run before the page's own, in its main frame only; null for none.
    val script: String?

    /// Whether `other` is strut's UI page itself, rather than a file it serves.
    fun isUIPage(other: String): Boolean

    /// The app a link names, opened here; null for none.
    fun appLink(other: String, from: String?): StrutAppLink?

    fun request(link: StrutAppLink): OrgRequest?

    /// The app the page at `current` shows; null for none Peel knows of.
    fun shownApp(current: String): StrutAppHandle?

    /// The page's title.
    fun title(showingAnotherApp: Boolean): String

    /// Who the app wallet's messages are from, from Peel's own record, and where that is as the person reads it.
    val walletScope: WalletScope
    val walletPlace: String

    /// Whether the page `current` shows an app other than the one it opened with: a link in it opened that one here.
    fun showsAnotherApp(current: String?): Boolean {
        val shown = current?.let { shownApp(it) } ?: return false
        return shown != shownApp(url)
    }

    /// How to load the page again where the person went (iOS `OrgPageController.reload`): strut's UI with the page's bearer
    /// (an app at the view it moved to, or another app a link opened), a file opened in place as it is, and the page's own
    /// request with no address yet.
    fun reload(current: String?): Reload =
        request(current)?.let { Reload.Load(it) } ?: if (current != null) Reload.InPlace else Reload.Load(request)

    sealed interface Reload {
        data class Load(val request: OrgRequest) : Reload
        /// Reload what is there, as it is.
        data object InPlace : Reload
    }

    /// What Try again loads (iOS `retry`): what failed, with the page's bearer, when that was strut's UI; else where the page is,
    /// or its own request.
    fun retry(failed: String?, current: String?): OrgRequest = request(failed) ?: request(current) ?: request
}

/// A stranger's public app as the phone opens it (iOS `PublicPage`; strut's plans/app-payments.md §5.3, R7, "direct open";
/// docs/app-wallet.md, "Signing in"): Peel loads the box's own address itself, with no credential, and keeps to that origin. The
/// box's own code is the main frame and may claim anything, so the app wallet trusts only the origin Peel loaded
/// ([WalletScope.Origin]), keying the connection and naming every sheet by it. The org page's twin ([OrgPageRequest]), with no
/// token. A stranger's page is no strut UI Peel knows: no app of it is named or opened by Peel, and it is titled by its host.
class PublicPage(val link: PublicAppLink) : PhonePage {
    val id: UUID = UUID.randomUUID()

    override fun equals(other: Any?) = other is PublicPage && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = "public page on $host"

    override val url: String get() = link.url
    override val origin: String get() = link.origin

    /// The host, as the person reads it and the sheets name it.
    val host: String get() = link.host

    /// Whether `other` is on the origin Peel loaded.
    override fun serves(other: String): Boolean = UrlParts.origin(other) == origin

    /// No credential of anyone's.
    override val request: OrgRequest get() = OrgRequest(url)

    /// Where the page went, while that is still on its origin.
    override fun request(at: String?): OrgRequest? = if (at != null && serves(at)) OrgRequest(at) else null

    override val script: String? get() = null
    override fun isUIPage(other: String) = false
    override fun appLink(other: String, from: String?): StrutAppLink? = null
    override fun request(link: StrutAppLink): OrgRequest? = null
    override fun shownApp(current: String): StrutAppHandle? = null
    override fun title(showingAnotherApp: Boolean): String = host

    override val walletScope: WalletScope get() = WalletScope.Origin(origin)
    override val walletPlace: String get() = host
}
