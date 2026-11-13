package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ports of iOS `OrgPageRulesTests` (what an org page's web view may download, what it saves a download as, and how it words
/// a failure). The apps search tests of that file are `WorkspaceAppsTest`'s.
class OrgPageRulesTest {
    @Test fun `only struts own main frame page may download`() {
        assertTrue(OrgPageRules.mayDownload(isMainFrame = true, sourceIsMainFrame = true, served = true))
        // A frame in the page holds what the org or an agent wrote.
        assertFalse(OrgPageRules.mayDownload(isMainFrame = false, sourceIsMainFrame = true, served = true))
        assertFalse(OrgPageRules.mayDownload(isMainFrame = true, sourceIsMainFrame = false, served = true))
        assertFalse(OrgPageRules.mayDownload(isMainFrame = false, sourceIsMainFrame = false, served = true))
        // A foreign host.
        assertFalse(OrgPageRules.mayDownload(isMainFrame = true, sourceIsMainFrame = true, served = false))
    }

    @Test fun `a download served by the orgs strut is the only one allowed`() {
        val page = OrgPageRequest("https://acme.sphinx.chat/lab/?app=x&solo=1", "t", "acme", "Acme")
        val own = "https://acme.sphinx.chat/lab/files/a.zip"
        val foreign = "https://evil.example/lab/files/a.zip"
        assertTrue(OrgPageRules.mayDownload(true, true, page.serves(own)))
        assertFalse(OrgPageRules.mayDownload(true, true, page.serves(foreign)))
        assertFalse(OrgPageRules.mayDownload(false, false, page.serves(own)))
    }

    @Test fun `a suggested name stays in its folder`() {
        for ((suggested, saved) in listOf(
            "report.pdf" to "report.pdf",
            "../../etc/passwd" to "passwd",
            "a/b/c.txt" to "c.txt",
            "..\\..\\win.ini" to "win.ini",
            "dir/" to "dir",
            ".." to "download",
            "." to "download",
            "" to "download",
            "/" to "download",
            "   " to "download",
            "../" to "download",
            "nul\u0000.txt" to "nul.txt",
        )) assertEquals(suggested, saved, OrgPageRules.filename(suggested))
        assertEquals(200, OrgPageRules.filename("x".repeat(500)).length)
        assertEquals("a b.txt", OrgPageRules.filename("  a b.txt\t"))
        assertEquals("download", OrgPageRules.filename("\u0007\u0085"))
    }

    @Test fun `a failing status says why`() {
        assertNull(OrgPageRules.failure(200))
        assertNull(OrgPageRules.failure(304))
        assertNull(OrgPageRules.failure(399))
        assertTrue(OrgPageRules.failure(404)!!.contains("404"))
        assertTrue(OrgPageRules.failure(401)!!.contains("401"))
        assertTrue(OrgPageRules.failure(503)!!.contains("503"))
        assertTrue(OrgPageRules.failure(418)!!.contains("418"))
    }

    /// The words, as iOS has them.
    @Test fun `the failure wording is iOS's`() {
        assertEquals("The workspace did not let this device open it (HTTP 401).", OrgPageRules.failure(401))
        assertEquals("The workspace did not let this device open it (HTTP 403).", OrgPageRules.failure(403))
        assertEquals("The workspace has nothing there (HTTP 404).", OrgPageRules.failure(404))
        assertEquals("The workspace had a problem answering (HTTP 500).", OrgPageRules.failure(500))
        assertEquals("The workspace had a problem answering (HTTP 599).", OrgPageRules.failure(599))
        assertEquals("The workspace answered with HTTP 400.", OrgPageRules.failure(400))
        assertEquals("The workspace answered with HTTP 499.", OrgPageRules.failure(499))
        assertEquals("There was no room to keep it.", OrgPageRules.NO_ROOM)
        assertEquals("The download did not finish.", OrgPageRules.DOWNLOAD_FAILED)
        assertEquals("Couldn't save it", OrgPageRules.NOTICE_TITLE)
        assertEquals("Couldn't open it", OrgPageRules.FAILURE_TITLE)
        assertEquals("Try again", OrgPageRules.TRY_AGAIN)
    }

    @Test fun `a server's suggested name is read from its header or its path`() {
        val url = "https://a.io/lab/files/My%20Plan.pdf?t=1"
        assertEquals("r.zip", OrgPageRules.suggestedName("attachment; filename=\"r.zip\"", url))
        assertEquals("r.zip", OrgPageRules.suggestedName("attachment; filename=r.zip; size=3", url))
        assertEquals("é plan.pdf", OrgPageRules.suggestedName("attachment; filename*=UTF-8''%C3%A9%20plan.pdf", url))
        assertEquals("My Plan.pdf", OrgPageRules.suggestedName(null, url))
        assertEquals("My Plan.pdf", OrgPageRules.suggestedName("attachment", url))
        assertEquals("", OrgPageRules.suggestedName(null, "https://a.io/"))
        // What it suggests is still cut to a safe name.
        assertEquals("passwd", OrgPageRules.filename(OrgPageRules.suggestedName("attachment; filename=\"../../etc/passwd\"", url)))
    }
}
