package com.sphinx.peel.engine

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/// What a tribe's own registry says about it — `GET /tribes/<pubkey>` on
/// the account's tribes host. Ported from PeelCore's `Tribe.swift`
/// `TribeInfo`, trimmed to the fields this port reads so far (a tribe's
/// real name and photo are not in any message, only here, and neither is
/// its price): membership, privacy and the rest come with the features
/// that read them.
data class TribeInfo(
    val pubkey: String,
    val name: String,
    val img: String?,
    /// The owner's own price per message, in msat — `"price_per_message"`.
    /// What a member actually pays is higher when the 3-sat floor or the
    /// escrow pushes it there (`tribeMessageAmountMsat`), never this
    /// alone — ported from PeelCore's own `Tribe.pricePerMessageMsat`/
    /// `messageAmountMsat` split for the same reason: the owner's setting
    /// and what gets paid are two different numbers.
    val pricePerMessageMsat: Long,
    /// Staked with each message, paid back later — `"escrow_amount"`.
    val escrowMsat: Long,
    /// What the tribe says about itself, for its details view only —
    /// `"description"`, `"tags"`, `"owner_alias"`, `"member_count"`. Not
    /// stored: read again each time the details open.
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val ownerAlias: String? = null,
    val memberCount: Long? = null,
    /// `"private"`: null when the registry did not say. Only a private tribe is
    /// sent the sender's time zone (`TimeZoneSharing`).
    val isPrivate: Boolean? = null,
    /// `"created"` as the registry wrote it (RFC 3339), for "Created <date>".
    val created: String? = null,
    /// `"price_to_join"`, in sats on the registry, kept here in msat; 0 when free or unsaid.
    val priceToJoinMsat: Long = 0,
) {
    companion object {
        /// `pubkey` doesn't match what the registry actually answered —
        /// PeelCore's own `Engine.Failure.tribeServerMismatch`. Caught
        /// here rather than left for a caller to notice downstream: the
        /// result otherwise gets stored keyed by the *requested* pubkey
        /// (`Engine.refreshTribeNames`/`Storage.updateTribeInfo`), so a
        /// mismatched answer would silently write one tribe's name,
        /// photo and — since this phase — *price* onto a different
        /// tribe's own row, charging every later send to it accordingly.
        class MismatchException(pubkey: String) : Exception("the tribes registry answered for a different tribe than $pubkey")

        suspend fun fetch(pubkey: String, host: String): TribeInfo = withContext(Dispatchers.IO) {
            val url = URL("https://$host/tribes/$pubkey")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val root = JSONObject(body)
            val info = TribeInfo(
                pubkey = root.getString("pubkey"),
                name = root.getString("name"),
                img = root.optString("img").takeIf { it.isNotEmpty() },
                pricePerMessageMsat = root.optLong("price_per_message", 0L).coerceAtLeast(0L),
                escrowMsat = root.optLong("escrow_amount", 0L).coerceAtLeast(0L),
                description = root.optString("description").trim().takeIf { it.isNotEmpty() },
                tags = root.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { t -> t.isNotEmpty() } } } ?: emptyList(),
                ownerAlias = root.optString("owner_alias").trim().takeIf { it.isNotEmpty() },
                memberCount = if (root.has("member_count")) root.optLong("member_count").takeIf { it > 0 } else null,
                isPrivate = if (root.has("private") && !root.isNull("private")) root.optBoolean("private") else null,
                priceToJoinMsat = (root.optLong("price_to_join", 0L).coerceIn(0L, Long.MAX_VALUE / 1000)) * 1000,
                created = root.optString("created").trim().takeIf { it.isNotEmpty() },
            )
            if (info.pubkey != pubkey) throw MismatchException(pubkey)
            info
        }
    }
}
