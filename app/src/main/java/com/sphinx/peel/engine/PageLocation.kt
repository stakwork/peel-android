package com.sphinx.peel.engine

/// What the person said about one site's location (`Storage.siteLocation`): kept per origin, in the account's own database, so
/// it goes with the profile. "Allow once" is never stored: it lives in the page's controller for that page session only.
data class SiteLocationGrant(val origin: String, val allowed: Boolean, val precise: Boolean, val decidedAt: Long)

/// Where site-location answers are kept ([com.sphinx.peel.storage.Storage] implements it).
interface SiteLocationStore {
    fun siteLocation(origin: String): SiteLocationGrant?
    fun putSiteLocation(grant: SiteLocationGrant)
}

/// A page's location, as plain rules (docs/phases/42-page-host-logs.md, "Location"): Peel asks the person first, remembers the
/// answer itself (WebView's own memory is never used: `retain` is always false), and asks the system only when a page asks.
/// Peel never reads a coordinate; the engine gives them to the page.
object PageLocation {
    const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
    const val FINE = "android.permission.ACCESS_FINE_LOCATION"

    /// How the asking frame relates to the page Peel loaded, for the log.
    enum class Frame(val wire: String) { MAIN("main"), OPAQUE("opaque"), OTHER("other") }

    /// Who a request is attributed to, and from which kind of frame.
    data class Attribution(val origin: String, val frame: Frame)

    /// The origin to attribute a request to, or null: denied with no prompt. The page must be showing, its own origin secure
    /// (https, or http on loopback). The request must come from that origin, or from an opaque one (strut's sandboxed app frame, which
    /// only gets location when strut's page delegated it with `allow="geolocation"`); a frame of another site never.
    fun mayAsk(requestOrigin: String, pageOrigin: String, pageVisible: Boolean): Attribution? {
        if (!pageVisible) return null
        val page = Seller.origin(pageOrigin) ?: return null
        val host = Seller.host(pageOrigin) ?: return null
        val secure = page.startsWith("https://") || (page.startsWith("http://") && Seller.isLoopback(host))
        if (!secure) return null
        if (StrutWallet.mayAnswer(isMainFrame = true, sourceOrigin = requestOrigin, origin = pageOrigin)) return Attribution(page, Frame.MAIN)
        val trimmed = requestOrigin.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return Attribution(page, Frame.OPAQUE)
        return null
    }

    enum class Decision { ASK, ALLOW, DENY }

    /// A stored answer wins (a stored no is silent: no nagging); else an allow-once; else Peel asks.
    fun decide(stored: SiteLocationGrant?, allowOnce: Boolean): Decision = when {
        stored != null -> if (stored.allowed) Decision.ALLOW else Decision.DENY
        allowOnce -> Decision.ALLOW
        else -> Decision.ASK
    }

    /// What the system is asked for: approximate alone, or approximate and precise (Android 12+ lets the person pick approximate anyway).
    fun systemPermissions(precise: Boolean): List<String> = if (precise) listOf(COARSE, FINE) else listOf(COARSE)

    /// Whether the system gives the page anything: either location permission held.
    fun granted(holds: (String) -> Boolean): Boolean = holds(COARSE) || holds(FINE)

    /// The accuracy the engine will give: precise only when the fine permission is held.
    fun level(holds: (String) -> Boolean): String = if (holds(FINE)) "precise" else if (holds(COARSE)) "approximate" else "none"

    /// A site's row in You › Site location.
    fun summary(grant: SiteLocationGrant): String =
        if (!grant.allowed) "Blocked" else if (grant.precise) "Allowed · precise" else "Allowed · approximate"

    const val TITLE_SUFFIX = " wants your location"
    const val PROMPT_BODY = "Only this site gets it, only while its page is open. Peel doesn't keep or send it."
    const val PRECISE_LABEL = "Precise location"
    const val PRECISE_NOTE = "On Android, precise location, once allowed, applies to every site you've allowed."
    const val OPEN_SETTINGS = "Open phone settings"
    const val SETTINGS_NOTE = "Location is turned off for Peel in your phone's settings."

    fun title(host: String): String = host + TITLE_SUFFIX
}
