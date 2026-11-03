package com.sphinx.peel.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {
    @Test fun `tls is always allowed`() {
        assertTrue(NetworkPolicy.tlsAllowed(tls = true, isDebug = true))
        assertTrue(NetworkPolicy.tlsAllowed(tls = true, isDebug = false))
    }

    @Test fun `plaintext mqtt only in debug`() {
        assertTrue(NetworkPolicy.tlsAllowed(tls = false, isDebug = true))
        assertFalse(NetworkPolicy.tlsAllowed(tls = false, isDebug = false))
    }

    @Test fun `plain loopback only in debug`() {
        assertTrue(NetworkPolicy.plainLoopbackAllowed(true))
        assertFalse(NetworkPolicy.plainLoopbackAllowed(false))
    }

    @Test fun `wss always accepted`() {
        assertTrue(NetworkPolicy.mediaSchemeAllowed("wss", ownIsLoopback = false, isDebug = false))
        assertTrue(NetworkPolicy.mediaSchemeAllowed("wss", ownIsLoopback = true, isDebug = false))
    }

    @Test fun `ws accepted only for loopback in debug`() {
        assertTrue(NetworkPolicy.mediaSchemeAllowed("ws", ownIsLoopback = true, isDebug = true))
        assertFalse(NetworkPolicy.mediaSchemeAllowed("ws", ownIsLoopback = true, isDebug = false))
        assertFalse(NetworkPolicy.mediaSchemeAllowed("ws", ownIsLoopback = false, isDebug = true))
    }

    @Test fun `other schemes rejected`() {
        assertFalse(NetworkPolicy.mediaSchemeAllowed("ftp", ownIsLoopback = true, isDebug = true))
    }
}
