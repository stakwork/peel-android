package com.sphinx.peel.engine

import java.nio.ByteBuffer
import java.security.MessageDigest
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

    /// The WebView profile its cookies and storage are kept in across opens (iOS `PhonePage.websiteDataStore`); null to keep
    /// nothing, a profile of its own made for each open and deleted at close. An org's page keeps nothing: Peel hands it the
    /// person's token at each open.
    val keptProfile: String? get() = null

    /// Whether `other` is strut's UI page itself, rather than a file it serves.
    fun isUIPage(other: String): Boolean

    /// The app a link names, opened here; null for none.
    fun appLink(other: String, from: String?): StrutAppLink?

    fun request(link: StrutAppLink): OrgRequest?

    /// The app the page at `current` shows; null for none Peel knows of.
    fun shownApp(current: String): StrutAppHandle?

    /// Who the app wallet's messages are from, from Peel's own record, and where that is as the person reads it.
    val walletScope: WalletScope
    val walletPlace: String

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
/// token. A stranger's page is no strut UI Peel knows: no app of it is named or opened by Peel.
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

    override val walletScope: WalletScope get() = WalletScope.Origin(origin)
    override val walletPlace: String get() = host

    /// Kept, one profile per origin (iOS `PublicPage.storeIdentifier`): strut keeps a signed-in visitor for 30 days in a cookie and
    /// the page's localStorage, so the next open of any page on this origin finds the person signed in, and no site reads another's
    /// (docs/phases/42-page-host-logs.md). Forget this account deletes these profiles.
    override val keptProfile: String get() = keptProfile(origin)

    companion object {
        /// Every kept profile's name starts so (Forget this account finds them by it).
        const val KEPT_PROFILE_PREFIX = "peel_public_"

        /// RFC 9562's URL namespace, 6ba7b811-9dad-11d1-80b4-00c04fd430c8.
        private val URL_NAMESPACE = byteArrayOf(
            0x6b, 0xa7.toByte(), 0xb8.toByte(), 0x11, 0x9d.toByte(), 0xad.toByte(), 0x11, 0xd1.toByte(),
            0x80.toByte(), 0xb4.toByte(), 0x00, 0xc0.toByte(), 0x4f, 0xd4.toByte(), 0x30, 0xc8.toByte(),
        )

        fun keptProfile(origin: String): String = KEPT_PROFILE_PREFIX + storeIdentifier(origin).toString().replace("-", "")

        /// iOS `PublicPage.storeIdentifier(origin:)`: a name-based UUID (RFC 9562, version 5) over the origin, the same one iOS
        /// names this origin's store by.
        fun storeIdentifier(origin: String): UUID {
            val bytes = MessageDigest.getInstance("SHA-1").digest(URL_NAMESPACE + origin.toByteArray(Charsets.UTF_8)).copyOf(16)
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(bytes)
            return UUID(buffer.long, buffer.long)
        }
    }
}
