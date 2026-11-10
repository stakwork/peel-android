package com.sphinx.peel.engine

import android.util.Log
import java.io.IOException
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
            val url = "$baseUrl/$network"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val body = try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    // Drain the error body safely; it's only for the message.
                    val detail = runCatching {
                        connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)
                    }.getOrNull()
                    throw IOException(
                        "Config service returned HTTP $code for $url" +
                            (if (detail.isNullOrBlank()) "" else ": ${detail.trim()}"),
                    )
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            parse(body)
        }

        /// Built-in default for the bitcoin network, used when the config
        /// service is unreachable or erroring.
        val bitcoinFallback = ServerConfig(
            defaultLsp = "mqtt-broker.v2.sphinx.chat:8883",
            tribeHost = "tribes.v2.sphinx.chat",
        )

        /// `fetch`, but on any failure for "bitcoin" logs and returns
        /// `bitcoinFallback`. Other networks rethrow.
        suspend fun fetchOrFallback(network: String = "bitcoin"): ServerConfig =
            try {
                fetch(network)
            } catch (e: Exception) {
                if (network != "bitcoin") throw e
                Log.w("ServerConfig", "config fetch failed, using built-in default: ${e.message}")
                bitcoinFallback
            }
    }
}
