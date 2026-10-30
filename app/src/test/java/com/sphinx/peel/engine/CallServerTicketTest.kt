package com.sphinx.peel.engine

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallServerTicketTest {
    private val server = CallServer("chat.sphinx.chat")

    private fun jwt(sub: String? = "user-1", expMs: Long? = System.currentTimeMillis() + 3_600_000, room: String? = "sphinx.call.abc", roomAdmin: Boolean = false): String {
        val header = JSONObject().put("alg", "none").toString()
        val payload = JSONObject().apply {
            if (sub != null) put("sub", sub)
            if (expMs != null) put("exp", expMs / 1000.0)
            if (room != null) put("video", JSONObject().put("room", room).put("roomAdmin", roomAdmin))
        }.toString()
        fun enc(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
        return "${enc(header)}.${enc(payload)}.sig"
    }

    @Test
    fun `claimsOf reads sub, exp, room and roomAdmin`() {
        val claims = server.claimsOf(jwt(sub = "abc", expMs = 1_793_000_000_000L, room = "sphinx.call.x", roomAdmin = true))
        assertEquals("abc", claims.sub)
        assertEquals(1_793_000_000_000L, claims.expMs)
        assertEquals("sphinx.call.x", claims.room)
        assertTrue(claims.roomAdmin)
    }

    @Test
    fun `claimsOf throws on a non-JWT string`() {
        assertThrows(CallServer.TicketException.MalformedToken::class.java) { server.claimsOf("not a jwt") }
        assertThrows(CallServer.TicketException.MalformedToken::class.java) { server.claimsOf("onlyonepart") }
        assertThrows(CallServer.TicketException.MalformedToken::class.java) { server.claimsOf("notbase64.notbase64either.sig") }
    }

    @Test
    fun `buildTicket succeeds for a valid, matching, unexpired token`() {
        val now = 1_793_000_000_000L
        val token = jwt(sub = "user-1", expMs = now + 60_000, room = "sphinx.call.abc", roomAdmin = true)
        val ticket = server.buildTicket("https://chat.sphinx.chat", token, "sphinx.call.abc", "Tom", now)
        assertEquals("wss://chat.sphinx.chat", ticket.serverUrl)
        assertEquals("user-1", ticket.identity)
        assertEquals("sphinx.call.abc", ticket.room)
        assertEquals("Tom", ticket.participantName)
        assertTrue(ticket.roomAdmin)
        assertEquals(now + 60_000, ticket.expiresAtMs)
    }

    @Test
    fun `buildTicket refuses a token for another room`() {
        val now = 1_793_000_000_000L
        val token = jwt(room = "sphinx.call.other", expMs = now + 60_000)
        assertThrows(CallServer.TicketException.TokenForAnotherRoom::class.java) {
            server.buildTicket("https://chat.sphinx.chat", token, "sphinx.call.abc", "Tom", now)
        }
    }

    @Test
    fun `buildTicket refuses an expired token`() {
        val now = 1_793_000_000_000L
        val token = jwt(room = "sphinx.call.abc", expMs = now - 1)
        assertThrows(CallServer.TicketException.TokenExpired::class.java) {
            server.buildTicket("https://chat.sphinx.chat", token, "sphinx.call.abc", "Tom", now)
        }
    }

    @Test
    fun `buildTicket refuses a token with no subject or no expiry`() {
        val now = 1_793_000_000_000L
        assertThrows(CallServer.TicketException.MalformedToken::class.java) {
            server.buildTicket("https://chat.sphinx.chat", jwt(sub = null, room = "sphinx.call.abc"), "sphinx.call.abc", "Tom", now)
        }
        assertThrows(CallServer.TicketException.MalformedToken::class.java) {
            server.buildTicket("https://chat.sphinx.chat", jwt(expMs = null, room = "sphinx.call.abc"), "sphinx.call.abc", "Tom", now)
        }
    }

    @Test
    fun `mediaUrl accepts the call server's own host, https read as wss`() {
        assertEquals("wss://chat.sphinx.chat", server.mediaUrl("https://chat.sphinx.chat"))
    }

    @Test
    fun `mediaUrl accepts the one allow-listed media host for this call server`() {
        assertEquals("wss://livekit.sphinx.chat", server.mediaUrl("https://livekit.sphinx.chat"))
    }

    @Test
    fun `mediaUrl refuses a host that is neither the call server nor allow-listed`() {
        assertThrows(CallServer.TicketException.MediaHostNotAllowed::class.java) { server.mediaUrl("https://evil.example") }
    }

    @Test
    fun `mediaUrl refuses plain ws on a non-loopback call server`() {
        assertThrows(CallServer.TicketException.MediaHostNotAllowed::class.java) { server.mediaUrl("http://chat.sphinx.chat") }
    }

    @Test
    fun `mediaUrl accepts plain ws only when the call server itself is loopback`() {
        val local = CallServer("127.0.0.1:4020")
        assertEquals("ws://127.0.0.1:4020", local.mediaUrl("http://127.0.0.1:4020"))
    }

    @Test
    fun `mediaUrl refuses credentials, a query, a fragment, or oversized text`() {
        assertThrows(CallServer.TicketException.MediaHostNotAllowed::class.java) { server.mediaUrl("https://user:pass@chat.sphinx.chat") }
        assertThrows(CallServer.TicketException.MediaHostNotAllowed::class.java) { server.mediaUrl("https://chat.sphinx.chat?x=1") }
        assertThrows(CallServer.TicketException.MediaHostNotAllowed::class.java) { server.mediaUrl("https://chat.sphinx.chat#frag") }
        assertThrows(CallServer.TicketException.MediaHostNotAllowed::class.java) { server.mediaUrl("https://chat.sphinx.chat/" + "a".repeat(3000)) }
    }
}
