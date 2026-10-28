package com.sphinx.peel.engine

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/// The broker (and tribe host) to connect to, fetched from Sphinx's own
/// public config service rather than hardcoded. Ported from PeelCore's
/// ServerConfig.swift. `parse` is the pure half this has a test for;
/// `fetch` is the live network half, not unit-tested — it hits a real
/// HTTPS endpoint, the same tradeoff `peel`'s own `ServerConfig.fetch`
/// makes.
data class ServerConfig(val defaultLsp: String, val tribeHost: String?) {
    companion object {
        private const val baseUrl = "https://config.config.sphinx.chat/api/config"

        fun parse(json: String): ServerConfig {
            val root = JSONObject(json)
            return ServerConfig(
                defaultLsp = root.getString("default_lsp"),
                tribeHost = root.optString("tribe_host").takeIf { it.isNotEmpty() },
            )
        }

        suspend fun fetch(network: String = "bitcoin"): ServerConfig = withContext(Dispatchers.IO) {
            val connection = URL("$baseUrl/$network").openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            parse(body)
        }
    }
}
