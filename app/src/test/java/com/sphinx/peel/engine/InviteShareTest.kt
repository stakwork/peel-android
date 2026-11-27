package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InviteShareTest {
    private val key = "02" + "ab".repeat(32)
    private val lsp = "03" + "cd".repeat(32)
    private val link = "sphinx.chat://?action=i&d=abc&c=xyz"
    private val named = "%1\$s invited you to Peel.\n\n1. Open:\n%2\$s\n2. Done \"Join with an invite\"."
    private val anon = "You're invited to Peel.\n\n1. Open:\n%1\$s\n2. Done."
    private val reader = InviteReader { InviteFields("c0de", "lsp:1", "${key}_${lsp}_1", "Alice") }
    private val joined = "https://strut.stadeum.stakwork.ai/public/join#action=i&d=abc&c=xyz"

    @Test fun `join link round trips`() {
        assertEquals(joined, InviteLink.joinLink(link))
        assertEquals(link, InviteLink.fromJoinLink(joined))
        assertEquals("https://h/public/join#action=i&d=1", InviteLink.joinLink("action=i&d=1", "https://h/"))
    }

    @Test fun `join link refuses non invites`() {
        assertNull(InviteLink.joinLink("hello"))
        assertNull(InviteLink.joinLink("sphinx.chat://?action=i&d="))
        assertNull(InviteLink.joinLink("sphinx.chat://?action=i&d=a#b"))
        assertNull(InviteLink.joinLink("sphinx.chat://?action=i&d=a b"))
        assertNull(InviteLink.joinLink("sphinx.chat://?action=j&d=a"))
    }

    @Test fun `message names the inviter and holds the join link`() {
        val m = InviteShare.message(link, "Alice", "Welcome!", named, anon)
        assertTrue(m.startsWith("Welcome!\n\nAlice invited you"))
        assertTrue(m.contains(joined))
        assertTrue(InviteShare.message(link, null, null, named, anon).startsWith("You're invited"))
        assertTrue(InviteShare.message("junk", "A", null, named, anon).contains("junk"))
    }

    @Test fun `find reads the first link in a message`() {
        val m = InviteShare.message(link, "Alice", null, named, anon)
        assertEquals(link, InviteLink.find(m))
        assertEquals(link, InviteLink.find("hi (see $link)."))
        assertEquals(link, InviteLink.find("a $link b sphinx.chat://?action=i&d=zzz"))
        assertNull(InviteLink.find("nothing here https://x.y/z"))
        assertEquals(link, InviteLink.fromClipboard(m))
        assertNull(InviteLink.fromClipboard("just words"))
        assertEquals("Alice", InviteLink.parse(m, reader).inviterAlias)
    }

    @Test fun `find reads the spanish message`() {
        val es = "Ana te invitó a Peel.\n\n1. Abre:\n$joined\n2. Abre Peel. Si no, toca \"Unirme con una invitación\"."
        assertEquals(link, InviteLink.find(es))
    }
}
