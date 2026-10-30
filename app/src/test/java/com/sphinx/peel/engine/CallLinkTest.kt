package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `credentials or a fragment in the link are refused`() {
        assertNull(CallLink.parse("https://user:pass@$server/rooms/sphinx.call.abc", server))
        assertNull(CallLink.parse("https://$server/rooms/sphinx.call.abc#frag", server))
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
}
