package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContactCodeTest {
    private val key = "02" + "ab".repeat(32)
    private val lsp = "03" + "cd".repeat(32)
    private val info = "${key}_${lsp}_42"
    private val reader = InviteReader { InviteFields("c0de", "h:1", info, "Alice") }

    private fun refused(text: String): String =
        try { ContactCode.read(text, reader); fail("expected a refusal"); "" } catch (e: InviteProblem) { e.message!! }

    @Test
    fun `contact info is read, lower-cased`() {
        val code = ContactCode.read(" ${info.uppercase()} ", reader)
        assertEquals(ContactCode.Contact(info), code)
        assertEquals(key, code.pubkey)
    }

    @Test
    fun `contact info is strict about its three parts`() {
        assertNull(ContactCode.contactInfo("${key}_${lsp}"))
        assertNull(ContactCode.contactInfo("${key}_${lsp}_4x"))
        assertNull(ContactCode.contactInfo("${key}_${lsp}_${"9".repeat(21)}"))
        assertNull(ContactCode.contactInfo("04${"ab".repeat(32)}_${lsp}_1"))
        assertNull(ContactCode.contactInfo("${key}_${lsp}_1_extra"))
        assertEquals(listOf(key, lsp, "42"), ContactCode.parts(info))
    }

    @Test
    fun `a bare public key needs its route hint`() {
        assertTrue(refused(key).contains("route hint"))
        assertEquals(ContactCode.Contact(info), ContactCode.fromParts(key, "${lsp}_42"))
        try { ContactCode.fromParts(key, "nonsense"); fail() } catch (e: InviteProblem) {}
    }

    @Test
    fun `a share_contact link is read, with the key or a whole contact info`() {
        assertEquals(ContactCode.Contact(info), ContactCode.read("sphinx.chat://?action=share_contact&pubKey=$info", reader))
        assertTrue(refused("sphinx.chat://?action=share_contact&pubkey=$key").contains("route hint"))
    }

    @Test
    fun `an invite adds its inviter, by link or bare`() {
        val viaLink = ContactCode.read("sphinx.chat://?action=i&d=abc", reader)
        assertEquals(info, viaLink.contactInfo)
        assertEquals("Alice", viaLink.inviterName)
        assertEquals(info, ContactCode.read("d=abc", reader).contactInfo)
    }

    @Test
    fun `tribe links, other actions and text are refused`() {
        assertTrue(refused("sphinx.chat://?action=tribeV2&host=h&pubkey=$key").contains("tribe"))
        assertTrue(refused("sphinx.chat://?action=auth&x=1").contains("not contact info"))
        assertTrue(refused("hello there").contains("not contact info"))
        assertTrue(refused("").contains("not contact info"))
        assertTrue(refused("a".repeat(InviteLink.MAX_LENGTH + 1)).contains("not contact info"))
        assertTrue(refused("sphinx.chat://host/path?action=i&d=abc").contains("not contact info"))
    }

    @Test
    fun `an invite with no inviter says so`() {
        val none = InviteReader { InviteFields("c", "h:1", null, null) }
        try { ContactCode.read("d=abc", none); fail() } catch (e: InviteProblem) { assertEquals("That invite names nobody to add.", e.message) }
    }
}
