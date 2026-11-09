package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TribeLinkTest {
    private val key = "02" + "ab".repeat(32)
    private val uuid = "Wm9v-YmFyX2JheiRxdXV4AAAA"

    private fun refused(text: String): String =
        try { TribeLink.parse(text); fail("expected a refusal"); "" } catch (e: TribeLink.Problem) { e.message!! }

    @Test
    fun `a tribeV2 link names a pubkey and a host`() {
        val target = TribeLink.parse("sphinx.chat://?action=tribeV2&pubkey=${key.uppercase()}&host=Tribes.Sphinx.Chat")
        assertEquals(TribeLink.Target(key, null, "tribes.sphinx.chat"), target)
        assertEquals(key, target.id)
    }

    @Test
    fun `an older tribe link names a uuid`() {
        assertEquals(TribeLink.Target(null, uuid, "tribes.sphinx.chat"), TribeLink.parse("sphinx.chat://?action=tribe&uuid=$uuid&host=tribes.sphinx.chat"))
        assertEquals(uuid, TribeLink.parse("sphinx.chat://?action=tribe&uuid=$uuid").id)
    }

    @Test
    fun `a link of another kind is not a tribe`() {
        refused("sphinx.chat://?action=share_contact&pubKey=$key")
        refused("sphinx.chat://?action=tribeV2&host=tribes.sphinx.chat")
        refused("sphinx.chat://?action=tribeV2&pubkey=zz&host=tribes.sphinx.chat")
        refused("sphinx.chat://?action=tribeV2&pubkey=$key&host=not a host")
    }

    @Test
    fun `a bare pubkey or uuid`() {
        assertEquals(TribeLink.Target(key, null, null), TribeLink.parse("  $key \n"))
        assertEquals(TribeLink.Target(null, uuid, null), TribeLink.parse(uuid))
    }

    @Test
    fun `pubkey and host in either order`() {
        val expected = TribeLink.Target(key, null, "tribes.sphinx.chat")
        assertEquals(expected, TribeLink.parse("$key@tribes.sphinx.chat"))
        assertEquals(expected, TribeLink.parse("$key/tribes.sphinx.chat"))
        assertEquals(expected, TribeLink.parse("tribes.sphinx.chat/$key"))
        assertEquals(TribeLink.Target(key, null, "localhost:8080"), TribeLink.parse("localhost:8080/$key"))
        assertEquals(TribeLink.Target(null, uuid, "tribes.sphinx.chat"), TribeLink.parse("tribes.sphinx.chat/$uuid"))
    }

    @Test
    fun `anything else is refused`() {
        for (text in listOf("", "   ", "hello world", "short", "a@b@c", "$key@", "@tribes.sphinx.chat", "x".repeat(3_000))) refused(text)
        assertTrue(refused("nonsense").contains("not a tribe"))
    }

    @Test
    fun `only the account's own tribes server is used`() {
        val bare = TribeLink.Target(key, null, null)
        assertEquals("tribes.sphinx.chat", TribeLink.hostFor(bare, "tribes.sphinx.chat"))
        assertEquals("tribes.sphinx.chat", TribeLink.hostFor(bare.copy(host = "tribes.sphinx.chat"), "Tribes.Sphinx.Chat"))
        val other = bare.copy(host = "evil.example")
        try { TribeLink.hostFor(other, "tribes.sphinx.chat"); fail("expected a refusal") } catch (e: TribeLink.Problem) { assertTrue(e.message!!.contains("evil.example")) }
        try { TribeLink.hostFor(bare, null); fail("expected a refusal") } catch (e: TribeLink.Problem) { assertTrue(e.message!!.contains("no tribes server")) }
    }
}
