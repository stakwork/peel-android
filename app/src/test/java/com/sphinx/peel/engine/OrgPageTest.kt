package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ports of iOS `OrgPageTests` (the page's address), `StrutAppLinkTests`, and `StadeumTests`' org-page tests
/// (`aPageKeepsToItsStrutsFolder`, `aLinkToAnotherAppOpensItAloneOnTheOrgsStrutWithTheToken`, `somethingOpensOnItsOrgsOwnStrut…`),
/// plus the web view rules Android adds around them (`OrgNavigation`), pure so they run on the JVM.
class OrgPageTest {
    private fun query(url: String?) = url?.let { UrlParts.parse(it)?.query }
    private fun page(url: String, token: String = "t", title: String? = null) =
        OrgPageRequest(url, token, peer = "acme", workspace = "Acme", title = title)

    // MARK: StrutUI.page / isPage

    @Test fun `an orgs app opens by its name there`() {
        val page = OrgPage.app("hive/board", "acme")
        assertEquals("app=hive%2Fboard&solo=1", query(page.url("https://acme.sphinx.chat/lab")))
    }

    @Test fun `the page is the orgs ui alone with no credential in it`() {
        val value = "/jobs/peer%2Fj-1/files/Lisbon%20plan.md"
        for ((base, folder) in listOf(
            // A swarm's strut is mcp's `/lab`: the UI is its folder, with its `/`.
            "https://acme.sphinx.chat/lab" to "https://acme.sphinx.chat/lab/",
            "https://acme.sphinx.chat/lab/" to "https://acme.sphinx.chat/lab/",
            "https://strut.acme.io:8443" to "https://strut.acme.io:8443/",
        )) {
            val url = OrgPage("acme", "artifact", value).url(base)!!
            assertTrue(url, url.startsWith("$folder?"))
            // The path's `%` escaped again, so strut's UI reads back exactly the path.
            assertEquals("artifact=%2Fjobs%2Fpeer%252Fj-1%2Ffiles%2FLisbon%2520plan.md&solo=1", query(url))
            assertFalse(url.contains("key="))
        }
    }

    @Test fun `a base that is not an address has no page`() {
        assertNull(StrutUI.page("not a url", "app", "x"))
        assertNull(StrutUI.page("/relative", "app", "x"))
    }

    @Test fun `a key and an input ride in the address as strut reads them`() {
        val ui = "http://127.0.0.1:4100/"
        val link = StrutAppLink.parse("http://127.0.0.1:4100/?app=bench&input=view%3D%252Fruns%26run%3D12", "/", StrutAppLink.Source.Own)!!
        assertEquals(StrutAppLink(StrutAppHandle("bench"), "view=%2Fruns&run=12"), link)
        val page = StrutUI.page(ui, "app", "bench", key = "k", input = link.input)
        assertEquals("key=k&app=bench&input=view%3D%252Fruns%26run%3D12&solo=1", query(page))
    }

    @Test fun `plus and reserved characters are escaped`() {
        assertEquals("a%2Bb%20c%26d~._-", StrutUI.encoded("a+b c&d~._-"))
        assertEquals("%C3%A9", StrutUI.encoded("é"))
    }

    @Test fun `the page moves to another view with its address kept`() {
        val opened = "http://127.0.0.1:4100/?key=k%2B1&app=%40acme%2Fboard&input=v%3D1&solo=1"
        assertEquals("key=k%2B1&app=%40acme%2Fboard&input=view%3D%2Fx&solo=1", query(StrutUI.page(opened, "view=/x")))
        assertEquals("key=k%2B1&app=%40acme%2Fboard&solo=1", query(StrutUI.page(opened, null)))
        val first = "http://127.0.0.1:4100/?key=k%2B1&app=board&solo=1"
        assertEquals("key=k%2B1&app=board&input=v%3D2&solo=1", query(StrutUI.page(first, "v=2")))
    }

    @Test fun `isPage compares the path with its slash to the folder`() {
        assertTrue(StrutUI.isPage("https://a.io/lab/?app=x", "/lab/"))
        assertTrue(StrutUI.isPage("https://a.io/lab?app=x", "/lab/"))
        assertTrue(StrutUI.isPage("https://a.io?app=x", "/"))
        assertFalse(StrutUI.isPage("https://a.io/lab/jobs/j/files/a.md", "/lab/"))
        assertFalse(StrutUI.isPage("https://a.io/other/", "/lab/"))
    }

    // MARK: StrutAppLink

    private val own = StrutAppLink.Source.Own
    private fun link(url: String, from: StrutAppLink.Source, folder: String = "/") = StrutAppLink.parse(url, folder, from)

    @Test fun `a link is read as struts ui reads it`() {
        val base = "http://127.0.0.1:4100/"
        data class Case(val link: String, val from: StrutAppLink.Source, val app: String, val input: String?)
        for (c in listOf(
            // A page names apps where it lives: a bare name from a workspace's page is that workspace's.
            Case("?app=board&input=v%3D1", StrutAppLink.Source.Peer("acme"), "@acme/board", "v=1"),
            Case("?app=board%40v3", StrutAppLink.Source.Peer("acme"), "@acme/board@v3", null),
            // From a page of Peel's own strut, as written.
            Case("?app=%40acme%2Fboard", own, "@acme/board", null),
            Case("?app=tools%2Fboard&input=", own, "tools/board", null),
            // On the org's own strut (the phone), one of its own.
            Case("?app=board", StrutAppLink.Source.Org, "board", null),
            // `URLSearchParams`: a bare `+` is a space.
            Case("?app=bench&input=q%3Da+b", own, "bench", "q=a b"),
        )) assertEquals(c.link, StrutAppLink(StrutAppHandle(c.app), c.input), link(base + c.link, c.from))
    }

    @Test fun `anything else is no app link`() {
        for ((url, from) in listOf(
            // An org's page linking to its own peer's app: the org's to open.
            "http://127.0.0.1:4100/?app=%40globex%2Fx" to StrutAppLink.Source.Peer("acme"),
            "http://127.0.0.1:4100/?app=%40globex%2Fx" to StrutAppLink.Source.Org,
            // A run, a job: no app.
            "http://127.0.0.1:4100/?wf=clip&run=1" to own,
            "http://127.0.0.1:4100/?app=&input=v%3D1" to own,
            // Not a name strut gives an app.
            "http://127.0.0.1:4100/?app=..%2Fx" to own,
            // Not strut's UI: an app's own document on the same strut.
            "http://127.0.0.1:4100/apps/board/v1/files/dist/index.html?app=board" to own,
            // Given twice, or not decoding: strut would read it its own way.
            "http://127.0.0.1:4100/?app=bench&app=other" to own,
            "http://127.0.0.1:4100/?app=&app=board" to own,
            "http://127.0.0.1:4100/?app=b&input=&input=v%3D1" to own,
            "http://127.0.0.1:4100/?app=b&%61pp=c" to own,
            "http://127.0.0.1:4100/?app=b&input=%FF" to own,
        )) assertNull(url, link(url, from))
    }

    /// Counted in bytes: 1,025 two-byte characters are past it.
    @Test fun `an input past a views address is no link`() {
        val long = "%C3%A9".repeat(StrutAppLink.LONGEST_INPUT / 2 + 1)
        assertNull(link("http://127.0.0.1:4100/?app=x&input=$long", own))
        val ok = "%C3%A9".repeat(StrutAppLink.LONGEST_INPUT / 2)
        assertEquals(StrutAppLink.LONGEST_INPUT, link("http://127.0.0.1:4100/?app=x&input=$ok", own)!!.input!!.toByteArray().size)
    }

    @Test fun `percent decoding is strict`() {
        assertEquals("a b", StrutAppLink.decoded("a+b"))
        assertEquals("é", StrutAppLink.decoded("%C3%A9"))
        assertNull(StrutAppLink.decoded("%"))
        assertNull(StrutAppLink.decoded("%4"))
        assertNull(StrutAppLink.decoded("%zz"))
        assertNull(StrutAppLink.decoded("%C3"))
    }

    @Test fun `app handles follow struts names`() {
        assertTrue(StrutAppHandle.isAppName("hive/board"))
        for (bad in listOf("", "v2", "acme/v2/board", "data.db", "..", "a/../b", ".x", "a@b", "a//b", "-x", "a/")) assertFalse(bad, StrutAppHandle.isAppName(bad))
        assertTrue(StrutAppHandle.isPeerId("acme.io"))
        assertFalse(StrutAppHandle.isPeerId("-bad"))
        assertFalse(StrutAppHandle.isPeerId("a".repeat(65)))
        val h = StrutAppHandle.of("@acme/board@v3")!!
        assertEquals("acme", h.peer)
        assertEquals("board" to "v3", h.nameAndVersion)
        assertEquals("board" to null, StrutAppHandle.of("board")!!.nameAndVersion)
        assertNull(StrutAppHandle.of("@acme"))
        assertNull(StrutAppHandle.of("@-x/board"))
        assertNull(StrutAppHandle.of("board@latest"))
    }

    // MARK: OrgPageRequest

    @Test fun `a page keeps to its struts folder`() {
        for ((url, origin, folder) in listOf(
            Triple("https://acme.sphinx.chat/lab/?app=x&solo=1", "https://acme.sphinx.chat", "/lab/"),
            Triple("https://ACME.sphinx.chat:443/lab/?app=x&solo=1", "https://acme.sphinx.chat", "/lab/"),
            Triple("http://127.0.0.1:3417/?app=x&solo=1", "http://127.0.0.1:3417", "/"),
        )) {
            val request = page(url, token = "t\"</script>")
            assertEquals(origin, request.origin)
            assertEquals(folder, request.folder)
            assertTrue(request.serves("$origin${folder}jobs/j/files/a.md?t=1"))
            assertFalse(request.serves("https://evil.example$folder"))
            if (folder != "/") assertFalse(request.serves("$origin/other/"))
            assertEquals("Bearer t\"</script>", request.request.headers["Authorization"])
            assertEquals(request.url, request.request.url)
            assertFalse(request.url.contains("script"))
            // The token rides as a JSON literal, checked against the origin and the folder before it is set.
            assertTrue(request.handOver.contains("[\"$origin\",\"$folder\",\"t\\\"</script>\"]"))
            assertTrue(request.handOver.contains("location.origin === origin && location.pathname.startsWith(folder)"))
        }
    }

    @Test fun `serves needs the same origin not just the same prefix`() {
        val request = page("https://acme.sphinx.chat/lab/?app=x&solo=1")
        assertFalse(request.serves("http://acme.sphinx.chat/lab/"))
        assertFalse(request.serves("https://acme.sphinx.chat:8443/lab/"))
        assertFalse(request.serves("https://acme.sphinx.chat.evil.com/lab/"))
        assertFalse(request.serves("https://user@evil.example/lab/"))
        assertTrue(request.serves("https://user@acme.sphinx.chat/lab/x"))
        assertFalse(request.serves("data:text/html,hi"))
        assertFalse(request.serves("about:blank"))
        assertFalse(request.serves("not a url"))
        // A path that merely starts like the folder is not in it.
        assertFalse(request.serves("https://acme.sphinx.chat/lab"))
        assertFalse(request.serves("https://acme.sphinx.chat/laboratory/"))
    }

    /// The script is the iOS one character for character, with the literals JSON-encoded and no `/` escaped.
    @Test fun `the hand over is iOS's script`() {
        val request = page("https://acme.sphinx.chat/lab/?app=x&solo=1", token = "tok/en")
        val expected = "(() => { const [origin, folder, key] = [\"https://acme.sphinx.chat\",\"/lab/\",\"tok/en\"];\n" +
            "  if (location.origin === origin && location.pathname.startsWith(folder)) {\n" +
            "    try { sessionStorage.setItem(\"strut/apiKey\", key); } catch (e) {} } })();"
        assertEquals(expected, request.handOver)
        assertFalse(request.handOver.contains("\\/"))
        assertEquals("if (window.top === window) {\n$expected\n}", request.mainFrameHandOver)
    }

    @Test fun `json literals escape what a script needs and no slash`() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001/é\"", OrgPageRequest.jsonLiteral("a\"b\\c\n\r\t\b\u000c\u0001/é"))
    }

    /// A link to another app opens that app alone on the org's strut, at the view it names, with the token; a page loaded again
    /// goes where it moved to, with the token, while that is strut's UI.
    @Test fun `a link to another app opens it alone on the orgs strut with the token`() {
        val request = page("https://acme.sphinx.chat/lab/?app=x&solo=1")
        val board = "https://acme.sphinx.chat/lab/?app=board&input=v%3D1"
        val link = request.appLink(board, from = request.url)!!
        val there = request.request(link)!!
        assertEquals("https://acme.sphinx.chat/lab/?app=board&input=v%3D1&solo=1", there.url)
        assertEquals("Bearer t", there.headers["Authorization"])
        // From a file (words an org or a model wrote), none.
        assertNull(request.appLink(board, from = "https://acme.sphinx.chat/lab/?artifact=%2Fjobs%2Fj%2Ffiles%2Fr.md&solo=1"))
        assertNull(request.appLink(board, from = null))
        // Another service on the host, no app, or the org's own peer's app: no link.
        assertNull(request.appLink("https://acme.sphinx.chat/other/?app=board", from = request.url))
        assertNull(request.appLink("https://acme.sphinx.chat/lab/?wf=clip&run=1", from = request.url))
        assertNull(request.appLink("https://acme.sphinx.chat/lab/?app=%40globex%2Fx", from = request.url))
        val moved = "https://acme.sphinx.chat/lab/?app=x&input=v%3D2&solo=1"
        assertEquals(moved, request.request(at = moved)?.url)
        assertEquals("Bearer t", request.request(at = moved)?.headers?.get("Authorization"))
        // A file opened in place, or anywhere else: not this.
        assertNull(request.request(at = "https://acme.sphinx.chat/lab/jobs/j/files/a.pdf?t=1"))
        assertNull(request.request(at = "https://evil.example/lab/?app=x"))
        assertNull(request.request(at = null))
    }

    @Test fun `a request is never described with its token`() {
        val request = page("https://acme.sphinx.chat/lab/?app=x&solo=1", token = "jwt-secret", title = "Board")
        assertFalse("$request".contains("jwt") || "$request".contains("Board"))
        assertEquals("org page on Acme", "$request")
        assertFalse(request.request.toString().contains("jwt"))
        assertFalse(request.toString().contains("jwt"))
        assertFalse(HivePeer("acme", "https://acme.sphinx.chat", "jwt-secret", "Acme").toString().contains("jwt"))
        // The same request only by identity, as on iOS.
        assertEquals(request, request)
        assertNotEquals(request, page("https://acme.sphinx.chat/lab/?app=x&solo=1", token = "jwt-secret"))
    }

    @Test fun `an app opens on its workspaces own strut with its token`() {
        val peers = listOf(
            HivePeer("acme", "https://acme.sphinx.chat:3355/lab", "jwt-acme", "Acme"),
            HivePeer("globex", "https://globex.example", "jwt-globex", null),
        )
        val app = WorkspaceApp("acme", "hive/board", "Board")
        val opened = OrgPageRequest.opening(app, peers)!!
        assertEquals("https://acme.sphinx.chat:3355/lab/?app=hive%2Fboard&solo=1", opened.url)
        assertEquals("acme", opened.peer)
        assertEquals("Acme", opened.workspace)
        assertEquals("Board", opened.title)
        assertEquals("Bearer jwt-acme", opened.request.headers["Authorization"])
        // Titled by the app's own; a workspace with no label is read by its id.
        assertEquals("globex", OrgPageRequest.opening(WorkspaceApp("globex", "x", "X"), peers)!!.workspace)
        // No workspace, or one not live: none.
        assertNull(OrgPageRequest.opening(WorkspaceApp(null, "mine", "Mine"), peers))
        assertNull(OrgPageRequest.opening(WorkspaceApp("other", "a", "A"), peers))
        assertNull(OrgPageRequest.opening(app, listOf(HivePeer("acme", "not a url", "t", null))))
    }

    @Test fun `the title is the apps own else the workspace and another app is the workspace`() {
        assertEquals("Board", page("https://a.io/?app=x&solo=1", title = "Board").title(showingAnotherApp = false))
        assertEquals("Acme", page("https://a.io/?app=x&solo=1", title = "Board").title(showingAnotherApp = true))
        assertEquals("Acme", page("https://a.io/?app=x&solo=1", title = "").title(false))
        assertEquals("Acme", page("https://a.io/?app=x&solo=1", title = null).title(false))
    }

    @Test fun `a page shows another app only when its address names a different one`() {
        val request = page("https://acme.sphinx.chat/lab/?app=x&solo=1")
        assertFalse(request.showsAnotherApp(null))
        assertFalse(request.showsAnotherApp(request.url))
        // The same app at another view is the same app.
        assertFalse(request.showsAnotherApp("https://acme.sphinx.chat/lab/?app=x&input=v%3D2&solo=1"))
        assertTrue(request.showsAnotherApp("https://acme.sphinx.chat/lab/?app=board&solo=1"))
        // A file is no app.
        assertFalse(request.showsAnotherApp("https://acme.sphinx.chat/lab/jobs/j/files/a.md"))
        assertEquals(StrutAppHandle("x"), request.shownApp(request.url))
        assertNull(request.shownApp("https://evil.example/lab/?app=x"))
    }

    @Test fun `reload and try again load strut's ui with the bearer and a file as it is`() {
        val request = page("https://acme.sphinx.chat/lab/?app=x&solo=1")
        val moved = "https://acme.sphinx.chat/lab/?app=x&input=v%3D2&solo=1"
        val file = "https://acme.sphinx.chat/lab/jobs/j/files/a.pdf?t=1"
        assertEquals(moved, (request.reload(moved) as PhonePage.Reload.Load).request.url)
        assertEquals(PhonePage.Reload.InPlace, request.reload(file))
        assertEquals(request.url, (request.reload(null) as PhonePage.Reload.Load).request.url)
        // Try again: what failed when that was strut's UI; else where the page is; else its own request.
        assertEquals(moved, request.retry(moved, null).url)
        assertEquals(moved, request.retry(file, moved).url)
        assertEquals(moved, request.retry(null, moved).url)
        assertEquals(request.url, request.retry(file, file).url)
        assertEquals(request.url, request.retry(null, null).url)
        assertEquals("Bearer t", request.retry(file, file).headers["Authorization"])
    }

    @Test fun `a page is live while its workspace is`() {
        val request = page("https://acme.sphinx.chat/lab/?app=x&solo=1")
        val acme = HivePeer("acme", "https://acme.sphinx.chat", "t", null)
        val globex = HivePeer("globex", "https://globex.example", "t", null)
        assertTrue(request.isLive(listOf(globex, acme)))
        assertFalse(request.isLive(listOf(globex)))
        assertFalse(request.isLive(emptyList()))
    }

    @Test fun `origins are read as a page reads them`() {
        assertEquals("https://acme.io", UrlParts.origin("HTTPS://ACME.io:443/x"))
        assertEquals("http://acme.io", UrlParts.origin("http://acme.io:80/"))
        assertEquals("https://acme.io:80", UrlParts.origin("https://acme.io:80/"))
        assertEquals("http://[::1]:3000", UrlParts.origin("http://[::1]:3000/x"))
        assertEquals("http://my_host:81", UrlParts.origin("http://my_host:81/x"))
        assertNull(UrlParts.origin("about:blank"))
        assertNull(UrlParts.origin("https://acme.io:notaport/"))
        assertNull(UrlParts.origin("https:///x"))
    }

    // MARK: OrgNavigation

    private val strut = page("https://acme.sphinx.chat/lab/?app=x&solo=1")

    private fun decide(url: String, main: Boolean = true, gesture: Boolean = false, touch: Long? = null, now: Long = 10_000) =
        OrgNavigation.decide(strut, url, main, gesture, touch, now)

    @Test fun `the strut's own folder loads and nothing else loads here`() {
        assertEquals(OrgNavigation.Decision(true), decide("https://acme.sphinx.chat/lab/?app=board"))
        assertEquals(OrgNavigation.Decision(true), decide("https://acme.sphinx.chat/lab/jobs/j/files/a.md", main = false))
        // Its own frames.
        assertEquals(OrgNavigation.Decision(true), decide("about:srcdoc", main = false))
        assertEquals(OrgNavigation.Decision(true), decide("about:blank"))
        // data: and blob: only in a frame, never as the whole page.
        assertEquals(OrgNavigation.Decision(false), decide("data:text/html,hi"))
        assertEquals(OrgNavigation.Decision(true), decide("data:text/html,hi", main = false))
        assertEquals(OrgNavigation.Decision(false), decide("blob:https://acme.sphinx.chat/uuid"))
        assertEquals(OrgNavigation.Decision(true), decide("blob:https://acme.sphinx.chat/uuid", main = false))
        // Elsewhere on the host, another host, other schemes: nothing.
        assertEquals(OrgNavigation.Decision(false), decide("https://acme.sphinx.chat/other/"))
        assertEquals(OrgNavigation.Decision(false), decide("https://evil.example/lab/"))
        for (scheme in listOf("file:///etc/passwd", "javascript:alert(1)", "intent://x#Intent;end", "market://details?id=x", "tel:1", "ftp://x/y", "content://x/y")) {
            assertEquals(scheme, OrgNavigation.Decision(false), decide(scheme))
        }
        assertEquals(OrgNavigation.Decision(false), decide("not a url"))
    }

    @Test fun `a link elsewhere goes out only from the main frame on the persons own tap within a second`() {
        val url = "https://example.com/a"
        assertEquals(OrgNavigation.Decision(false, openOutside = true), decide(url, gesture = true, touch = 9_500))
        assertEquals(OrgNavigation.Decision(false, openOutside = true), decide("http://example.com/a", gesture = true, touch = 9_001))
        // A script's click has no gesture; no touch, or a touch too long ago, is no tap; a frame's link never goes out.
        assertEquals(OrgNavigation.Decision(false), decide(url, gesture = false, touch = 9_500))
        assertEquals(OrgNavigation.Decision(false), decide(url, gesture = true, touch = null))
        assertEquals(OrgNavigation.Decision(false), decide(url, gesture = true, touch = 9_000))
        assertEquals(OrgNavigation.Decision(false), decide(url, gesture = true, touch = 8_000))
        assertEquals(OrgNavigation.Decision(false), decide(url, main = false, gesture = true, touch = 9_500))
        // A touch in the future (a clock that stepped back) is not one.
        assertEquals(OrgNavigation.Decision(false), decide(url, gesture = true, touch = 10_500))
        // Other schemes never go out, tap or not.
        assertEquals(OrgNavigation.Decision(false), decide("tel:1", gesture = true, touch = 9_500))
    }

    @Test fun `a new window is never a window`() {
        val board = "https://acme.sphinx.chat/lab/?app=board&input=v%3D1"
        val from = strut.url
        // Another app of the org's: here, with the token, only on a tap.
        val here = OrgNavigation.window(strut, board, from, personActed = true) as OrgNavigation.Window.Here
        assertEquals("https://acme.sphinx.chat/lab/?app=board&input=v%3D1&solo=1", here.url)
        assertEquals("Bearer t", here.request!!.headers["Authorization"])
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, board, from, personActed = false))
        // From a file's page: nothing (a file's links are an org's or a model's).
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, board, "https://acme.sphinx.chat/lab/jobs/j/files/a.md", true))
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, board, null, true))
        // Anything else of strut's UI (a run, a job): nowhere.
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, "https://acme.sphinx.chat/lab/?wf=clip&run=1", from, true))
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, "https://acme.sphinx.chat/lab/?app=%40globex%2Fx", from, true))
        // A file as it is, with no bearer.
        val file = "https://acme.sphinx.chat/lab/jobs/j/files/a.pdf?t=1"
        assertEquals(OrgNavigation.Window.Here(null, file), OrgNavigation.window(strut, file, from, true))
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, file, from, false))
        // A link elsewhere goes out as a link does, on a tap; never another scheme.
        assertEquals(OrgNavigation.Window.Outside("https://example.com/"), OrgNavigation.window(strut, "https://example.com/", from, true))
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, "https://example.com/", from, false))
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, "javascript:alert(1)", from, true))
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, "intent://x#Intent;end", from, true))
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, "not a url", from, true))
    }

    @Test fun `a tap counts for a second`() {
        assertTrue(OrgNavigation.personActed(1_000, 1_999))
        assertFalse(OrgNavigation.personActed(1_000, 2_000))
        assertFalse(OrgNavigation.personActed(null, 2_000))
    }
}
