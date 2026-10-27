package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test
    fun `a bare host defaults to TLS`() {
        val address = ServerAddress.parse("chat.sphinx.chat:1883")!!
        assertEquals(ServerAddress("chat.sphinx.chat", 1883, tls = true), address)
    }

    @Test
    fun `a bare loopback host defaults to plaintext`() {
        val address = ServerAddress.parse("127.0.0.1:1883")!!
        assertEquals(ServerAddress("127.0.0.1", 1883, tls = false), address)
    }

    @Test
    fun `an explicit scheme overrides the loopback default`() {
        assertEquals(true, ServerAddress.parse("mqtts://127.0.0.1:1883")!!.tls)
        assertEquals(false, ServerAddress.parse("mqtt://chat.sphinx.chat:1883")!!.tls)
    }

    @Test
    fun `refuses what it cannot parse`() {
        assertNull(ServerAddress.parse("not-a-server"))
        assertNull(ServerAddress.parse("host:not-a-port"))
        assertNull(ServerAddress.parse("ftp://host:21"))
    }

    @Test
    fun `round trips through toString`() {
        val address = ServerAddress.parse("mqtt://127.0.0.1:1883")!!
        assertEquals(address, ServerAddress.parse(address.toString()))
    }

    @Test
    fun `named refuses plaintext off this device`() {
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.named("mqtt://chat.sphinx.chat:1883") }
    }

    @Test
    fun `named allows plaintext on this device`() {
        assertEquals(false, ServerAddress.named("mqtt://127.0.0.1:1883").tls)
    }
}
