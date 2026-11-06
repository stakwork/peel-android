package com.sphinx.peel.engine

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/// Time zones, as the official apps and iOS Peel share them (`TimeZones.swift`
/// in peel): a message's JSON may carry `"metadata": "{\"tz\":\"GMT+1\"}"`, the
/// sender's offset when they sent it. Pure, so JVM-tested.
/// Not ported: private tribes (Android does not know whether a tribe is
/// private, so it sends the offset in direct messages only), the synced
/// `timezone` preference (the choice is kept on this device), the account-wide
/// zone, and a per-message "their time" tooltip.
object TimeZones {
    /// The picker's first entry: no zone chosen, the device's is used.
    const val DEFAULT_CHOICE = "Use device settings"

    /// The longest `tz` value kept from a message.
    private const val MAX_LENGTH = 64

    /// Newer offsets only: older than this, their time is unknown.
    const val FRESHNESS_MS = 90L * 24 * 3600 * 1000

    /// The picker's entries: the default, then every zone Java knows.
    val choices: List<String> by lazy {
        listOf(DEFAULT_CHOICE) + ZoneId.getAvailableZoneIds().filterNot { it.startsWith("SystemV/") }.sorted()
    }

    /// `zone`'s offset at `at`, summer time included: "GMT+1", "GMT-5", "GMT+5:30", "GMT+0".
    fun format(zone: ZoneId, at: Instant = Instant.now()): String {
        val minutes = zone.rules.getOffset(at).totalSeconds / 60
        val sign = if (minutes < 0) "-" else "+"
        val hours = abs(minutes) / 60
        val rest = abs(minutes) % 60
        return if (rest == 0) "GMT$sign$hours" else "GMT$sign$hours:${rest.toString().padStart(2, '0')}"
    }

    /// The zone an identifier names (`Europe/London`, `EST`), or null.
    fun zoneNamed(identifier: String?): ZoneId? =
        identifier?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it, ZoneId.SHORT_IDS) }.getOrNull() }

    /// The zone a received value names: `GMT` or `UTC`, alone or with `±H` or
    /// `±H:MM` (up to 14 hours), as a fixed offset; or an IANA name this device
    /// knows. Anything else is null.
    fun zone(text: String): ZoneId? {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_LENGTH) return null
        if (text.contains("/")) {
            if (!text.all { it.isAsciiNameChar() } || text.contains("..") || text.startsWith("/")) return null
            return runCatching { ZoneId.of(text) }.getOrNull()
        }
        if (!text.startsWith("GMT") && !text.startsWith("UTC")) return null
        val offset = text.drop(3)
        if (offset.isEmpty()) return ZoneOffset.UTC
        val sign = when (offset[0]) { '+' -> 1; '-' -> -1; else -> return null }
        val parts = offset.drop(1).split(":")
        if (parts.size > 2) return null
        val hours = number(parts[0], 1..2) ?: return null
        val minutes = if (parts.size == 2) number(parts[1], 2..2) ?: return null else 0
        if (minutes >= 60 || hours * 60 + minutes > 14 * 60) return null
        return ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60))
    }

    private fun Char.isAsciiNameChar() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in "/_-+"

    private fun number(text: String, digits: IntRange): Int? =
        text.takeIf { it.length in digits && it.all { c -> c in '0'..'9' } }?.toInt()

    /// The `tz` in a message body's metadata, if it has one.
    fun sent(body: String?): String? {
        if (body == null) return null
        return runCatching {
            JSONObject(JSONObject(body).optString("metadata").takeIf { it.isNotEmpty() } ?: return null).optString("tz")
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /// `body` with its metadata's `tz` set to `offset` (a JSON object of strings in, one out).
    fun setting(offset: String?, body: JSONObject): JSONObject {
        if (offset != null) body.put("metadata", JSONObject().put("tz", offset).toString())
        return body
    }

    /// "6 h ahead", "6 h 30 min behind", "30 min ahead"; null when the same.
    fun offsetText(seconds: Int): String? {
        val minutes = abs(seconds) / 60
        if (minutes == 0) return null
        val parts = listOfNotNull(
            (minutes / 60).takeIf { it > 0 }?.let { "$it h" },
            (minutes % 60).takeIf { it > 0 }?.let { "$it min" },
        )
        return parts.joinToString(" ") + if (seconds > 0) " ahead" else " behind"
    }

    /// "02:14 local time · 6 h ahead": their time now, when their offset is not yours.
    /// Time as `hh:mm`, like iOS's `Formatting.time` in the device's locale 24 h/12 h choice.
    fun localTimeLine(zone: ZoneId, now: Instant, mine: ZoneId, time: (Instant, ZoneId) -> String): String? {
        val difference = zone.rules.getOffset(now).totalSeconds - mine.rules.getOffset(now).totalSeconds
        val offset = offsetText(difference) ?: return null
        return "${time(now, zone)} local time · $offset"
    }

    /// The newest readable offset among `bodies` (newest first) as a zone.
    fun newest(bodies: List<String?>): ZoneId? = bodies.asSequence().mapNotNull { sent(it)?.let(::zone) }.firstOrNull()
}
