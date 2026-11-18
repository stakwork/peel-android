package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// A page's location rules (docs/phases/42-page-host-logs.md): who a request is attributed to, and what is decided.
class PageLocationTest {
    private val page = "https://strut.stadeum1.sphinx.chat"
    private val grant = { allowed: Boolean -> SiteLocationGrant(page, allowed, precise = false, decidedAt = 1) }

    @Test fun `the page's own origin is attributed to itself, a default port written or not`() {
        assertEquals(PageLocation.Attribution(page, PageLocation.Frame.MAIN), PageLocation.mayAsk("https://strut.stadeum1.sphinx.chat:443", page, true))
        assertEquals(PageLocation.Attribution(page, PageLocation.Frame.MAIN), PageLocation.mayAsk("$page/", page, true))
    }

    @Test fun `another site's frame is refused`() {
        assertNull(PageLocation.mayAsk("https://evil.example.com", page, true))
    }

    @Test fun `an opaque origin is the page's own app frame`() {
        for (opaque in listOf("null", "", "  ")) {
            assertEquals(PageLocation.Attribution(page, PageLocation.Frame.OPAQUE), PageLocation.mayAsk(opaque, page, true))
        }
    }

    @Test fun `an insecure page gets nothing, loopback http is fine`() {
        assertNull(PageLocation.mayAsk("http://shop.example.com", "http://shop.example.com", true))
        assertNull(PageLocation.mayAsk("null", "http://shop.example.com", true))
        assertEquals("http://localhost:3000", PageLocation.mayAsk("http://localhost:3000", "http://localhost:3000", true)!!.origin)
        assertEquals("http://127.0.0.1:8080", PageLocation.mayAsk("null", "http://127.0.0.1:8080", true)!!.origin)
    }

    @Test fun `a page that is not showing is refused`() {
        assertNull(PageLocation.mayAsk(page, page, false))
        assertNull(PageLocation.mayAsk("null", page, false))
    }

    @Test fun `a stored answer wins, a stored no is silent, allow once helps only without one`() {
        assertEquals(PageLocation.Decision.ASK, PageLocation.decide(null, false))
        assertEquals(PageLocation.Decision.ALLOW, PageLocation.decide(null, true))
        assertEquals(PageLocation.Decision.ALLOW, PageLocation.decide(grant(true), false))
        assertEquals(PageLocation.Decision.DENY, PageLocation.decide(grant(false), false))
        assertEquals(PageLocation.Decision.DENY, PageLocation.decide(grant(false), true))
    }

    @Test fun `precise asks the system for both, approximate for one`() {
        assertEquals(listOf(PageLocation.COARSE), PageLocation.systemPermissions(false))
        assertEquals(listOf(PageLocation.COARSE, PageLocation.FINE), PageLocation.systemPermissions(true))
        assertEquals("approximate", PageLocation.level { it == PageLocation.COARSE })
        assertEquals("precise", PageLocation.level { true })
        assertEquals("none", PageLocation.level { false })
    }

    @Test fun `a site's row says what it is`() {
        assertEquals("Blocked", PageLocation.summary(grant(false)))
        assertEquals("Allowed · approximate", PageLocation.summary(grant(true)))
        assertEquals("Allowed · precise", PageLocation.summary(grant(true).copy(precise = true)))
    }
}
