package com.sphinx.peel.engine

/// Where the account's broker lives. Ported from PeelCore's Transport.swift.
data class ServerAddress(val host: String, val port: Int, val tls: Boolean) {
    /// Round-trips through `parse`, so what is stored keeps its TLS setting.
    override fun toString(): String = "${if (tls) "mqtts" else "mqtt"}://$host:$port"

    companion object {
        /// This device: the only place plaintext is allowed without asking.
        fun isLoopback(host: String): Boolean = host in setOf("127.0.0.1", "localhost", "::1")

        /// Parses "host:port" with an optional scheme. TLS is on unless the
        /// scheme is `mqtt://` or the host is this device.
        fun parse(string: String): ServerAddress? {
            var rest = string.trim()
            var scheme: String? = null
            val schemeEnd = rest.indexOf("://")
            if (schemeEnd >= 0) {
                scheme = rest.substring(0, schemeEnd).lowercase()
                rest = rest.substring(schemeEnd + 3)
            }
            val parts = rest.split(":", limit = 2)
            if (parts.size != 2 || parts[0].isEmpty()) return null
            val port = parts[1].toIntOrNull() ?: return null
            val host = parts[0]
            val tls = when (scheme) {
                null -> !isLoopback(host)
                "mqtts", "ssl", "tls" -> true
                "mqtt", "tcp" -> false
                else -> return null
            }
            return ServerAddress(host, port, tls)
        }

        /// A server someone else named (an invite, the public config): as
        /// `parse`, but plaintext only to this device, so an `mqtt://` in an
        /// invite cannot take the login and the account's traffic off TLS.
        fun named(string: String): ServerAddress {
            val address = parse(string) ?: throw IllegalArgumentException("the server address $string is unreadable")
            require(address.tls || isLoopback(address.host)) {
                "${address.host} is named without TLS; Peel connects in the clear only to this device"
            }
            return address
        }
    }
}
