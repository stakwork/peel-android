package com.sphinx.peel.engine

/// What `Engine.connectionState` reports, mirrored from PeelCore's
/// `Engine.ConnectionState` — trimmed, since `Engine.start` here makes one
/// attempt rather than reconnecting on its own, so there is no "offline,
/// retrying in N seconds" case yet.
sealed class ConnectionState {
    object Idle : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}
