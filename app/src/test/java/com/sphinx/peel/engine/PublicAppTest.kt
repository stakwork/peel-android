package com.sphinx.peel.engine

import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun link(address: String) = "peel://open?url=" + URLEncoder.encode(address, "UTF-8")

private class FakeHttp(private val answers: Map<String, PublicAppResponse?>) : PublicAppHttp {
    val asked = mutableListOf<String>()
    override suspend fun get(url: String, limit: Int): PublicAppResponse? {
        asked += url
        if (!answers.containsKey(url)) throw IOException("no route")
        return answers[url]
    }
}

@RunWith(RobolectricTestRunner::class)
class PublicAppLinkTest {
    @Test fun accepted() {
        val l = PublicAppLink.parse("peel://open?url=https%3A%2F%2Fshop.example.com%2F%3Fapp%3Dmoon-game")!!
        assertEquals("https://shop.example.com/?app=moon-game", l.url)
        assertEquals("https://shop.example.com", l.seller)
        assertEquals("moon-game", l.app)
        assertEquals("https://shop.example.com", l.origin)
        assertEquals("shop.example.com", l.host)
        assertNotNull(PublicAppLink.parse("PEEL://open/?url=https%3A%2F%2Fshop.example.com"))
        assertNull(PublicAppLink.parse(link("https://shop.example.com"))!!.app)
        assertNotNull(PublicAppLink.parse(link("http://localhost:3000/")))
    }

    @Test fun rejectedLinks() {
        val ok = "url=https%3A%2F%2Fshop.example.com"
        assertNull(PublicAppLink.parse("peel://open?$ok&x=1"))
        assertNull(PublicAppLink.parse("peel://open?$ok&url=https%3A%2F%2Fother.example.com"))
        assertNull(PublicAppLink.parse("peel://open/extra?$ok"))
        assertNull(PublicAppLink.parse("peel://user@open?$ok"))
        assertNull(PublicAppLink.parse("peel://open?$ok#frag"))
        assertNull(PublicAppLink.parse("peel://open"))
        assertNull(PublicAppLink.parse("peel://closed?$ok"))
        assertNull(PublicAppLink.parse("https://open?$ok"))
        assertNull(PublicAppLink.parse("peel://open?x=1"))
    }

    @Test fun appNames() {
        for (ok in listOf("moon-game", "team/app", "a.b_c-d", "a".repeat(120), "v2x", "data.dbx")) assertTrue(ok, PublicAppLink.isAppName(ok))
        for (bad in listOf("v2", "team/v2", "data.db", "x/data.db", ".hidden", "a b", "", "a".repeat(121), "team/", "/app", "a//b", "-a")) {
            assertFalse(bad, PublicAppLink.isAppName(bad))
        }
    }

    @Test fun aPortOnThePeelLinkIsNotRejected() {
        assertNotNull(PublicAppLink.parse("peel://open:8080?url=https%3A%2F%2Fshop.example.com"))
    }

    @Test fun theLinkIsNotTrimmed() {
        assertNull(PublicAppLink.parse(" peel://open?url=https%3A%2F%2Fshop.example.com"))
    }

    @Test fun rejectedAddresses() {
        assertNull(PublicAppLink.parse(link("http://shop.example.com")))
        assertNull(PublicAppLink.parse(link("https://shop.example.com/?app=a&app=b")))
        assertNull(PublicAppLink.parse(link("https://shop.example.com/?app=-bad")))
        assertNull(PublicAppLink.parse(link("https://shop.example.com/?app=" + "a".repeat(121))))
        assertNull(PublicAppLink.parse(link("https://shop.example.com/#x")))
        assertNull(PublicAppLink.parse(link("https://u:p@shop.example.com/")))
        assertNull(PublicAppLink.parse(link("/just/a/path")))
        assertNull(PublicAppLink.parse(link("https://")))
    }

    @Test fun originIsNormalised() {
        assertEquals("https://shop.example.com", PublicAppLink.parse(link("HTTPS://Shop.Example.COM:443/x"))!!.origin)
        assertEquals("https://shop.example.com:8443", PublicAppLink.parse(link("https://shop.example.com:8443/"))!!.origin)
        assertEquals("http://localhost:3000", PublicAppLink.parse(link("http://localhost:3000/a?app=x"))!!.origin)
        assertEquals("https://shop.example.com/lab", PublicAppLink.parse(link("https://shop.example.com/lab/public"))!!.seller)
    }

    @Test fun serveMeta() {
        assertEquals("moon-game", PublicAppLink.serveName("<head><meta name=\"strut-serve\" content=\"moon-game\"></head>"))
        assertEquals("moon-game", PublicAppLink.serveName("<meta  name=\"strut-serve\"\n content=\"moon-game\" />"))
        assertNull(PublicAppLink.serveName("<meta name=\"strut-serve\" content=\"-bad\">"))
        assertNull(PublicAppLink.serveName("<meta name=\"strut-serve\" content=\"a<b\">"))
        assertNull(PublicAppLink.serveName("<meta name=\"strut-serve\" content=\"\">"))
        assertNull(PublicAppLink.serveName("<meta name=\"other\" content=\"x\">"))
    }

    @Test fun appNameFromTheAddress() = runBlocking {
        val http = FakeHttp(emptyMap())
        assertEquals("moon-game", PublicAppLink.parse(link("https://shop.example.com/?app=moon-game"))!!.appName(http))
        assertTrue(http.asked.isEmpty())
    }

    @Test fun appNameFromTheHomePageThenPublicApps() = runBlocking {
        val l = PublicAppLink.parse(link("https://shop.example.com/"))!!
        val meta = FakeHttp(mapOf("https://shop.example.com/" to PublicAppResponse(200, "<meta name=\"strut-serve\" content=\"moon-game\">")))
        assertEquals("moon-game", l.appName(meta))
        val listed = FakeHttp(
            mapOf(
                "https://shop.example.com/" to PublicAppResponse(200, "<html></html>"),
                "https://shop.example.com/public/apps" to PublicAppResponse(200, "[{\"name\":\"sun-game\"}]"),
            ),
        )
        assertEquals("sun-game", l.appName(listed))
    }

    @Test fun appNameNotSaid() {
        val l = PublicAppLink.parse(link("https://shop.example.com/"))!!
        val http = FakeHttp(
            mapOf(
                "https://shop.example.com/" to null,
                "https://shop.example.com/public/apps" to PublicAppResponse(200, "[{\"name\":\"a\"},{\"name\":\"b\"}]"),
            ),
        )
        val e = assertThrows(IOException::class.java) { runBlocking { l.appName(http) } }
        assertEquals("shop.example.com does not say which app to open.", e.message)
    }
}
