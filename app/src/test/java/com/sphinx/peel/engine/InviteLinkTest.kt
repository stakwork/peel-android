package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// `Invite.toJson` uses org.json, which needs Robolectric on a plain JVM (see ServerConfigTest).
@RunWith(RobolectricTestRunner::class)
class InviteLinkTest {
    private val key = "02" + "ab".repeat(32)
    private val lsp = "03" + "cd".repeat(32)
    private val info = "${key}_${lsp}_123456"
    private val link = "sphinx.chat://?action=i&d=abc"

    private fun reader(
        code: String = "c0de", host: String? = "lsp.example:8883", inviter: String? = info, alias: String? = "Alice",
    ) = InviteReader { InviteFields(code, host, inviter, alias) }

    private fun refused(text: String, reader: InviteReader): String =
        try { InviteLink.parse(text, reader); fail("expected a refusal"); "" } catch (e: InviteProblem) { e.message!! }

    @Test
    fun `a full link is read`() {
        val invite = InviteLink.parse(link, reader())
        assertEquals(Invite("c0de", "lsp.example:8883", info, "Alice"), invite)
    }

    @Test
    fun `a bare d= is handed to the core as it is, and whitespace around is trimmed`() {
        var seen = ""
        InviteLink.parse("  d=abc\n", InviteReader { seen = it; InviteFields("c", "h:1", info, null) })
        assertEquals("d=abc", seen)
    }

    @Test
    fun `garbage the core cannot read is not an invite`() {
        val failing = InviteReader { throw IllegalArgumentException("no") }
        assertEquals("That doesn't look like a Sphinx invite.", refused("hello", failing))
        assertEquals("That doesn't look like a Sphinx invite.", refused("   ", failing))
    }

    @Test
    fun `over-length text is refused before the core sees it`() {
        var called = false
        val spy = InviteReader { called = true; InviteFields("c", "h:1", info, null) }
        val message = refused("d=" + "a".repeat(InviteLink.MAX_LENGTH), spy)
        assertTrue(message.contains("too long"))
        assertEquals(false, called)
        // A whole pasted document: refused on bytes.
        assertTrue(refused("x".repeat(InviteLink.MAX_LENGTH * 4 + 1), spy).contains("too long"))
        assertEquals(false, called)
    }

    @Test
    fun `an invite without an inviter is refused`() {
        assertEquals("That invite names nobody to add.", refused(link, reader(inviter = null)))
        assertEquals("That invite names nobody to add.", refused(link, reader(inviter = "not_contact_info")))
    }

    @Test
    fun `an invite without a server is refused for a signup, not for adding the inviter`() {
        assertEquals("The invite does not say which server to use.", refused(link, reader(host = null)))
        assertNull(InviteLink.parse(link, reader(host = " "), requireServer = false).server)
    }

    @Test
    fun `the inviter's contact info is normalised and a blank alias dropped`() {
        val invite = InviteLink.parse(link, reader(inviter = info.uppercase(), alias = "  "))
        assertEquals(info, invite.inviterContactInfo)
        assertNull(invite.inviterAlias)
    }

    @Test
    fun `only an invite link prefills the invite step`() {
        assertEquals("sphinx.chat://?action=i&d=abc", InviteLink.fromDeepLink("sphinx.chat", "i", "sphinx.chat://?action=i&d=abc"))
        assertEquals("x", InviteLink.fromDeepLink("SPHINX.CHAT", "i", "x"))
        assertNull(InviteLink.fromDeepLink("sphinx.chat", "tribeV2", "x"))
        assertNull(InviteLink.fromDeepLink("https", "i", "x"))
        assertNull(InviteLink.fromDeepLink("sphinx.chat", "i", " "))
    }

    @Test
    fun `an invite survives the setting it is stored in`() {
        val invite = Invite("c0de", "lsp:8883", info, "Alice")
        assertEquals(invite, Invite.fromJson(invite.toJson()))
        val bare = Invite("c0de", null, null, null)
        assertEquals(bare, Invite.fromJson(bare.toJson()))
        assertNull(Invite.fromJson(""))
        assertNull(Invite.fromJson(null))
        assertNull(Invite.fromJson("{not json"))
    }

    // A worker's invite link (docs/phases/37-worker-onboarding.md): `&a=` names the public app to open.
    private val worker = "https%3A%2F%2Facme.example.com%2Fpublic%2F%3Fapp%3Dworker"

    @Test
    fun `a worker's link names the app to open, by a peel-open link's rules`() {
        val app = InviteLink.appOf("sphinx.chat://?action=i&d=abc&c=def&a=$worker")!!
        assertEquals("https://acme.example.com/public/?app=worker", app.url)
        assertEquals("worker", app.app)
        assertEquals("https://acme.example.com", app.origin)
        assertEquals("https://acme.example.com", app.seller)
        assertEquals(PublicAppLink.of("https://acme.example.com/public/?app=worker"), app)
        // Anywhere in the link, and a plus stays a plus.
        assertEquals("https://acme.example.com/a+b", InviteLink.appOf("sphinx.chat://?a=https%3A%2F%2Facme.example.com%2Fa+b&action=i&d=abc")!!.url)
    }

    @Test
    fun `no a=, or one that fails the rules, opens nothing and leaves the invite as it is`() {
        assertNull(InviteLink.appOf(link))
        assertNull(InviteLink.appOf("$link&a="))
        // Not https (http only on this device's loopback).
        assertNull(InviteLink.appOf("$link&a=http%3A%2F%2Facme.example.com"))
        assertNull(InviteLink.appOf("$link&a=javascript%3Aalert(1)"))
        // A fragment, credentials, two apps.
        assertNull(InviteLink.appOf("$link&a=https%3A%2F%2Facme.example.com%2F%23x"))
        assertNull(InviteLink.appOf("$link&a=https%3A%2F%2Fu%3Ap%40acme.example.com"))
        assertNull(InviteLink.appOf("$link&a=https%3A%2F%2Facme.example.com%2F%3Fapp%3Da%26app%3Db"))
        // Two a= sections: neither.
        assertNull(InviteLink.appOf("$link&a=$worker&a=$worker"))
        // A broken escape.
        assertNull(InviteLink.appOf("$link&a=https%3A%2F%2Facme.example.com%2"))
        // Only a section named exactly `a`.
        assertNull(InviteLink.appOf("$link&aa=$worker"))
        assertNull(InviteLink.appOf("$link&app=$worker"))
        // The invite is read from the whole link, `a=` and all, as the core reads it (it passes over `a=`).
        var seen = ""
        val withApp = "$link&a=$worker"
        InviteLink.parse(withApp, InviteReader { seen = it; InviteFields("c0de", "lsp.example:8883", info, null) })
        assertEquals(withApp, seen)
    }
}
