package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallLinkTest {
    private val server = "chat.sphinx.chat"

    @Test
    fun `a fresh link's own url parses back against the same server`() {
        val fresh = CallLink.new(server, audioOnly = true)
        val parsed = CallLink.parse(fresh.url, server)
        assertEquals(fresh, parsed)
    }

    @Test
    fun `a valid link is accepted`() {
        val link = CallLink.parse("https://$server/rooms/sphinx.call.abc123", server)
        assertNotNull(link)
        assertEquals("sphinx.call.abc123", link!!.room)
        assertFalse(link.audioOnly)
    }

    @Test
    fun `startAudioOnly true is the only thing that sets audioOnly`() {
        val audio = CallLink.parse("https://$server/rooms/sphinx.call.abc?startAudioOnly=true", server)
        assertTrue(audio!!.audioOnly)
        val notQuiteTrue = CallLink.parse("https://$server/rooms/sphinx.call.abc?startAudioOnly=yes", server)
        assertFalse(notQuiteTrue!!.audioOnly)
    }

    @Test
    fun `a different host is refused`() {
        assertNull(CallLink.parse("https://evil.example/rooms/sphinx.call.abc", server))
    }

    @Test
    fun `a different port is refused`() {
        assertNull(CallLink.parse("https://$server:4443/rooms/sphinx.call.abc", server))
    }

    @Test
    fun `plain http on a non-loopback server is refused`() {
        assertNull(CallLink.parse("http://$server/rooms/sphinx.call.abc", server))
    }

    @Test
    fun `http is accepted for a loopback call server`() {
        val link = CallLink.parse("http://127.0.0.1:4020/rooms/sphinx.call.abc", "127.0.0.1:4020")
        assertNotNull(link)
    }

    @Test
    fun `credentials in the link are refused`() {
        assertNull(CallLink.parse("https://user:pass@$server/rooms/sphinx.call.abc", server))
    }

    // The call's key after `#` (docs/phases/56-box-group-calls.md; stadeum plans/group-calls.md §2)

    private val key = "q8Zp3v+Jx/2mWcLrT0aYb1nHsEuKfD6gVoQ4tRzN5iE="

    @Test
    fun `a fragment is the call's key, kept on the link and written back after the query`() {
        val link = CallLink.parse("https://$server/rooms/sphinx.call.abc?startAudioOnly=true#$key", server)!!
        assertEquals(key, link.encryptionKey)
        assertTrue(link.isEncrypted)
        assertTrue(link.audioOnly)
        assertEquals("https://$server/rooms/sphinx.call.abc?startAudioOnly=true#$key", link.url)
        assertNull(CallLink.parse("https://$server/rooms/sphinx.call.abc", server)!!.encryptionKey)
    }

    @Test
    fun `a percent-encoded key is read as meet writes one, and an empty fragment is no key`() {
        val escaped = key.replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")
        assertEquals(key, CallLink.parse("https://$server/rooms/sphinx.call.abc#$escaped", server)!!.encryptionKey)
        val empty = CallLink.parse("https://$server/rooms/sphinx.call.abc#", server)!!
        assertNull(empty.encryptionKey)
        assertFalse(empty.isEncrypted)
    }

    @Test
    fun `a fragment that is not a key refuses the link`() {
        assertNull(CallLink.parse("https://$server/rooms/sphinx.call.abc#has%20space", server))
        assertNull(CallLink.parse("https://$server/rooms/sphinx.call.abc#a%22b", server))
        assertNull(CallLink.parse("https://$server/rooms/sphinx.call.abc#" + "a".repeat(CallLink.MAX_KEY + 1), server))
        assertNull(CallLink.parse("https://$server/rooms/sphinx.call.abc#%zz", server))
    }

    @Test
    fun `a new encrypted call carries its key through the message`() {
        val link = CallLink.new(server, audioOnly = false, encryptionKey = key)
        assertTrue(link.url.endsWith("#$key"))
        assertEquals(link, CallLink.parse(link.url, server))
        assertEquals(link, CallLink.inMessage(link.message, isCallType = true, server = server))
        // Still JSON the other apps read: the link is the `link` field, key and all.
        assertEquals(link.url, CallLink.linkText(link.message))
    }

    @Test
    fun `the key is never in a link's description`() {
        val link = CallLink.new(server, audioOnly = true, encryptionKey = key)
        assertFalse(link.toString().contains(key))
        assertTrue(link.toString().contains("encrypted"))
    }

    @Test
    fun `a key that is not one is refused when making a call`() {
        assertThrows(IllegalArgumentException::class.java) { CallLink.new(server, audioOnly = false, encryptionKey = "has space") }
        assertThrows(IllegalArgumentException::class.java) { CallLink.new(server, audioOnly = false, encryptionKey = "") }
    }

    @Test
    fun `Hive's call links still refuse a fragment`() {
        assertNull(CallLink.hive("https://$server/rooms/swarm1.sphinx.chat-.1700000000#$key", server))
        assertNotNull(CallLink.hive("https://$server/rooms/swarm1.sphinx.chat-.1700000000", server))
    }

    @Test
    fun `a path that is not rooms is refused`() {
        assertNull(CallLink.parse("https://$server/other/sphinx.call.abc", server))
    }

    @Test
    fun `a room name with a bad prefix or characters is refused`() {
        assertNull(CallLink.parse("https://$server/rooms/not-a-call-room", server))
        assertNull(CallLink.parse("https://$server/rooms/sphinx.call.", server))
        assertNull(CallLink.parse("https://$server/rooms/sphinx.call.has space", server))
    }

    @Test
    fun `an unknown query key is refused`() {
        assertNull(CallLink.parse("https://$server/rooms/sphinx.call.abc?evil=1", server))
    }

    @Test
    fun `the same query key twice is refused`() {
        assertNull(
            CallLink.parse(
                "https://$server/rooms/sphinx.call.abc?startAudioOnly=true&startAudioOnly=false",
                server,
            ),
        )
    }

    @Test
    fun `a bare query key with no value is refused`() {
        assertNull(CallLink.parse("https://$server/rooms/sphinx.call.abc?startAudioOnly", server))
    }

    @Test
    fun `content over the length limit is refused`() {
        val huge = "https://$server/rooms/sphinx.call." + "a".repeat(5000)
        assertNull(CallLink.parse(huge, server))
    }

    @Test
    fun `linkText reads a bare link or the call wrapper`() {
        assertEquals("https://x/rooms/sphinx.call.a", CallLink.linkText("https://x/rooms/sphinx.call.a"))
        assertEquals(
            "https://x/rooms/sphinx.call.a",
            CallLink.linkText("""call::{"link":"https://x/rooms/sphinx.call.a","recurring":false,"cron":""}"""),
        )
        assertNull(CallLink.linkText("call::not json"))
    }

    @Test
    fun `message round-trips through inMessage`() {
        val link = CallLink.new(server, audioOnly = false)
        val parsed = CallLink.inMessage(link.message, isCallType = true, server = server)
        assertEquals(link, parsed)
    }

    @Test
    fun `inMessage finds a bare link in plain text for Mac interop, but not in ordinary text`() {
        val link = CallLink.new(server, audioOnly = false)
        assertEquals(link, CallLink.inMessage(link.url, isCallType = false, server = server))
        assertNull(CallLink.inMessage("just chatting, no call here", isCallType = false, server = server))
    }

    @Test
    fun `a call is recent for twelve hours and not from the future`() {
        val now = 1_000_000_000_000L
        val hour = 60L * 60 * 1000
        assertTrue(CallLink.isRecent(now - 11 * hour, now))
        assertTrue(CallLink.isRecent(now - 12 * hour, now))
        assertFalse(CallLink.isRecent(now - 12 * hour - 1, now))
        assertTrue(CallLink.isRecent(now + 4 * 60 * 1000, now))
        assertFalse(CallLink.isRecent(now + 6 * 60 * 1000, now))
        assertTrue(CallLink.isRecent(null, now))
    }

    @Test
    fun `a hive call link is the account's call server and a hive room`() {
        val link = CallLink.hive("https://$server/rooms/acme.sphinx.chat-.1730000000", server)
        assertNotNull(link)
        assertEquals("acme.sphinx.chat-.1730000000", link!!.room)
        assertTrue(CallLink.isRoom(link.room))
        // Also without /rooms/ in Hive's base URL.
        assertNotNull(CallLink.hive("https://$server/acme.sphinx.chat-.1730000000", server))
    }

    @Test
    fun `a hive call link on another host or with a odd room is refused`() {
        assertNull(CallLink.hive("https://evil.example/rooms/acme.sphinx.chat-.1730000000", server))
        assertNull(CallLink.hive("https://$server/rooms/anything", server))
        assertNull(CallLink.hive("https://$server/rooms/acme.sphinx.chat-.1730000000?x=1", server))
        assertNull(CallLink.hive("https://user@$server/rooms/acme.sphinx.chat-.1730000000", server))
        // A message's link is still only a Peel room.
        assertNull(CallLink.parse("https://$server/rooms/acme.sphinx.chat-.1730000000", server))
    }
}
