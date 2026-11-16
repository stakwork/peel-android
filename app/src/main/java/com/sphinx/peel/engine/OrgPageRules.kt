package com.sphinx.peel.engine

/// What an org page's web view may save, and how it words a page that did not load: plain rules, without any web view's
/// types (iOS `PeelUI/OrgPageRules.swift`), which `ui/apps/OrgPageHost.kt` applies.
object OrgPageRules {
    /// The alert's title and its two messages when a download is not kept (iOS `OrgPageSheet`).
    const val NOTICE_TITLE = "Couldn't save it"
    const val NO_ROOM = "There was no room to keep it."
    const val DOWNLOAD_FAILED = "The download did not finish."

    /// The failure screen's title and its button (iOS `OrgPageSheet`).
    const val FAILURE_TITLE = "Couldn't open it"
    const val TRY_AGAIN = "Try again"

    /// Whether a navigation may become a download: only strut's own page (the main frame, started by the main frame, on the
    /// org's strut's UI), never a frame in it, which holds what the org or an agent wrote.
    fun mayDownload(isMainFrame: Boolean, sourceIsMainFrame: Boolean, served: Boolean): Boolean =
        isMainFrame && sourceIsMainFrame && served

    /// The name a download is saved as: what a server suggested, cut to its last path component, so it cannot leave its folder;
    /// `download` when nothing usable is left. (Cut at 200 code points; iOS cuts at 200 characters.)
    fun filename(suggested: String): String {
        val last = suggested.split('/', '\\').lastOrNull { it.isNotEmpty() }
            ?.filter { Character.getType(it) != Character.CONTROL.toInt() }
            ?.trim { Character.getType(it) == Character.SPACE_SEPARATOR.toInt() || it == '\t' } ?: ""
        if (last.isEmpty() || last == "." || last == "..") return "download"
        val cut = if (last.codePointCount(0, last.length) > 200) last.substring(0, last.offsetByCodePoints(0, 200)) else last
        return cut
    }

    /// Why a main-frame answer with this HTTP `status` is not shown, or null when it is one the page may show.
    fun failure(status: Int): String? {
        if (status < 400) return null
        return when (status) {
            401, 403 -> "The workspace did not let this device open it (HTTP $status)."
            404 -> "The workspace has nothing there (HTTP 404)."
            in 500..Int.MAX_VALUE -> "The workspace had a problem answering (HTTP $status)."
            else -> "The workspace answered with HTTP $status."
        }
    }

    /// The name a server suggests for a download (iOS `suggestedFilename`): the `Content-Disposition`'s `filename*` or
    /// `filename`, else the last segment of the address's path; unsanitised, [filename] does that.
    fun suggestedName(contentDisposition: String?, url: String): String {
        contentDisposition?.let { header ->
            Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { m ->
                val decoded = percentDecode(m.groupValues[2].trim(), runCatching { charset(m.groupValues[1].ifEmpty { "UTF-8" }) }.getOrDefault(Charsets.UTF_8))
                if (decoded.isNotEmpty()) return decoded
            }
            Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { m ->
                val value = (m.groups[2]?.value ?: m.groupValues[1]).trim()
                if (value.isNotEmpty()) return value
            }
        }
        val path = UrlParts.parse(url)?.path.orEmpty()
        val last = path.split('/').lastOrNull { it.isNotEmpty() } ?: return ""
        return percentDecode(last, Charsets.UTF_8)
    }

    private fun percentDecode(s: String, charset: java.nio.charset.Charset): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val hex = if (s[i] == '%' && i + 3 <= s.length) s.substring(i + 1, i + 3) else null
            if (hex != null && hex.all { it in "0123456789abcdefABCDEF" }) {
                out.write(hex.toInt(16)); i += 3
            } else {
                out.write(s[i].toString().toByteArray(charset)); i++
            }
        }
        return String(out.toByteArray(), charset)
    }
}
