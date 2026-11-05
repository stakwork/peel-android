package com.sphinx.peel.engine

import org.json.JSONArray
import org.json.JSONObject

/// The account's preferences file, as the official Sphinx apps and
/// PeelCore read and write it on the memes server: `{"items": [{"key",
/// "identifier", "date", "value"}, ...], ...other top-level fields}`,
/// RNCryptor-v3 encrypted under the account's own node secret key
/// (`Engine.preferencesKey`) — PeelCore's `PreferencesFile`/
/// `Engine+Preferences.swift`, trimmed hard: PeelCore's own version
/// syncs ten-plus keys (colours, Face ID, the agent's credential,
/// feed subscriptions, …) through a general per-key merge policy
/// registry. This port only ever reads or writes ONE item in the whole
/// file — `"tip_amount"` (the boost amount) for the account-wide
/// identifier `"0"` — and leaves every other item and every other
/// top-level field exactly as it came, byte for byte, so this can
/// never clobber a preference another Sphinx client (official or
/// PeelCore) set. A general registry for more keys is real future
/// work, not something this shape should grow into by accretion.
class PreferencesFile private constructor(private val root: JSONObject, private val items: MutableList<JSONObject>) {
    companion object {
        /// PeelCore's own `Preferences.accountWide` — the identifier for
        /// a preference that isn't per-chat.
        const val ACCOUNT_WIDE = "0"

        /// The official apps' own key for the one-tap boost amount
        /// (`DataSyncModels.swift`), kept in whole sats, never msat —
        /// PeelCore's own `Preference<UInt64>.boostAmountMsat`.
        const val TIP_AMOUNT_KEY = "tip_amount"

        /// Peel-only key (iOS `Preference<UInt64>.paymentConfirmationMsat`):
        /// payments/boosts over this many msat are confirmed first, in
        /// whole msat as a JSON string. The official apps keep and ignore it.
        const val PAYMENT_CONFIRMATION_KEY = "peel.payment_confirmation_msat"

        /// The official apps' own keys (PeelCore `Preferences.pinTimeoutKey` / `biometricKey`).
        const val PIN_TIMEOUT_KEY = "pin_timeout"
        const val BIOMETRIC_KEY = "biometric_enabled"

        /// Peel-only key (iOS `Preference<Bool>.autoDownloadMedia`): "true"/"false"
        /// as a JSON string; absent means on.
        const val AUTO_DOWNLOAD_MEDIA_KEY = "peel.auto_download_media"

        fun parse(text: String): PreferencesFile {
            val root = JSONObject(text)
            // PeelCore's `PreferencesFile.init(text:)`: a file the official
            // apps could not read is refused whole (this throws), so a sync
            // neither reads nor rewrites it.
            val array = root.optJSONArray("items") ?: throw IllegalArgumentException("unreadable preferences file")
            val items = (0 until array.length()).map { index ->
                val item = array.optJSONObject(index) ?: throw IllegalArgumentException("unreadable preferences file")
                val readable = item.opt("key") is String && item.opt("identifier") is String && item.opt("date") is String &&
                    item.opt("value").let { it is String || it is Int || it is Long || it is Boolean || it is JSONObject }
                if (!readable) throw IllegalArgumentException("unreadable preferences file")
                item
            }.toMutableList()
            return PreferencesFile(root, items)
        }

        /// A brand-new file, written only when this device may start one
        /// at all (`Engine.mayStartPreferencesFile`) — never on an
        /// ordinary "not found", which could just as easily mean a
        /// server hiccup on a restored account that has a real file
        /// elsewhere.
        fun empty(): PreferencesFile = PreferencesFile(JSONObject(), mutableListOf())

        /// A whole number as the official apps write one: a JSON string
        /// of digits, or a JSON number they also read — PeelCore's own
        /// `JSONValue.wholeNumber`.
        internal fun wholeNumber(value: Any?): Long? = when (value) {
            is String -> value.toLongOrNull()
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }

        /// An item's date in seconds as PeelCore reads it (`readDate`):
        /// a string `Double` reads, finite; null if it does not read.
        internal fun readDate(item: JSONObject): Long? =
            (item.opt("date") as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { Math.floor(it).toLong() }
    }

    fun text(): String {
        // A shallow copy of `root` so `items` can be swapped in without
        // disturbing whatever else the official apps keep at the top
        // level — never read or interpreted here, only carried through.
        val out = JSONObject(root, root.keys().asSequence().toList().toTypedArray())
        out.put("items", JSONArray(items))
        return out.toString()
    }

    /// The account-wide item for `key`: the last copy, as the official
    /// apps apply every item in order and let the last win.
    private fun lastItem(key: String): JSONObject? =
        items.lastOrNull { it.optString("key") == key && it.optString("identifier") == ACCOUNT_WIDE }

    /// `key`'s raw string value (or number, as a string) and its date, if
    /// the file has an item for it. The date is null when it does not read.
    fun entry(key: String): Pair<String, Long?>? {
        val item = lastItem(key) ?: return null
        val value = when (val raw = item.opt("value")) {
            is String -> raw
            is Int, is Long -> raw.toString()
            else -> return null
        }
        return value to readDate(item)
    }

    /// Replaces every copy of `key` with one item (value as a JSON string,
    /// date as a string of whole seconds, as PeelCore writes them).
    fun setEntry(key: String, value: String, dateSeconds: Long) {
        items.removeAll { it.optString("key") == key && it.optString("identifier") == ACCOUNT_WIDE }
        items.add(
            JSONObject().apply {
                put("key", key)
                put("identifier", ACCOUNT_WIDE)
                put("date", dateSeconds.toString())
                put("value", value)
            },
        )
    }

    /// `tip_amount`'s whole-sats value and date, if the file holds one
    /// that reads (more than zero, no overflow in msat).
    fun tipAmount(): Pair<Long, Long>? {
        val (value, date) = entry(TIP_AMOUNT_KEY) ?: return null
        val sats = PreferencesMerge.TIP.parse(value) ?: return null
        return sats to (date ?: 0L)
    }

    /// PeelCore's `Preference<UInt64>.boostAmountMsat.write`: whole sats, as a JSON string.
    fun setTipAmount(sats: Long, dateSeconds: Long) = setEntry(TIP_AMOUNT_KEY, sats.toString(), dateSeconds)
}
