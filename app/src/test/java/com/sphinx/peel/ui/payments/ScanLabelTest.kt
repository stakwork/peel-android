package com.sphinx.peel.ui.payments

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanLabelTest {
    private fun label(text: String) = ScanLabel.of(text, Locale.US)
    private val key = "02" + "ab".repeat(32)

    @Test
    fun `a web address is its host without www`() {
        assertEquals("example.com", label("https://www.example.com/a/b?c=1"))
        assertEquals("stakwork.com", label("http://stakwork.com"))
        assertEquals("sub.example.com", label("HTTPS://Sub.Example.com:8080/x"))
    }

    @Test
    fun `peel links have names`() {
        assertEquals("Open strut.example.com in Peel", label("peel://open?url=https%3A%2F%2Fstrut.example.com"))
        assertEquals("Open in Peel", label("peel://open"))
        assertEquals("Link a box", label("peel://strut/link?url=https%3A%2F%2Fbox&challenge=abc"))
    }

    @Test
    fun `sphinx links`() {
        assertEquals("Sphinx invite", label("sphinx.chat://?action=i&d=abc"))
        assertEquals("Tribe", label("sphinx.chat://?action=tribeV2&pubkey=$key&host=tribes.example.com"))
    }

    @Test
    fun `an invoice names its amount when the prefix has one`() {
        val tail = "1" + "q".repeat(40)
        assertEquals("Lightning invoice · 2,500 sats", label("lnbc25u$tail"))
        assertEquals("Lightning invoice · 250,000 sats", label("lnbc2500u$tail"))
        assertEquals("Lightning invoice", label("lnbc$tail"))
        assertEquals("Lightning invoice · 1 sat", label("lightning:lnbc10n$tail"))
    }

    @Test
    fun `contact and tribe codes`() {
        assertEquals("Contact", label("${key}_${"03" + "cd".repeat(32)}_12345"))
        assertEquals("Tribe", label("$key@tribes.example.com"))
    }

    @Test
    fun `anything else is cut at thirty characters`() {
        assertEquals("hello", label("hello"))
        assertEquals("a".repeat(30) + "…", label("a".repeat(50)))
        // Sixteen letters is not a tribe.
        assertEquals("abcdefghijklmnop", label("abcdefghijklmnop"))
    }

    @Test
    fun `routes`() {
        assertEquals(ScanRoute.InApp, ScanRoute.of("peel://open?url=https%3A%2F%2Fx.com"))
        assertEquals(ScanRoute.InApp, ScanRoute.of("peel://strut/link?url=x&challenge=y"))
        assertEquals(ScanRoute.InApp, ScanRoute.of("sphinx.chat://?action=i&d=abc"))
        assertEquals(ScanRoute.External, ScanRoute.of("https://example.com/x"))
        assertEquals(ScanRoute.External, ScanRoute.of("lightning:lnurl1abc"))
        assertEquals(ScanRoute.Refuse, ScanRoute.of("sphinx.chat://?action=tribe&uuid=x"))
        assertEquals(ScanRoute.Refuse, ScanRoute.of("peel://other"))
        for (bad in listOf("tel:123456", "intent://x#Intent;end", "file:///etc/passwd", "content://x/y", "sms:123", "javascript:alert(1)")) assertEquals(bad, ScanRoute.Refuse, ScanRoute.of(bad))
        assertEquals(ScanRoute.Refuse, ScanRoute.of("hello world"))
        assertEquals(ScanRoute.Refuse, ScanRoute.of("note: hi there"))
        assertEquals(ScanRoute.Refuse, ScanRoute.of("plain"))
    }
}
