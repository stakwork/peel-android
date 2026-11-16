package com.sphinx.peel.engine

import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// iOS `StrutWallet` as plain rules: who may be answered, whom a push may go to, and the script a push is made of. Robolectric
/// only for `org.json`, which reads the script's literal back.
@RunWith(RobolectricTestRunner::class)
class StrutWalletTest {
    private class Target(override val url: String?) : StrutWallet.ScriptTarget {
        val ran = mutableListOf<String>()
        override fun run(script: String) { ran += script }
    }

    @Test fun `only the main frame at the loaded origin is answered`() {
        val loaded = "https://acme.sphinx.chat"
        assertTrue(StrutWallet.mayAnswer(true, "https://acme.sphinx.chat", loaded))
        assertTrue(StrutWallet.mayAnswer(true, "HTTPS://ACME.sphinx.chat", loaded))
        // A frame of the page, the app's sandboxed one included.
        assertFalse(StrutWallet.mayAnswer(false, "https://acme.sphinx.chat", loaded))
        // Another scheme, host or port; an opaque origin; no origin.
        assertFalse(StrutWallet.mayAnswer(true, "http://acme.sphinx.chat", loaded))
        assertFalse(StrutWallet.mayAnswer(true, "https://evil.example", loaded))
        assertFalse(StrutWallet.mayAnswer(true, "https://acme.sphinx.chat.evil.com", loaded))
        assertFalse(StrutWallet.mayAnswer(true, "https://acme.sphinx.chat:8443", loaded))
        assertFalse(StrutWallet.mayAnswer(true, "null", loaded))
        assertFalse(StrutWallet.mayAnswer(true, "", loaded))
        assertFalse(StrutWallet.mayAnswer(true, "https://acme.sphinx.chat", "not an origin"))
    }

    @Test fun `a default port is the same port written or not`() {
        assertTrue(StrutWallet.mayAnswer(true, "https://acme.io:443", "https://acme.io"))
        assertTrue(StrutWallet.mayAnswer(true, "https://acme.io", "https://acme.io:443"))
        assertTrue(StrutWallet.mayAnswer(true, "http://acme.io:80", "http://acme.io"))
        assertTrue(StrutWallet.mayAnswer(true, "http://acme.io", "http://acme.io:80"))
        // 443 is not http's default, nor 80 https's.
        assertFalse(StrutWallet.mayAnswer(true, "http://acme.io:443", "http://acme.io"))
        assertFalse(StrutWallet.mayAnswer(true, "https://acme.io:80", "https://acme.io"))
        assertTrue(StrutWallet.mayAnswer(true, "http://127.0.0.1:3417", "http://127.0.0.1:3417"))
        assertFalse(StrutWallet.mayAnswer(true, "http://127.0.0.1:3418", "http://127.0.0.1:3417"))
        assertTrue(StrutWallet.mayAnswer(true, "http://[::1]:3000", "http://[::1]:3000"))
    }

    @Test fun `a tap counts for five seconds`() {
        assertTrue(StrutWallet.personActed(1_000, 5_999))
        assertFalse(StrutWallet.personActed(1_000, 6_000))
        assertFalse(StrutWallet.personActed(null, 6_000))
        assertFalse(StrutWallet.personActed(7_000, 6_000))
        // A link out is only a second (OrgNavigation), the wallet's sheets five.
        assertEquals(5_000L, StrutWallet.GESTURE_WINDOW_MS)
        assertEquals(1_000L, OrgNavigation.TAP_WINDOW_MS)
    }

    @Test fun `a push goes only to a page still showing struts ui at the origin`() {
        val origin = "https://acme.sphinx.chat/lab/?app=x&solo=1"
        assertTrue(StrutWallet.mayPush("https://acme.sphinx.chat/lab/?app=y", origin))
        assertTrue(StrutWallet.mayPush("https://ACME.sphinx.chat:443/lab/jobs/j/files/a.md", origin))
        assertFalse(StrutWallet.mayPush("https://evil.example/lab/", origin))
        assertFalse(StrutWallet.mayPush("http://acme.sphinx.chat/lab/", origin))
        assertFalse(StrutWallet.mayPush("https://acme.sphinx.chat:8443/lab/", origin))
        assertFalse(StrutWallet.mayPush("about:blank", origin))
        assertFalse(StrutWallet.mayPush(null, origin))
    }

    @Test fun `the push script calls strut's own function with the message as one argument`() {
        val script = StrutWallet.pushScript("{\"v\":1}")
        assertEquals(
            "if (window.strutWallet && typeof window.strutWallet.notify === 'function') window.strutWallet.notify(\"{\\\"v\\\":1}\");",
            script,
        )
    }

    /// A message is data: whatever it holds, the script is the same code and the literal reads back as the message.
    @Test fun `a hostile message never changes the script`() {
        val prefix = "if (window.strutWallet && typeof window.strutWallet.notify === 'function') window.strutWallet.notify("
        val raw = listOf(
            "\"); alert(1); (\"",
            "\\\"); alert(1); //",
            "\\",
            "</script><script>alert(1)</script>",
            "<!-- <script>",
            "line\u2028separator\u2029paragraph",
            "new\nline\r\nand\ttab",
            "\u0000\u0001\u001f\u007f\u0085",
            "' + alert(1) + '",
            "\${alert(1)}`",
            "lone \ud800 surrogate and \udc00 another",
            "emoji \ud83d\ude00 and é and 日本",
            "a/b\\/c",
            "",
        )
        // As a message is, a JSON object holding them, and as they are (a message the page's strut would never send).
        for (message in raw + raw.map { JSONObject().put("v", 1).put("note", it).toString() }) {
            val script = StrutWallet.pushScript(message)
            assertTrue(script, script.startsWith(prefix) && script.endsWith(");"))
            val literal = script.removePrefix(prefix).removeSuffix(");")
            // One string literal: starts and ends with its quotes, and nothing in it can end a tag or a line.
            assertTrue(literal.length >= 2 && literal.first() == '"' && literal.last() == '"')
            assertFalse(literal, literal.contains("</") || literal.contains("<!") || literal.contains('<'))
            assertFalse(literal.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' })
            assertFalse(literal.any { it.code < 0x20 })
            assertFalse(literal.any { Character.isSurrogate(it) && !(Character.isHighSurrogate(it) || Character.isLowSurrogate(it)) })
            // Every `"` inside is escaped: an unescaped one would end the string.
            val inner = literal.substring(1, literal.length - 1)
            var i = 0
            while (i < inner.length) {
                if (inner[i] == '\\') i += 2 else { assertFalse(inner, inner[i] == '"'); i++ }
            }
            // And it reads back as exactly the message (the escapes are JSON's, which a JS literal reads the same).
            val back = JSONTokener(literal).nextValue() as String
            assertEquals(message, back)
        }
    }

    @Test fun `a lone surrogate is escaped and a pair is kept`() {
        assertEquals("\"\\ud800\"", StrutWallet.scriptLiteral("\ud800"))
        assertEquals("\"\\udc00x\"", StrutWallet.scriptLiteral("\udc00x"))
        assertEquals("\"\ud83d\ude00\"", StrutWallet.scriptLiteral("\ud83d\ude00"))
        assertEquals("\"\\u003c/script>\"", StrutWallet.scriptLiteral("</script>"))
        assertEquals("\"a/b\"", StrutWallet.scriptLiteral("a/b"))
        assertEquals("\"\\u2028\\u2029\"", StrutWallet.scriptLiteral("\u2028\u2029"))
    }

    @Test fun `a push is run in the target and only while the page is at the origin`() {
        val origin = "https://acme.sphinx.chat/lab/?app=x&solo=1"
        val here = Target("https://acme.sphinx.chat/lab/?app=x&solo=1")
        assertTrue(StrutWallet.push("{\"v\":1}", here, origin))
        assertEquals(listOf(StrutWallet.pushScript("{\"v\":1}")), here.ran)
        val away = Target("https://evil.example/")
        assertFalse(StrutWallet.push("{\"v\":1}", away, origin))
        assertTrue(away.ran.isEmpty())
        assertFalse(StrutWallet.push("{\"v\":1}", Target(null), origin))
    }

    @Test fun `no script is built from anything but the message literal`() {
        // The whole script is a fixed guard, the message's literal and a closing: nothing else of the message is in it.
        val message = JSONObject().put("op", "x").toString()
        val script = StrutWallet.pushScript(message)
        assertEquals(script.replace(StrutWallet.scriptLiteral(message), "<LITERAL>"),
            "if (window.strutWallet && typeof window.strutWallet.notify === 'function') window.strutWallet.notify(<LITERAL>);")
        assertNull(JSONObject(message).opt("missing"))
    }
}
