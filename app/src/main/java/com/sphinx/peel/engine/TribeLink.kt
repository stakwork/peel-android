package com.sphinx.peel.engine

import java.net.URI
import java.net.URLDecoder

/// What a pasted or scanned text names when joining a tribe: a `sphinx.chat://` tribe
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
        if (trimmed.lowercase().startsWith("sphinx.chat://")) return link(trimmed)
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

    private fun isPubkey(text: String): Boolean =
        text.length == 66 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun isHost(text: String): Boolean = HOST.matches(text) && !isPubkey(text)

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
