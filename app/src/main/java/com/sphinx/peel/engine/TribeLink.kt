package com.sphinx.peel.engine

import java.net.URI
import java.net.URLDecoder

/// What a pasted or scanned text names when joining a tribe: Peel's own `peel://tribe?pubkey=…&host=…`
/// (read strictly, through [PeelLink]: exactly those two items, no path or fragment), a `sphinx.chat://` tribe
/// link (`action=tribeV2` with `pubkey`, or the older `action=tribe` with `uuid`), a
/// `pubkey@host`, `host/pubkey` or `pubkey/host`, a bare pubkey or a bare uuid. Pure, so
/// JVM-tested. Ported in spirit from PeelCore's `TribeLink` (Tribe.swift); the loose
/// `host/pubkey` forms are this port's own.
object TribeLink {
    /// Who to join: exactly one of [pubkey] (66 hex characters, lower case) and [uuid] is set.
    /// [host] is the tribes server the text named, if it named one.
    data class Target(val pubkey: String?, val uuid: String?, val host: String?) {
        /// What the registry is asked for.
        val id: String get() = pubkey ?: uuid ?: ""
    }

    class Problem(message: String) : Exception(message)

    private const val MAX_LENGTH = 2_000
    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$")
    private val UUID = Regex("^[A-Za-z0-9_-]{16,128}$")

    fun parse(text: String): Target {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) throw notATribe()
        if (trimmed.lowercase().startsWith("peel://")) return peelLink(trimmed)
        if (trimmed.lowercase().startsWith("sphinx.chat://")) return link(trimmed)
        if (trimmed.lowercase().startsWith("peel://tribe")) return TribeLinks.parsePeel(trimmed)?.let { Target(it.pubkey, null, it.host) } ?: throw notATribe()
        if (isPubkey(trimmed)) return Target(trimmed.lowercase(), null, null)
        for (separator in listOf("@", "/")) {
            val i = trimmed.indexOf(separator)
            if (i <= 0 || i != trimmed.lastIndexOf(separator)) continue
            val left = trimmed.substring(0, i)
            val right = trimmed.substring(i + 1)
            // `pubkey@host`, `pubkey/host`, `host/pubkey`.
            if (isPubkey(left) && isHost(right)) return Target(left.lowercase(), null, right.lowercase())
            if (separator == "/" && isHost(left) && isPubkey(right)) return Target(right.lowercase(), null, left.lowercase())
            if (isHost(left) && UUID.matches(right) && separator == "/") return Target(null, right, left.lowercase())
        }
        if (UUID.matches(trimmed)) return Target(null, trimmed, null)
        throw notATribe()
    }

    private fun peelLink(text: String): Target {
        val values = PeelLink.values(text, host = "tribe", names = setOf("pubkey", "host")) ?: throw notATribe()
        val key = values.getValue("pubkey")
        val host = values.getValue("host")
        if (!isPubkey(key) || !isHost(host)) throw notATribe()
        return Target(key.lowercase(), null, host.lowercase())
    }

    private fun link(text: String): Target {
        val uri = runCatching { URI(text) }.getOrNull() ?: throw notATribe()
        val items = queryItems(uri.rawQuery)
        if (items["action"] != "tribeV2" && items["action"] != "tribe") throw notATribe()
        val host = items["host"]?.trim()?.takeIf { it.isNotEmpty() }?.also { if (!isHost(it)) throw notATribe() }?.lowercase()
        val key = items["pubkey"]?.trim()
        if (key != null) {
            if (!isPubkey(key)) throw notATribe()
            return Target(key.lowercase(), null, host)
        }
        val uuid = items["uuid"]?.trim()
        if (uuid != null && UUID.matches(uuid)) return Target(null, uuid, host)
        throw notATribe()
    }

    /// The tribes server to ask: the account's own. A link naming a different one is
    /// refused rather than followed, as a message's host never is (`Engine.refreshTribeNames`):
    /// whoever sends a link must not choose where Peel connects.
    fun hostFor(target: Target, accountHost: String?): String {
        val own = accountHost?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: throw Problem("This account has no tribes server yet. Try again once it is connected.")
        val named = target.host
        if (named != null && named != own) throw Problem("That tribe is on another tribes server ($named), not this account's ($own).")
        return own
    }

    internal fun isPubkey(text: String): Boolean =
        text.length == 66 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    internal fun isHost(text: String): Boolean = HOST.matches(text) && !isPubkey(text)

    private fun notATribe() = Problem("That is not a tribe link, a tribe's public key or a tribe uuid.")

    private fun queryItems(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val items = LinkedHashMap<String, String>()
        for (part in rawQuery.split("&")) {
            val i = part.indexOf('=')
            val name = if (i < 0) part else part.substring(0, i)
            val value = if (i < 0) "" else part.substring(i + 1)
            items.putIfAbsent(name, runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value))
        }
        return items
    }
}

/// The one place a tribe's link is built and read, in both forms: `sphinx.chat://?action=tribeV2&pubkey=…&host=…` (the official
/// apps' form, [official]/[build]) and `peel://tribe?pubkey=…&host=…` (Peel's own, [peel] — what Share/Copy/QR make today,
/// since the Android intent filter can match it by host). [parse] reads either form a message or an opened link names.
/// Pure, so JVM-tested (docs/phases/23-tribe-management.md, "Tribe links").
object TribeLinks {
    /// A tribe a link names: its [pubkey] (66 hex, lower case) and the tribes server [host] it named, if any.
    data class Named(val pubkey: String, val host: String?)

    private const val MAX_LENGTH = 2_000

    /// A tribe's link in the official apps' format (`sphinx.chat://`); Peel itself shares [peel]'s form instead
    /// (`ChatDetails.tribeLink`, since the already-merged "share as peel://tribe links" change) -- kept so
    /// [parse]'s round trip of both formats stays tested.
    fun build(pubkey: String, host: String?): String? = official(pubkey, host)

    /// The official apps' form.
    fun official(pubkey: String, host: String?): String? =
        host?.takeIf { it.isNotEmpty() }?.let { "sphinx.chat://?action=tribeV2&pubkey=$pubkey&host=$it" }

    /// Peel's own form.
    fun peel(pubkey: String, host: String?): String? =
        host?.takeIf { it.isNotEmpty() }?.let { "peel://tribe?pubkey=$pubkey&host=$it" }

    /// The tribe a link of either form names, or null for anything else (a link of another action, an older uuid form, a bad
    /// pubkey or host). Only a whole link: the scheme (any case), the action, a 66-hex `pubkey` and, when there is one, a plain `host`;
    /// extra parameters are ignored. A `peel://tribe` link must name its host.
    fun parse(text: String): Named? = parsePeel(text) ?: parseOfficial(text)

    fun parseOfficial(text: String): Named? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH || !t.lowercase().startsWith("sphinx.chat://")) return null
        val items = items(t) ?: return null
        if (items["action"] != "tribeV2") return null
        return named(items, hostRequired = false)
    }

    fun parsePeel(text: String): Named? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH) return null
        if (!t.lowercase().startsWith("peel://tribe")) return null
        val after = t.substring("peel://tribe".length)
        if (!after.startsWith("?") && !after.startsWith("/?")) return null
        val items = items(t) ?: return null
        return named(items, hostRequired = true)
    }

    private fun named(items: Map<String, String>, hostRequired: Boolean): Named? {
        val key = items["pubkey"]?.trim() ?: return null
        if (!TribeLink.isPubkey(key)) return null
        val host = items["host"]?.trim()?.takeIf { it.isNotEmpty() }
        if (host == null && hostRequired) return null
        if (host != null && !TribeLink.isHost(host)) return null
        return Named(key.lowercase(), host?.lowercase())
    }

    private fun items(text: String): Map<String, String>? {
        val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
        val raw = uri.rawQuery?.takeIf { it.isNotEmpty() } ?: return null
        val items = LinkedHashMap<String, String>()
        for (part in raw.split("&")) {
            val i = part.indexOf('=')
            val name = if (i < 0) part else part.substring(0, i)
            val value = if (i < 0) "" else part.substring(i + 1)
            items.putIfAbsent(name, runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value))
        }
        return items
    }

    private val LINK = Regex("(?<![A-Za-z0-9])(?:sphinx\\.chat://\\?|peel://tribe\\?)[^\\s<>\"\\[\\]{}|\\\\^]+", RegexOption.IGNORE_CASE)

    /// Where tribe links sit in a message: (start, end) of each, trailing sentence punctuation and an unbalanced `)` left out.
    fun inText(text: String): List<IntRange> {
        if (!text.contains("://")) return emptyList()
        val found = ArrayList<IntRange>()
        for (m in LINK.findAll(text)) {
            var end = m.range.last + 1
            val start = m.range.first
            while (end > start) {
                val c = text[end - 1]
                val drop = when {
                    c in ".,;:!?'*_~" -> true
                    c == ')' -> text.substring(start, end).let { s -> s.count { it == ')' } > s.count { it == '(' } }
                    else -> false
                }
                if (!drop) break
                end--
            }
            if (parse(text.substring(start, end)) != null) found.add(start until end)
        }
        return found
    }
}

/// What a tapped or opened tribe link does: the tribe's chat when this account already is in it, else the Join screen with the
/// link filled in (looking up prices; joining and paying still wait for the person's tap).
sealed interface TribeRoute {
    data class OpenChat(val chat: String) : TribeRoute
    data class Join(val link: String) : TribeRoute

    companion object {
        /// The route for [link], or null when it is no tribe link. [joined] says whether this account is in the tribe (its pubkey is the chat's id):
        /// a left tribe has no chat here any more, so it is not joined.
        fun decide(link: String, joined: (String) -> Boolean): TribeRoute? {
            val named = TribeLinks.parse(link) ?: return null
            return if (joined(named.pubkey)) OpenChat(named.pubkey) else Join(link.trim())
        }
    }
}
