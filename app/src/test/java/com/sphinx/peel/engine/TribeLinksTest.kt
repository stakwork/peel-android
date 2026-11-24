package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TribeLinksTest {
    private val key = "03811f89e48f974babec419538dab16bd379e1da3b8e089da06e48b7e98e528e78"
    private val official = "sphinx.chat://?action=tribeV2&pubkey=$key&host=tribes.v2.sphinx.chat"
    private val peel = "peel://tribe?pubkey=$key&host=tribes.v2.sphinx.chat"
    private val named = TribeLinks.Named(key, "tribes.v2.sphinx.chat")

    @Test
    fun `the builder keeps the shared link byte for byte`() {
        // ChatDetails.tribeLink shares peel://, not the official apps' form (already merged, "share as peel://tribe
        // links" -- TribeLinks.build/official stay for parsing's round trip, not for what Peel itself writes).
        assertEquals(peel, ChatDetails.tribeLink(key, "tribes.v2.sphinx.chat"))
        assertEquals(official, TribeLinks.build(key, "tribes.v2.sphinx.chat"))
        assertEquals(peel, TribeLinks.peel(key, "tribes.v2.sphinx.chat"))
        assertNull(TribeLinks.build(key, null))
        assertNull(TribeLinks.build(key, ""))
    }

    @Test
    fun `both forms parse and round trip`() {
        assertEquals(named, TribeLinks.parse(official))
        assertEquals(named, TribeLinks.parse(peel))
        assertEquals(named, TribeLinks.parse(TribeLinks.build(key, "tribes.v2.sphinx.chat")!!))
        assertEquals(named, TribeLinks.parse("  $peel \n"))
    }

    @Test
    fun `case is forgiven in the scheme, key and host`() {
        assertEquals(named, TribeLinks.parse("SPHINX.CHAT://?action=tribeV2&pubkey=${key.uppercase()}&host=Tribes.V2.Sphinx.Chat"))
        assertEquals(named, TribeLinks.parse("PEEL://TRIBE?pubkey=${key.uppercase()}&host=TRIBES.v2.sphinx.chat"))
    }

    @Test
    fun `extra parameters are ignored`() {
        assertEquals(named, TribeLinks.parse("$official&utm=x"))
        assertEquals(named, TribeLinks.parse("$peel&x=1"))
    }

    @Test
    fun `another action is not a tribe`() {
        assertNull(TribeLinks.parse("sphinx.chat://?action=i&pubkey=$key&host=h.io"))
        assertNull(TribeLinks.parse("sphinx.chat://?action=tribe&uuid=Wm9v-YmFyX2JheiRxdXV4AAAA"))
        assertNull(TribeLinks.parse("peel://open?pubkey=$key&host=h.io"))
        assertNull(TribeLinks.parse("peel://tribes?pubkey=$key&host=h.io"))
        assertNull(TribeLinks.parse("peel://tribe.evil.com?pubkey=$key&host=h.io"))
        assertNull(TribeLinks.parse("https://x.io/?pubkey=$key&host=h.io"))
    }

    @Test
    fun `a bad pubkey is refused`() {
        assertNull(TribeLinks.parse("peel://tribe?pubkey=${key.dropLast(2)}&host=h.io"))
        assertNull(TribeLinks.parse("peel://tribe?pubkey=${"zz" + key.drop(2)}&host=h.io"))
        assertNull(TribeLinks.parse("peel://tribe?host=h.io"))
        assertNull(TribeLinks.parse("sphinx.chat://?action=tribeV2&pubkey=abc&host=h.io"))
    }

    @Test
    fun `a bad host is refused`() {
        assertNull(TribeLinks.parse("peel://tribe?pubkey=$key"))
        assertNull(TribeLinks.parse("peel://tribe?pubkey=$key&host="))
        assertNull(TribeLinks.parse("peel://tribe?pubkey=$key&host=evil.com/x"))
        assertNull(TribeLinks.parse("peel://tribe?pubkey=$key&host=a%20b"))
        assertNull(TribeLinks.parse("sphinx.chat://?action=tribeV2&pubkey=$key&host=-bad-/"))
        // The official form may leave the host out (the account's own server is then used).
        assertEquals(TribeLinks.Named(key, null), TribeLinks.parse("sphinx.chat://?action=tribeV2&pubkey=$key"))
    }

    @Test
    fun `TribeLink parse reads the peel form too`() {
        assertEquals(TribeLink.Target(key, null, "tribes.v2.sphinx.chat"), TribeLink.parse(peel))
    }

    @Test
    fun `tribe links are found in message text`() {
        val text = "join us: $official, or $peel."
        val spans = MessageMarkup.spans(text)
        assertEquals(listOf(official, peel), spans.map { (it.kind as MessageMarkup.Kind.Link).url })
        assertEquals(listOf(official, peel), spans.map { it.text })
        assertTrue(MessageMarkup.inlines("see ($official)").any { it.kind is MessageMarkup.Kind.Link })
    }

    @Test
    fun `tribe links sit with https links and other text stays text`() {
        val text = "https://a.co/x then $peel"
        assertEquals(2, MessageMarkup.spans(text).size)
        assertTrue(MessageMarkup.spans("sphinx.chat://?action=i&d=abc").isEmpty())
        assertTrue(MessageMarkup.spans("peel://tribe?pubkey=short&host=h.io").isEmpty())
        assertTrue(MessageMarkup.spans("xpeel://tribe?pubkey=$key&host=h.io").isEmpty())
    }

    @Test
    fun `a joined tribe opens its chat, any other goes to Join filled in`() {
        assertEquals(TribeRoute.OpenChat(key), TribeRoute.decide(official, { it == key }))
        assertEquals(TribeRoute.OpenChat(key), TribeRoute.decide(peel, { it == key }))
        assertEquals(TribeRoute.Join(official), TribeRoute.decide(" $official ", { false }))
        assertEquals(TribeRoute.Join(peel), TribeRoute.decide(peel, { false }))
        assertNull(TribeRoute.decide("https://a.co", { true }))
    }

    @Test
    fun `the link handler takes a tribe link inside the app`() {
        val taken = ArrayList<String>()
        val system = ArrayList<String>()
        val handler = AppLinkHandler(
            tap = { LinkTap.NotOurs }, reopen = { Opening.OnMac }, openPage = {}, announce = {},
            system = { system.add(it) }, tribe = { taken.add(it); true },
        )
        handler.open(official)
        handler.open(peel)
        handler.open("https://a.co")
        assertEquals(listOf(official, peel), taken)
        assertEquals(listOf("https://a.co"), system)
    }

    @Test
    fun `the link handler takes a public app link inside the app and never hands it to the system`() {
        val taken = ArrayList<String>()
        val system = ArrayList<String>()
        val handler = AppLinkHandler(
            tap = { LinkTap.NotOurs }, reopen = { Opening.OnMac }, openPage = {}, announce = {},
            system = { system.add(it) }, publicApp = { taken.add(it); true },
        )
        val tasks = "peel://open?url=https%3A%2F%2Fstrut.stadeum.stakwork.ai%2F%3Fapp%3Dstrut%2Fworker"
        handler.open(tasks)
        handler.open("peel://open?url=http%3A%2F%2Fa.co%2F")
        assertEquals(listOf(tasks), taken)
        assertEquals(listOf("peel://open?url=http%3A%2F%2Fa.co%2F"), system)
    }
}
