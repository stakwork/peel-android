package com.sphinx.peel.engine

import java.net.URI
import java.security.SecureRandom
import org.json.JSONObject

/// A LiveKit call link as the official apps post them:
/// `https://<call server>/rooms/sphinx.call.<name>`, with `startAudioOnly=true`
/// for an audio call. A message carries it bare (the Mac app) or as
/// `call::{"link":…}` (iOS, Android, and this port). Ported from PeelCore's
/// `Call.swift` `CallLink`, trimmed: no Hive (`CallLink.hive`, Stadeum's own
/// call-with-Jamie flow — this port has no Hive/Workspaces feature to need
/// it from) and no `callKey` parsing on the way in, since nothing here ever
/// reads one — but still parsed and round-tripped, in case a link posted by
/// another client carries one.
///
/// Anyone can write a call message, so a link is only ever a link that
/// passed [parse]'s checks against the account's own call server: https
/// (plain http only when that server is this device), its exact host and
/// port, no credentials or fragment, the path `/rooms/<room>` with a strict
/// room name, and only known query keys. Everything else in it is dropped —
/// [url] is rebuilt from the parsed parts, never the text received.
data class CallLink(
    val server: String,
    private val scheme: String,
    private val host: String,
    private val port: Int,
    val room: String,
    val audioOnly: Boolean,
    val callKey: String?,
    val hiveToken: String?,
) {
    /// The link, rebuilt from its parts with every query value strictly
    /// percent-encoded.
    val url: String
        get() {
            val authority = if (hasDefaultPort(scheme, port)) host else "$host:$port"
            val items = buildList {
                if (audioOnly) add("startAudioOnly=true")
                callKey?.let { add("callKey=${encode(it)}") }
                hiveToken?.let { add("hiveToken=${encode(it)}") }
            }
            val query = if (items.isEmpty()) "" else "?" + items.joinToString("&")
            return "$scheme://$authority$ROOMS_PATH$room$query"
        }

    /// The content of a call message in the `call::` form iOS and Android
    /// send, which all three official apps read as a call. The link holds
    /// only URL-safe characters, so it needs no JSON escaping.
    val message: String
        get() = "$PREFIX{\"link\":\"$url\",\"recurring\":false,\"cron\":\"\"}"

    companion object {
        const val PREFIX = "call::"
        const val ROOM_PREFIX = "sphinx.call."
        private const val ROOMS_PATH = "/rooms/"
        private val QUERY_KEYS = setOf("startAudioOnly", "callKey", "hiveToken")
        private const val MAX_CONTENT = 4096
        private val random = SecureRandom()

        /// A new room on `server` (`host[:port]`) named with 128 random bits
        /// from the system's secure generator, as the words are, where the
        /// official apps' timestamps can be guessed from when a call started.
        fun new(server: String, audioOnly: Boolean): CallLink {
            val base = baseURL(server) ?: throw IllegalArgumentException("the call server $server is unreadable")
            val name = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            return CallLink(server, base.first, base.second, base.third, ROOM_PREFIX + name, audioOnly, null, null)
        }

        /// A link on `server` (`host[:port]`), checked as this class's own
        /// doc comment describes. Null for anything that does not pass.
        fun parse(link: String, server: String): CallLink? {
            val base = baseURL(server) ?: return null
            if (link.toByteArray(Charsets.UTF_8).size > MAX_CONTENT) return null
            val uri = try {
                URI(link)
            } catch (e: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (scheme != base.first || host != base.second) return null
            val port = if (uri.port == -1) defaultPort(scheme) else uri.port
            if (port != base.third) return null
            if (uri.userInfo != null || uri.fragment != null) return null
            val path = uri.rawPath ?: return null
            if (!path.startsWith(ROOMS_PATH)) return null
            val room = path.removePrefix(ROOMS_PATH)
            if (!isRoom(room)) return null

            val query = mutableMapOf<String, String>()
            for (raw in (uri.rawQuery ?: "").split("&").filter { it.isNotEmpty() }) {
                val eq = raw.indexOf('=')
                val name = if (eq >= 0) raw.substring(0, eq) else raw
                if (name !in QUERY_KEYS || query.containsKey(name)) return null
                val rawValue = if (eq >= 0) raw.substring(eq + 1) else return null
                val value = decode(rawValue) ?: return null
                if (name != "startAudioOnly" && !isToken(value)) return null
                query[name] = value
            }
            return CallLink(
                server, scheme, host, port, room, query["startAudioOnly"] == "true",
                query["callKey"], query["hiveToken"],
            )
        }

        /// The link a message carries, checked against this account's own
        /// call server — PeelCore's own `CallLink.init(in:server:)`: a call
        /// message, or (for interop with the Mac app, which never sends the
        /// `call::` wrapper) plain text that is only a link. `isCallType`
        /// is the caller's own `row.type == CALL_MESSAGE_TYPE` check, kept
        /// out of this file since it's a message-storage concern, not a
        /// link-parsing one.
        fun inMessage(content: String, isCallType: Boolean, server: String): CallLink? {
            if (!isCallType && !content.contains(ROOM_PREFIX)) return null
            val text = linkText(content) ?: return null
            return parse(text, server)
        }

        /// What a call message's content links to, unchecked: the `call::`
        /// form's link, or the content itself. A call message that is not a
        /// valid call shows this as plain text.
        fun linkText(content: String): String? {
            if (content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT) return null
            val text = content.trim()
            if (!text.startsWith(PREFIX)) return text
            return try {
                JSONObject(text.removePrefix(PREFIX)).optString("link").takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }

        fun isRoom(room: String): Boolean {
            if (!room.startsWith(ROOM_PREFIX)) return false
            val name = room.removePrefix(ROOM_PREFIX)
            return name.length in 1..128 && name.all { isAlphanumeric(it) || it in "._-" }
        }

        /// A Hive key or token: base64, base64url or JWT characters, at most 2 KB.
        private fun isToken(value: String): Boolean =
            value.length in 1..2048 && value.all { isAlphanumeric(it) || it in "-._~+/=" }

        private fun isAlphanumeric(c: Char): Boolean = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'

        /// Unreserved characters only, so `+`, `/` and `=` are always escaped.
        /// Visibility relaxed to `internal` (from `private`) so `CallServer`'s
        /// own ticket request — the same account's call server, the same
        /// percent-encoding rules — can reuse this rather than a second copy.
        internal fun encode(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val unsigned = byte.toInt() and 0xFF
                val c = unsigned.toChar()
                if (unsigned < 0x80 && (isAlphanumeric(c) || c in "-._~")) append(c) else append("%%%02X".format(unsigned))
            }
        }

        /// Percent-decodes `%XX` sequences only — unlike `URLDecoder.decode`,
        /// a `+` stays a literal `+`, matching Swift's
        /// `removingPercentEncoding` (which `CallLink.init(link:server:)`
        /// relies on for its query values).
        private fun decode(value: String): String? {
            val bytes = mutableListOf<Byte>()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%') {
                    if (i + 2 >= value.length) return null
                    val hex = value.substring(i + 1, i + 3)
                    val b = hex.toIntOrNull(16) ?: return null
                    bytes.add(b.toByte())
                    i += 3
                } else {
                    bytes.add(c.code.toByte())
                    i += 1
                }
            }
            return try {
                bytes.toByteArray().toString(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }

        internal fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

        private fun hasDefaultPort(scheme: String, port: Int): Boolean = port == defaultPort(scheme)

        /// `name[:port]` and nothing else, so a link cannot smuggle a path or
        /// credentials; http for this device, https for everything else.
        /// Null for anything that is not a plain host. `internal`, same
        /// reason as [encode]: `CallServer`'s own ticket request resolves
        /// the call server's base URL the exact same way a link does.
        internal fun baseURL(server: String): Triple<String, String, Int>? {
            if (server.isEmpty() || !server.all { isAlphanumeric(it) || it in ".-:" }) return null
            val colon = server.indexOf(':')
            val host = (if (colon >= 0) server.substring(0, colon) else server).lowercase()
            val port = if (colon >= 0) server.substring(colon + 1).toIntOrNull() ?: return null else null
            val scheme = if (ServerAddress.isLoopback(host)) "http" else "https"
            return Triple(scheme, host, port ?: defaultPort(scheme))
        }
    }
}
