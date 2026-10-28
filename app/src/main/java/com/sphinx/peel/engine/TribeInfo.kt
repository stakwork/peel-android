package com.sphinx.peel.engine

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/// What a tribe's own registry says about it — `GET /tribes/<pubkey>` on
/// the account's tribes host. Ported from PeelCore's `Tribe.swift`
/// `TribeInfo`, trimmed to the two fields the dashboard needs so far (a
/// tribe's real name and photo are not in any message, only here); price,
/// membership, privacy and the rest come with the features that read them.
data class TribeInfo(val pubkey: String, val name: String, val img: String?) {
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
            )
        }
    }
}
