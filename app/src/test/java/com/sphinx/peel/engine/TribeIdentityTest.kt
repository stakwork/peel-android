package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TribeIdentityTest {
    @Test
    fun `alias is letters digits and underscores`() {
        assertEquals("Ann_Lee_2", TribeIdentity.tribeAlias("Ann Lee 2"))
        assertEquals("Unknown", TribeIdentity.tribeAlias("!!"))
        assertNull(TribeIdentity.aliasNote("Ann"))
        assertEquals("Members see \u201CAnn_Lee\u201D.", TribeIdentity.aliasNote("Ann Lee"))
    }

    @Test
    fun `store round trips and drops empty identities`() {
        val store = mapOf("a" to TribeIdentity.Identity("Ann", "https://x/y.jpg"), "b" to TribeIdentity.Identity())
        assertEquals(mapOf("a" to TribeIdentity.Identity("Ann", "https://x/y.jpg")), TribeIdentity.decode(TribeIdentity.encode(store)))
        assertTrue(TribeIdentity.decode(null).isEmpty())
    }

    @Test
    fun `photo must be https`() {
        assertTrue(TribeIdentity.cleanPhoto("http://x").isFailure)
        assertNull(TribeIdentity.cleanPhoto("  ").getOrThrow())
        assertEquals("https://x", TribeIdentity.cleanPhoto(" https://x ").getOrThrow())
    }

    @Test
    fun `sent uses the tribe's own else the account's`() {
        assertEquals("Me" to "https://a", TribeIdentity.sent(null, "Me", "https://a"))
        assertEquals("Ann_Lee" to "https://a", TribeIdentity.sent(TribeIdentity.Identity("Ann Lee", null), "Me", "https://a"))
        assertEquals("Me" to "https://t", TribeIdentity.sent(TribeIdentity.Identity(null, "https://t"), "Me", "https://a"))
    }
}
