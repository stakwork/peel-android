package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// iOS `PhonePage` and its two conformances: an org's page ([OrgPageRequest]) and a stranger's public app ([PublicPage]), and what
/// the one navigation policy does with each.
class PhonePageTest {
    private val link = PublicAppLink.of("https://shop.example.com/?app=moon-game")!!
    private val public = PublicPage(link)
    private val org = OrgPageRequest("https://acme.sphinx.chat/lab/?app=x&solo=1", "tok", "acme", "Acme", "Board")

    // MARK: PublicPage

    @Test fun `a public page loads its address as given with no credential`() {
        assertEquals(link.url, public.url)
        assertEquals("https://shop.example.com", public.origin)
        assertEquals("shop.example.com", public.host)
        assertEquals(link.url, public.request.url)
        assertTrue(public.request.headers.isEmpty())
        assertFalse(public.request.toString().contains("Bearer"))
        assertNull(public.script)
        assertEquals("public page on shop.example.com", "$public")
    }

    @Test fun `a public page keeps to the origin it loaded`() {
        assertTrue(public.serves("https://shop.example.com/anything?x=1"))
        assertTrue(public.serves("HTTPS://SHOP.example.com:443/"))
        assertFalse(public.serves("http://shop.example.com/"))
        assertFalse(public.serves("https://shop.example.com:8443/"))
        assertFalse(public.serves("https://evil.example/"))
        assertFalse(public.serves("https://shop.example.com.evil.com/"))
        assertFalse(public.serves("about:blank"))
        assertFalse(public.serves("data:text/html,x"))
        assertFalse(public.serves("not a url"))
    }

    @Test fun `a public page reloads where it went, on its origin, with nothing added`() {
        val moved = "https://shop.example.com/cart"
        assertEquals(moved, public.request(at = moved)!!.url)
        assertTrue(public.request(at = moved)!!.headers.isEmpty())
        assertNull(public.request(at = "https://evil.example/"))
        assertNull(public.request(at = null))
        assertEquals(moved, (public.reload(moved) as PhonePage.Reload.Load).request.url)
        assertEquals(PhonePage.Reload.InPlace, public.reload("https://evil.example/x"))
        assertEquals(link.url, (public.reload(null) as PhonePage.Reload.Load).request.url)
        assertEquals(moved, public.retry(moved, null).url)
        assertEquals(link.url, public.retry("https://evil.example/", "https://evil.example/").url)
    }

    @Test fun `a public page is no strut ui Peel knows`() {
        val any = "https://shop.example.com/?app=board&solo=1"
        assertFalse(public.isUIPage(any))
        assertNull(public.appLink(any, from = public.url))
        assertNull(public.request(StrutAppLink(StrutAppHandle("board"), null)))
        assertNull(public.shownApp(any))
        assertFalse(public.showsAnotherApp(any))
        assertEquals("shop.example.com", public.title(showingAnotherApp = false))
        assertEquals("shop.example.com", public.title(showingAnotherApp = true))
    }

    @Test fun `a public page is a visitor scope by its origin`() {
        assertEquals(WalletScope.Origin("https://shop.example.com"), public.walletScope)
        assertEquals("origin:https://shop.example.com", public.walletScope.key)
        assertEquals("shop.example.com", public.walletPlace)
        assertEquals("shop.example.com", public.walletScope.visitorHost)
    }

    @Test fun `two public pages are the same only when they are the same page`() {
        assertEquals(public, public)
        assertNotEquals(public, PublicPage(link))
    }

    // MARK: OrgPageRequest

    @Test fun `an org page is an org scope by its peer, named by its workspace`() {
        assertEquals(WalletScope.Org("acme"), org.walletScope)
        assertEquals("org:acme", org.walletScope.key)
        assertEquals("Acme", org.walletPlace)
        assertNull(org.walletScope.visitorHost)
        assertEquals("https://acme.sphinx.chat", org.origin)
    }

    @Test fun `an org page's script is the main frame hand over and it knows its ui`() {
        assertEquals(org.mainFrameHandOver, org.script)
        assertTrue(org.script!!.contains("strut/apiKey"))
        assertTrue(org.isUIPage("https://acme.sphinx.chat/lab/?app=y"))
        assertFalse(org.isUIPage("https://acme.sphinx.chat/lab/jobs/j/files/a.md"))
        assertEquals("Board", org.title(false))
        assertEquals("Acme", org.title(true))
        assertTrue(org.showsAnotherApp("https://acme.sphinx.chat/lab/?app=board&solo=1"))
    }

    // MARK: One policy for both

    private fun decide(page: PhonePage, url: String, main: Boolean = true, gesture: Boolean = false, touch: Long? = null) =
        OrgNavigation.decide(page, url, main, gesture, touch, now = 10_000)

    /// The public page's policy as it was before the hosts were one: the origin and `about:` load; `data:`/`blob:` only in a
    /// frame; another http(s) site never loads, and goes out only on a tap; every other scheme loads nothing.
    @Test fun `a public page's navigation policy is the origin alone`() {
        assertEquals(OrgNavigation.Decision(true), decide(public, "https://shop.example.com/a"))
        assertEquals(OrgNavigation.Decision(true), decide(public, "https://shop.example.com/a", main = false))
        assertEquals(OrgNavigation.Decision(true), decide(public, "about:srcdoc", main = false))
        assertEquals(OrgNavigation.Decision(false), decide(public, "data:text/html,x"))
        assertEquals(OrgNavigation.Decision(true), decide(public, "data:text/html,x", main = false))
        assertEquals(OrgNavigation.Decision(false), decide(public, "blob:https://shop.example.com/u"))
        assertEquals(OrgNavigation.Decision(false, openOutside = true), decide(public, "https://example.org/", gesture = true, touch = 9_500))
        assertEquals(OrgNavigation.Decision(false), decide(public, "https://example.org/", gesture = true, touch = 8_000))
        assertEquals(OrgNavigation.Decision(false), decide(public, "https://example.org/", gesture = false, touch = 9_500))
        assertEquals(OrgNavigation.Decision(false), decide(public, "file:///etc/passwd"))
        assertEquals(OrgNavigation.Decision(false), decide(public, "intent://x#Intent;end", gesture = true, touch = 9_500))
    }

    @Test fun `the same url is the orgs and not a stranger's, or the other way round`() {
        // The org's folder is its own: the same host outside it is not the org's.
        assertEquals(OrgNavigation.Decision(false), decide(org, "https://acme.sphinx.chat/other/"))
        assertEquals(OrgNavigation.Decision(true), decide(public, "https://shop.example.com/other/"))
    }

    @Test fun `a public page opens no app of anyone's in a new window`() {
        val url = "https://shop.example.com/?app=board&solo=1"
        // Same origin: loaded here as it is, on a tap (as a file is on an org's page), never as an app with a token.
        assertEquals(OrgNavigation.Window.Here(null, url), OrgNavigation.window(public, url, public.url, personActed = true))
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(public, url, public.url, personActed = false))
        assertEquals(OrgNavigation.Window.Outside("https://example.org/"), OrgNavigation.window(public, "https://example.org/", public.url, true))
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(public, "javascript:alert(1)", public.url, true))
    }
}
