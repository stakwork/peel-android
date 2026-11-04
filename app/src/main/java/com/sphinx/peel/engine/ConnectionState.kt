package com.sphinx.peel.engine

/// What `Engine.connectionState` reports, mirrored from PeelCore's
/// `Engine.ConnectionState`. [Offline] is PeelCore's own `.offline(retryIn:)`
/// — the connection was up and dropped unexpectedly, and `Engine` is
/// retrying it (`onConnectionDropped`/`reconnectLoop`), [retryInSeconds]
/// counting down to the next attempt.
sealed class ConnectionState {
    object Idle : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Offline(val retryInSeconds: Int) : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}
