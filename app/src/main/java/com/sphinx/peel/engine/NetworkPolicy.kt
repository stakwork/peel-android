package com.sphinx.peel.engine

/// Pure network-hardening policy, kept free of BuildConfig and Android so
/// it is unit-testable on the JVM; callers pass `BuildConfig.DEBUG`.
///
/// The platform's cleartext policy (manifest `usesCleartextTraffic`,
/// network security config) does NOT cover raw sockets: the MQTT client
/// (Netty) and LiveKit's media connection open their own sockets. Real
/// enforcement for those is here, in code.
object NetworkPolicy {
    /// Plaintext MQTT (`tls == false`) is only ever allowed in debug builds.
    fun tlsAllowed(tls: Boolean, isDebug: Boolean): Boolean = tls || isDebug

    /// Plain `ws://` (or `http://`) to this device's loopback is a debug-only
    /// convenience for a local test server; release accepts only wss/https.
    fun plainLoopbackAllowed(isDebug: Boolean): Boolean = isDebug

    /// Whether a media URL scheme (already normalised: https->wss, http->ws)
    /// is acceptable. `ownIsLoopback`: the call server is this device.
    fun mediaSchemeAllowed(scheme: String, ownIsLoopback: Boolean, isDebug: Boolean): Boolean =
        scheme == "wss" || (scheme == "ws" && ownIsLoopback && plainLoopbackAllowed(isDebug))
}
