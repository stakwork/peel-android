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

        fun parse(text: String): PreferencesFile {
            val root = JSONObject(text)
            val array = root.optJSONArray("items") ?: JSONArray()
            val items = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.toMutableList()
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
        private fun wholeNumber(value: Any?): Long? = when (value) {
            is String -> value.toLongOrNull()
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
    }

    fun text(): String {
        // A shallow copy of `root` so `items` can be swapped in without
        // disturbing whatever else the official apps keep at the top
        // level — never read or interpreted here, only carried through.
        val out = JSONObject(root, root.keys().asSequence().toList().toTypedArray())
        out.put("items", JSONArray(items))
        return out.toString()
    }

    /// `tip_amount`'s own item for the account-wide identifier, if the
    /// file has one — the last copy, as the official apps apply every
    /// item in order and let the last win (`PreferencesFile.merged`'s
    /// own comment).
    private fun tipAmountItem(): JSONObject? =
        items.lastOrNull { it.optString("key") == TIP_AMOUNT_KEY && it.optString("identifier") == ACCOUNT_WIDE }

    /// `tip_amount`'s own whole-sats value and date, if the file holds
    /// one that reads.
    fun tipAmount(): Pair<Long, Long>? {
        val item = tipAmountItem() ?: return null
        val sats = wholeNumber(item.opt("value"))?.takeIf { it > 0 } ?: return null
        val date = wholeNumber(item.opt("date")) ?: 0L
        return sats to date
    }

    /// Replaces every copy of `tip_amount` (there should only ever be
    /// one) with a single item carrying `sats`/`dateSeconds` — PeelCore's
    /// own `Preference<UInt64>.boostAmountMsat.write`: the value is
    /// whole sats, as a JSON string, never msat and never a raw number.
    fun setTipAmount(sats: Long, dateSeconds: Long) {
        items.removeAll { it.optString("key") == TIP_AMOUNT_KEY && it.optString("identifier") == ACCOUNT_WIDE }
        items.add(
            JSONObject().apply {
                put("key", TIP_AMOUNT_KEY)
                put("identifier", ACCOUNT_WIDE)
                put("date", dateSeconds.toString())
                put("value", sats.toString())
            },
        )
    }
}
