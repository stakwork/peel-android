package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// The page host's logcat lines (docs/phases/42-page-host-logs.md): an origin and numbers, never what follows the host.
class PageLogTest {
    @Test fun `an address is written as its origin alone`() {
        assertEquals("https://acme.sphinx.chat", PageLog.origin("https://acme.sphinx.chat/lab/?app=board&solo=1#x"))
        assertEquals("https://strut.stadeum1.sphinx.chat", PageLog.origin("https://strut.stadeum1.sphinx.chat/?app=strut/worker&solo=1&public=1"))
        assertEquals("http://127.0.0.1:8080", PageLog.origin("http://127.0.0.1:8080/x"))
        assertEquals("https://example.com", PageLog.origin("HTTPS://Example.COM"))
        assertEquals("https://example.com", PageLog.origin("https://user:secret@example.com/p"))
        assertEquals("https://example.com", PageLog.origin("https://example.com?q=1"))
        assertEquals("about:", PageLog.origin("about:blank"))
        assertEquals("data:", PageLog.origin("data:text/html,<b>secret</b>"))
        assertEquals("-", PageLog.origin(null))
        assertEquals("-", PageLog.origin(""))
        assertEquals("?", PageLog.origin("not an address"))
        assertEquals("?", PageLog.origin("/relative/path?token=x"))
    }

    @Test fun `steps and failures carry no path, query or token`() {
        val url = "https://acme.sphinx.chat/lab/?app=board&input=v%3D2&solo=1"
        for (line in listOf(
            PageLog.step("pageStarted", url, 2),
            PageLog.error(url, -2, "net::ERR_NAME_NOT_RESOLVED"),
            PageLog.httpError(url, 404),
        )) {
            assertTrue(line, line.contains("https://acme.sphinx.chat"))
            assertFalse(line, line.contains("app=") || line.contains("/lab") || line.contains("input"))
        }
        assertEquals("pageStarted https://acme.sphinx.chat gen=2", PageLog.step("pageStarted", url, 2))
        assertEquals("error https://acme.sphinx.chat code=-2 net::ERR_NAME_NOT_RESOLVED", PageLog.error(url, -2, "net::ERR_NAME_NOT_RESOLVED"))
        assertEquals("httpError https://acme.sphinx.chat status=404", PageLog.httpError(url, 404))
        assertEquals("renderGone didCrash=false priorityAtExit=2 current=true", PageLog.renderGone(false, 2, true))
    }

    @Test fun `a console message is redacted and its source is a file name`() {
        val line = PageLog.console(
            "ERROR",
            "fetch https://acme.sphinx.chat/api/x?token=abc failed with ${"a1".repeat(20)}",
            "https://acme.sphinx.chat/assets/index.js?v=3#m",
            12,
        )
        assertTrue(line, line.startsWith("console ERROR fetch https://acme.sphinx.chat<redacted> failed with <redacted>"))
        assertTrue(line, line.endsWith(" @index.js:12"))
        assertFalse(line, line.contains("token") || line.contains("/api") || line.contains("v=3"))
        assertEquals("-", PageLog.file(null))
        assertEquals("-", PageLog.file("https://acme.sphinx.chat/"))
        assertEquals("app.js", PageLog.file("app.js"))
    }

    @Test fun `an exception is its class alone`() {
        assertEquals("threw:IllegalStateException", PageLog.threw(IllegalStateException("profile peel_org_abc in use")))
        assertEquals(
            "webView gen=0 package=app.vanadium.webview/140 MULTI_PROFILE=true DOCUMENT_START_SCRIPT=true WEB_MESSAGE_LISTENER=true " +
                "profile=on tokenScript=none wallet=installed",
            PageLog.made(0, "app.vanadium.webview/140", true, true, true, "on", "none", "installed"),
        )
    }
}
