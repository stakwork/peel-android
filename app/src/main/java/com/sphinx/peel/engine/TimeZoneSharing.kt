package com.sphinx.peel.engine

import java.util.TimeZone
import org.json.JSONObject

/// "Share my time zone with <chat>": the per-chat setting, how it travels in
/// the account's preferences file and how it becomes message metadata.
/// Ported from the iPhone's `TimeZoneSharing` (`Preferences.swift`) and
/// `TimeZoneOffset.format` (`TimeZones.swift`), as the official Sphinx apps
/// do it: a message carries `metadata = {"tz":"GMT+1"}` (a JSON string) when
/// the switch is on and the chat is a contact or a PRIVATE tribe.
///
/// The preferences file item is `key = "timezone"`, `identifier = <chat pubkey>`,
/// `value = {"timezone_enabled":"true|false","timezone_identifier":"<IANA id>" or ""}`
/// ("" = the device's own zone). Legacy `timezoneEnabled`/`timezoneIdentifier`
/// are read too. Pure apart from the JSON helpers of the local store, so it is
/// unit-tested on the JVM. Not ported: reading a received `tz` (see
/// `docs/phases/22-tribe-details-parity.md`).
object TimeZoneSharing {
    const val KEY = "timezone"
    const val ENABLED_FIELD = "timezone_enabled"
    const val IDENTIFIER_FIELD = "timezone_identifier"
    const val LEGACY_ENABLED_FIELD = "timezoneEnabled"
    const val LEGACY_IDENTIFIER_FIELD = "timezoneIdentifier"

    /// A chat's choice. `identifier` "" means the device's zone ("Automatic");
    /// `date` is whole seconds, 0 for one never chosen on a device.
    data class Choice(val enabled: Boolean = true, val identifier: String = "", val date: Long = 0)

    /// An item as the file holds it: the choice if it reads, and its date if it reads.
    class FileItem(val choice: Choice?, val date: Long?)

    /// `GMT` + sign + hours (+ `:MM` when the minutes are not zero): GMT+1, GMT-5, GMT+5:30, GMT+0.
    fun formatOffset(offsetSeconds: Int): String {
        val minutes = offsetSeconds / 60
        val abs = Math.abs(minutes)
        val sign = if (minutes < 0) "-" else "+"
        val rest = if (abs % 60 != 0) ":" + (abs % 60).toString().padStart(2, '0') else ""
        return "GMT$sign${abs / 60}$rest"
    }

    /// Whether `identifier` is a zone this device knows.
    fun isKnown(identifier: String): Boolean = identifier.isNotEmpty() && identifier in TimeZone.getAvailableIDs()

    /// The zone's offset now, in seconds. An empty or unknown identifier means `device`.
    fun offsetSeconds(identifier: String, nowMillis: Long, device: TimeZone = TimeZone.getDefault()): Int {
        val zone = if (isKnown(identifier)) TimeZone.getTimeZone(identifier) else device
        return zone.getOffset(nowMillis) / 1000
    }

    /// `"GMT+1"`-style label of a zone now (the picker's own).
    fun offsetLabel(identifier: String, nowMillis: Long, device: TimeZone = TimeZone.getDefault()): String =
        formatOffset(offsetSeconds(identifier, nowMillis, device))

    /// The message metadata for a send, or null when none goes: only where the chat
    /// `offered` the setting (a contact or a private tribe) and the switch is on.
    /// A chat never chosen on defaults to on.
    fun metadata(choice: Choice?, offered: Boolean, nowMillis: Long, device: TimeZone = TimeZone.getDefault()): String? {
        if (!offered) return null
        val chosen = choice ?: Choice()
        if (!chosen.enabled) return null
        return "{\"tz\":\"" + offsetLabel(chosen.identifier, nowMillis, device) + "\"}"
    }

    /// The picker's list: "Automatic · <device id>" ("" ) first, then every available id, sorted,
    /// narrowed by `query` (case-insensitive, `_` read as a space).
    data class Option(val identifier: String, val title: String)

    fun options(query: String, deviceId: String, available: Collection<String> = TimeZone.getAvailableIDs().toList()): List<Option> {
        val needle = query.trim().lowercase().replace('_', ' ')
        fun matches(text: String) = needle.isEmpty() || text.lowercase().replace('_', ' ').contains(needle)
        val automatic = Option("", "Automatic · $deviceId")
        val rest = available.distinct().sorted().filter(::matches).map { Option(it, it.replace('_', ' ')) }
        return (if (matches(automatic.title)) listOf(automatic) else emptyList()) + rest
    }

    private fun flag(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.lowercase()) { "true" -> true; "false" -> false; else -> null }
        else -> null
    }

    /// A file value's fields as a choice: null when `enabled` is there but not a flag.
    /// An absent flag means on, an absent or non-text identifier the device's zone.
    fun decode(enabled: Any?, identifier: Any?, legacyEnabled: Any?, legacyIdentifier: Any?, date: Long): Choice? {
        val flagValue = enabled ?: legacyEnabled
        val on = if (flagValue == null) true else flag(flagValue) ?: return null
        val id = ((identifier ?: legacyIdentifier) as? String)?.trim() ?: ""
        return Choice(on, id, date)
    }

    sealed interface Outcome {
        data object Nothing : Outcome
        /// Put this device's choice in the file.
        data class Upload(val choice: Choice) : Outcome
        /// Store this choice (with the file's date) here.
        data class Take(val choice: Choice) : Outcome
    }

    /// The per-chat merge, the same rules as `PreferencesMerge.decide` for one item:
    /// newer here goes up; otherwise the file's is taken if it reads and differs
    /// (or carries a different date); in the file only, taken; here only, up if
    /// it was chosen on a device (date above 0).
    fun decide(file: FileItem?, local: Choice?): Outcome {
        if (file == null) return if (local != null && local.date > 0) Outcome.Upload(local) else Outcome.Nothing
        if (local != null && local.date > (file.date ?: 0L)) return Outcome.Upload(local)
        val incoming = file.choice ?: return Outcome.Nothing
        if (local != null && local.enabled == incoming.enabled && local.identifier == incoming.identifier &&
            (file.date == null || file.date == local.date)
        ) return Outcome.Nothing
        return Outcome.Take(incoming.copy(date = file.date ?: 0L))
    }

    /// The local store, one JSON object in a setting: `{chat: {"e": bool, "i": id, "d": date}}`.
    fun decodeStore(text: String?): Map<String, Choice> {
        if (text.isNullOrEmpty()) return emptyMap()
        return try {
            val root = JSONObject(text)
            root.keys().asSequence().mapNotNull { chat ->
                val item = root.optJSONObject(chat) ?: return@mapNotNull null
                chat to Choice(item.optBoolean("e", true), item.optString("i", ""), item.optLong("d", 0L))
            }.toMap()
        } catch (e: org.json.JSONException) {
            emptyMap()
        }
    }

    fun encodeStore(store: Map<String, Choice>): String {
        val root = JSONObject()
        for ((chat, choice) in store) {
            root.put(chat, JSONObject().put("e", choice.enabled).put("i", choice.identifier).put("d", choice.date))
        }
        return root.toString()
    }

    /// Which tribes are private (`{pubkey: bool}`), as the registry last said.
    fun decodeFlags(text: String?): Map<String, Boolean> {
        if (text.isNullOrEmpty()) return emptyMap()
        return try {
            val root = JSONObject(text)
            root.keys().asSequence().associateWith { root.optBoolean(it, false) }
        } catch (e: org.json.JSONException) {
            emptyMap()
        }
    }

    fun encodeFlags(flags: Map<String, Boolean>): String {
        val root = JSONObject()
        for ((chat, flag) in flags) root.put(chat, flag)
        return root.toString()
    }
}
