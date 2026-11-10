package com.sphinx.peel.engine

/// The one logcat tag for connection diagnostics (filter on "PeelConnect").
/// Never log seeds, keys, passwords, signatures, invite codes, xpub/clientID or payloads.
internal object PeelLog {
    const val TAG = "PeelConnect"
}
