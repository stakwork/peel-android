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
) {
    companion object {
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
            TribeInfo(
                pubkey = root.getString("pubkey"),
                name = root.getString("name"),
                img = root.optString("img").takeIf { it.isNotEmpty() },
                pricePerMessageMsat = root.optLong("price_per_message", 0L).coerceAtLeast(0L),
                escrowMsat = root.optLong("escrow_amount", 0L).coerceAtLeast(0L),
            )
        }
    }
}
