package com.sphinx.peel.engine

import org.json.JSONArray
import org.json.JSONObject

/// What the create / edit tribe form holds, and the JSON the core's `create_tribe` /
/// `update_tribe` take for it (the registry's own field names, the same ones `TribeInfo`
/// reads). Amounts are typed in sats and written in msat, as `TribeInfo` reads them back.
/// Pure, so JVM-tested.
data class TribeForm(
    val name: String = "",
    val description: String = "",
    /// An https link to the tribe's picture (picked and uploaded, never typed).
    val img: String? = null,
    val priceToJoinSat: Long = 0,
    val pricePerMessageSat: Long = 0,
    val escrowSat: Long = 0,
    /// How long escrow is held, in hours: required when there is an escrow amount.
    val escrowHours: Long = 0,
    val tags: List<String> = emptyList(),
    val unlisted: Boolean = false,
    val appUrl: String = "",
    val feedUrl: String = "",
    /// Members must be approved by the owner (or an admin) before they join.
    val approvalRequired: Boolean = false,
    /// Set when editing: the tribe's own key and uuid, and the pinned message to keep.
    val pubkey: String? = null,
    val uuid: String? = null,
    val pin: String? = null,
) {
    /// Words for the person when the form cannot be sent, else null.
    fun problem(): String? {
        if (name.isBlank()) return "A tribe needs a name."
        if (name.trim().length > MAX_NAME) return "The name is longer than $MAX_NAME characters."
        if (description.length > MAX_DESCRIPTION) return "The description is longer than $MAX_DESCRIPTION characters."
        if (img != null && !img.startsWith("https://", ignoreCase = true)) return "The picture must be an https link."
        if (priceToJoinSat < 0 || pricePerMessageSat < 0 || escrowSat < 0 || escrowHours < 0) return "Amounts cannot be negative."
        if (escrowSat > 0 && escrowHours <= 0) return "Escrow needs a time: how many hours it is held."
        if (tags.size > MAX_TAGS) return "A tribe can have at most $MAX_TAGS tags."
        if (!isUrlOrEmpty(appUrl)) return "The app URL must be an https link."
        if (!isUrlOrEmpty(feedUrl)) return "The feed URL must be an https link."
        return null
    }

    /// The body for `create_tribe` / `update_tribe`. Keys the registry has no value for are left out.
    fun toJson(): String {
        val root = JSONObject()
        root.put("name", name.trim())
        root.put("description", description.trim())
        root.put("tags", JSONArray(tags))
        img?.let { root.put("img", it) }
        root.put("price_to_join", priceToJoinSat * MSAT_PER_SAT)
        root.put("price_per_message", pricePerMessageSat * MSAT_PER_SAT)
        root.put("escrow_amount", escrowSat * MSAT_PER_SAT)
        root.put("escrow_millis", escrowHours * MILLIS_PER_HOUR)
        root.put("unlisted", unlisted)
        root.put("private", approvalRequired)
        appUrl.trim().takeIf { it.isNotEmpty() }?.let { root.put("app_url", it) }
        feedUrl.trim().takeIf { it.isNotEmpty() }?.let { root.put("feed_url", it) }
        pubkey?.let { root.put("pubkey", it) }
        uuid?.let { root.put("uuid", it) }
        pin?.let { root.put("pin", it) }
        return root.toString()
    }

    companion object {
        const val MAX_NAME = 100
        const val MAX_DESCRIPTION = 500
        const val MAX_TAGS = 10
        private const val MSAT_PER_SAT = 1_000L
        private const val MILLIS_PER_HOUR = 3_600_000L

        private fun isUrlOrEmpty(text: String): Boolean {
            val t = text.trim()
            return t.isEmpty() || (t.startsWith("https://", ignoreCase = true) && t.length > "https://".length && t.none { it.isWhitespace() })
        }

        /// "art, music ,, art" -> [art, music]: trimmed, lower-case, no blanks, no repeats.
        fun tagsFrom(text: String): List<String> =
            text.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

        /// The edit form, filled from what the registry says now (msat to sats, millis to hours).
        fun from(info: TribeInfo): TribeForm = TribeForm(
            name = info.name,
            description = info.description ?: "",
            img = info.img,
            priceToJoinSat = info.priceToJoinMsat / MSAT_PER_SAT,
            pricePerMessageSat = info.pricePerMessageMsat / MSAT_PER_SAT,
            escrowSat = info.escrowMsat / MSAT_PER_SAT,
            escrowHours = (info.escrowMillis ?: 0L) / MILLIS_PER_HOUR,
            tags = info.tags,
            unlisted = info.unlisted,
            appUrl = info.appUrl ?: "",
            feedUrl = info.feedUrl ?: "",
            approvalRequired = info.isPrivate ?: false,
            pubkey = info.pubkey,
            uuid = info.uuid,
            pin = info.pin,
        )
    }
}
