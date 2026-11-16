package com.sphinx.peel.engine

/// What a page host does for the app wallet's bridge, as plain rules (iOS `Apps/Shared/StrutWallet.swift`): strut's UI, the
/// main frame of a page Peel opened, relays an app's `strut/wallet/*` calls to the native handler (strut's specs/WALLET.md §1.1),
/// and Peel pushes each notification back through strut's UI's `window.strutWallet.notify`. The handler is offered to every
/// frame of the origin, the app's own included, so only the main frame at the origin Peel loaded is answered; nothing else learns
/// more than that the call was not answered.
object StrutWallet {
    /// How recent the person's own click or tap must be for a sheet: "the last few seconds" (WALLET.md §5.4). Links out of
    /// the page use [OrgNavigation.TAP_WINDOW_MS] (one second) instead.
    const val GESTURE_WINDOW_MS = 5_000L

    /// Whether the person touched the page within [GESTURE_WINDOW_MS] before `now`.
    fun personActed(lastTouchAt: Long?, now: Long): Boolean = lastTouchAt != null && now - lastTouchAt in 0 until GESTURE_WINDOW_MS

    /// Whether a message may be answered: sent by the main frame, whose origin (the sender's `scheme://host[:port]`) is the one
    /// Peel loaded, a default port written or not (iOS `mayAnswer`). An opaque origin (`null`) never is.
    fun mayAnswer(isMainFrame: Boolean, sourceOrigin: String, origin: String): Boolean {
        if (!isMainFrame) return false
        val seen = UrlParts.parse(sourceOrigin) ?: return false
        val loaded = UrlParts.parse(origin) ?: return false
        val seenHost = seen.host ?: return false
        val loadedHost = loaded.host ?: return false
        val scheme = loaded.scheme.lowercase()
        return seen.scheme.lowercase() == scheme && seenHost == loadedHost && port(seen.port, scheme) == port(loaded.port, scheme)
    }

    private fun port(port: Int?, scheme: String): Int? = port ?: when (scheme) { "https" -> 443; "http" -> 80; else -> null }

    /// What a page's web view offers a push: the address it shows now, and a way to run a script in its main frame.
    interface ScriptTarget {
        val url: String?
        fun run(script: String)
    }

    /// Whether the page still shows strut's UI at `origin` (iOS compares scheme, host and port): a push never goes to a page
    /// that moved elsewhere.
    fun mayPush(currentUrl: String?, origin: String): Boolean {
        val current = currentUrl?.let { UrlParts.origin(it) } ?: return false
        return current == UrlParts.origin(origin)
    }

    /// The script that hands a notification to strut's UI: its own function called with the message as an argument, JSON text
    /// that it parses, never spliced into the script's text as code, so nothing in it can end a string or start code.
    fun pushScript(message: String): String =
        "if (window.strutWallet && typeof window.strutWallet.notify === 'function') window.strutWallet.notify(${scriptLiteral(message)});"

    /// Pushes a notification ([WalletPush.envelope]) into `target`'s main frame while it still shows strut's UI at `origin`.
    /// Returns whether it was sent.
    fun push(envelope: String, into: ScriptTarget, origin: String): Boolean {
        if (!mayPush(into.url, origin)) return false
        into.run(pushScript(envelope))
        return true
    }

    /// `s` as a JavaScript string literal that stays one string whatever it holds: `"`, `\` and control characters escaped, and
    /// `<` (so `</script>` and `<!--` never appear), U+2028 and U+2029 (line terminators in older engines) and any lone surrogate
    /// written as `\uXXXX`. Everything else, `/` included, as it is.
    fun scriptLiteral(s: String): String {
        val out = StringBuilder("\"")
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c.code < 0x20 || c == '<' || c == '\u2028' || c == '\u2029' || c == '\u007f' || c == '\u0085' -> out.append("\\u%04x".format(c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { out.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
            i++
        }
        return out.append('"').toString()
    }
}
