package com.sphinx.peel.engine

import com.sphinx.peel.diagnostics.DetailRedaction

/// What the page host writes to logcat under [TAG] (docs/phases/42-page-host-logs.md), in release builds too: a person can only
/// run a release-signed Peel without losing their account, and the page host had never been run on a device. Only what helps
/// tell why a page shows nothing: an address's origin (never its path, query or fragment: an org page's address names the app and
/// what it was opened with), numbers, feature flags, an exception's class (never its message), and page text through
/// [DetailRedaction] (no addresses past their host, no long tokens or hex, at most 200 characters). No header, no token, no invite
/// is ever passed here.
object PageLog {
    const val TAG = "PeelPage"

    /// `scheme://host[:port]` (lower-cased, without credentials), or `scheme:` for an address with no host (`about:`, `data:`),
    /// or `-` for none and `?` for one that is not an address.
    fun origin(url: String?): String {
        if (url.isNullOrBlank()) return "-"
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme.isEmpty() || !scheme.first().isLetter() || !scheme.all { it.isLetterOrDigit() || it in "+.-" }) return "?"
        if (!url.startsWith("$scheme://", ignoreCase = true)) return "$scheme:"
        val authority = url.substring(scheme.length + 3).takeWhile { it !in "/?#\\" }
        return "$scheme://" + authority.substringAfterLast('@').lowercase()
    }

    /// A script's file name, without its folder, query or fragment.
    fun file(source: String?): String =
        source?.substringBefore('#')?.substringBefore('?')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }?.take(80) ?: "-"

    /// Text the page or the web view wrote, made safe to keep.
    fun text(words: CharSequence?): String = DetailRedaction.redact(words?.toString().orEmpty())

    /// An exception, by its class alone (a message can carry anything).
    fun threw(error: Throwable): String = "threw:" + (error.javaClass.simpleName.ifEmpty { "Throwable" })

    fun step(name: String, url: String?, generation: Int): String = "$name ${origin(url)} gen=$generation"

    fun console(level: String, message: CharSequence?, source: String?, line: Int): String =
        "console $level ${text(message)} @${file(source)}:$line"

    fun error(url: String?, code: Int, description: CharSequence?): String = "error ${origin(url)} code=$code ${text(description)}"

    fun httpError(url: String?, status: Int): String = "httpError ${origin(url)} status=$status"

    /// A page's request for the camera or microphone: who asked, for what (the resource's last word, e.g. `VIDEO_CAPTURE`), and
    /// what it was given.
    fun permission(asker: String?, asked: List<String>, granted: List<String>): String =
        "permission ${origin(asker)} asked=${resources(asked)} granted=${resources(granted)}"

    private fun resources(list: List<String>): String = list.joinToString(",") { it.substringAfterLast('.') }.ifEmpty { "-" }

    fun renderGone(didCrash: Boolean, priorityAtExit: Int, current: Boolean): String =
        "renderGone didCrash=$didCrash priorityAtExit=$priorityAtExit current=$current"

    /// The web view as made: which WebView this is, what it supports, and how each optional piece went in.
    fun made(
        generation: Int,
        webView: String?,
        multiProfile: Boolean,
        documentStartScript: Boolean,
        webMessageListener: Boolean,
        profile: String,
        tokenScript: String,
        wallet: String,
    ): String =
        "webView gen=$generation package=${webView ?: "-"} MULTI_PROFILE=$multiProfile DOCUMENT_START_SCRIPT=$documentStartScript " +
            "WEB_MESSAGE_LISTENER=$webMessageListener profile=$profile tokenScript=$tokenScript wallet=$wallet"
}
