package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ports of iOS `OrgPageTests` (`OrgPage.hiveApp`, sharing an app) and `StadeumTests` (`opening`, `tap`, `opensFromTribe`), and the
/// app-wide link handler of `MainTabs`.
class OpeningsTest {
    private val acme = HivePeer("acme", "https://acme.sphinx.chat:3355/lab", "jwt-acme", "Acme")
    private fun openings(live: List<HivePeer> = listOf(acme), connected: Boolean = true) = Openings(live, connected)
    private fun app(name: String, peer: String? = "sphinx") = WorkspaceApp(peer, name, name)

    // MARK: Hive's link for an app

    @Test fun `a shared app is Hive's page for it and opens the same app it came from`() {
        for (name in listOf("board", "acme/hvac/invoices", "a.b-c_d/x9")) {
            val link = app(name).shareLink()!!
            assertEquals(link, OrgPage.app(name, "sphinx"), OrgPage.hiveApp(link))
        }
    }

    @Test fun `a link that is not Hive's page for an app opens nothing`() {
        for (link in listOf(
            "http://hive.sphinx.chat/w/sphinx/apps/board",
            "https://example.com/w/sphinx/apps/board",
            "https://hive.sphinx.chat.evil.com/w/sphinx/apps/board",
            "https://evil.com/hive.sphinx.chat/w/sphinx/apps/board",
            "https://user@hive.sphinx.chat/w/sphinx/apps/board",
            "https://hive.sphinx.chat:8443/w/sphinx/apps/board",
            "https://hive.sphinx.chat:443/w/sphinx/apps/board",
            "https://hive.sphinx.chat/w/sphinx/apps/",
            "https://hive.sphinx.chat/w/sphinx/apps",
            "https://hive.sphinx.chat/w/sphinx/apps/board/",
            "https://hive.sphinx.chat/w/sphinx/apps/v2",
            "https://hive.sphinx.chat/w/sphinx/apps/acme/v2/board",
            "https://hive.sphinx.chat/w/sphinx/apps/data.db",
            "https://hive.sphinx.chat/w/sphinx/apps/..",
            "https://hive.sphinx.chat/w/sphinx/apps/a/../b",
            "https://hive.sphinx.chat/w/sphinx/apps/a%2Fb",
            "https://hive.sphinx.chat/w/sphinx/apps/%40acme",
            "https://hive.sphinx.chat/w/sphinx/apps/board/extra//more",
            "https://hive.sphinx.chat/w/sphinx/tasks/board",
            "https://hive.sphinx.chat/x/sphinx/apps/board",
            "https://hive.sphinx.chat/w/-bad/apps/board",
            "https://hive.sphinx.chat/w/acme",
            "not a url",
            "",
        )) assertNull(link, OrgPage.hiveApp(link))
    }

    @Test fun `the scheme and host ignore case and the query and fragment are ignored`() {
        for (link in listOf(
            "HTTPS://HIVE.SPHINX.CHAT/w/sphinx/apps/board",
            "https://hive.sphinx.chat/w/sphinx/apps/board?input=view-2",
            "https://hive.sphinx.chat/w/sphinx/apps/board#top",
        )) assertEquals(link, OrgPage("sphinx", "app", "board"), OrgPage.hiveApp(link))
    }

    @Test fun `another hive is another host`() {
        assertEquals(OrgPage.app("board", "acme"), OrgPage.hiveApp("https://hive.example.org/w/acme/apps/board", hive = "https://hive.example.org"))
        assertNull(OrgPage.hiveApp("https://hive.sphinx.chat/w/acme/apps/board", hive = "https://hive.example.org"))
    }

    // MARK: Opening

    @Test fun `an app opens on its workspaces own strut with its token`() {
        val opened = (openings().opening(WorkspaceApp("acme", "hive/board", "Board")) as Opening.Page).request
        assertEquals("https://acme.sphinx.chat:3355/lab/?app=hive%2Fboard&solo=1", opened.url)
        assertEquals("acme", opened.peer)
        assertEquals("Acme", opened.workspace)
        assertEquals("Board", opened.title)
        assertEquals("Bearer jwt-acme", opened.request.headers["Authorization"])
        // Never described with its token.
        assertFalse("$opened".contains("jwt"))
        assertFalse(openings().opening(WorkspaceApp("acme", "x", "X")).toString().contains("jwt"))
    }

    @Test fun `something opens on its orgs own strut or says why not`() {
        val file = AgentHandle.file("/peers/acme/jobs/peer%2Fj-1/files/plan.md")!!
        // No Stadeum here yet, or Stadeum no longer knows this device.
        assertEquals(Opening.ConnectFirst, openings(emptyList(), connected = false).opening(file))
        val page = (openings().opening(file) as Opening.Page).request
        assertEquals("https://acme.sphinx.chat:3355/lab/?artifact=%2Fjobs%2Fpeer%252Fj-1%2Ffiles%2Fplan.md&solo=1", page.url)
        assertEquals("jwt-acme", page.request.headers["Authorization"]?.removePrefix("Bearer "))
        assertEquals("Acme", page.workspace)
        assertNull(page.title)
        // Titled by what the caller names; the token still hidden.
        val titled = (openings().opening(file, title = "Plan") as Opening.Page).request
        assertEquals("Plan", titled.title)
        assertFalse("$titled".contains("jwt") || "$titled".contains("Plan"))
        // An org this device holds no token for; one of the brain's own, which only the Mac reaches.
        assertEquals(Opening.NotPaired("globex"), openings().opening(AgentHandle.file("/peers/globex/jobs/j/files/a.md")!!))
        assertEquals(Opening.OnMac, openings().opening(AgentHandle.file("/jobs/j/files/a.md")!!))
        assertEquals(Opening.OnMac, openings().opening(AgentHandle.app("reports")!!))
        assertEquals(Opening.OnMac, openings().opening(WorkspaceApp(null, "mine", "Mine")))
        // A workspace whose address is no address does not open.
        assertEquals(Opening.NotPaired("acme"), Openings(listOf(HivePeer("acme", "not a url", "t", null)), true).opening(file))
    }

    @Test fun `a revoked or absent pairing says to connect first, a paired one without that org says not paired`() {
        val page = OrgPage.app("board", "acme")
        assertEquals(Opening.ConnectFirst, openings(emptyList(), connected = false).opening(page))
        assertEquals(Opening.NotPaired("acme"), openings(emptyList(), connected = true).opening(page))
        assertEquals(Opening.NotPaired("acme"), openings(listOf(HivePeer("globex", "https://g.example", "t", null))).opening(page))
    }

    // MARK: A tapped link

    @Test fun `a tapped hive link opens a paired workspaces app or says to connect first`() {
        val mine = "https://hive.sphinx.chat/w/acme/apps/hive/board"
        // No Stadeum here yet.
        assertEquals(LinkTap.ConnectFirst("acme"), openings(emptyList(), connected = false).tap(mine))
        val tapped = openings().tap(mine) as LinkTap.Open
        assertEquals(OrgPage.app("hive/board", "acme"), tapped.page)
        assertEquals("acme", tapped.request.peer)
        // A workspace not paired here, then links that are not Hive's apps.
        assertEquals(LinkTap.ConnectFirst("globex"), openings().tap("https://hive.sphinx.chat/w/globex/apps/board"))
        assertEquals(LinkTap.NotOurs, openings().tap("https://example.com/w/acme/apps/board"))
        assertEquals(LinkTap.NotOurs, openings().tap("http://hive.sphinx.chat/w/acme/apps/board"))
        assertEquals(LinkTap.NotOurs, openings().tap("https://hive.sphinx.chat/w/acme"))
        assertEquals(LinkTap.NotOurs, openings().tap("mailto:a@b.c"))
    }

    @Test fun `a deliverable sent in a tribe opens only on an org this device pairs`() {
        val o = openings()
        assertTrue(o.opensFromTribe(AgentHandle.app("@acme/board")!!))
        assertFalse(o.opensFromTribe(AgentHandle.file("/peers/initech/jobs/j/files/a.md")!!))
        assertFalse(o.opensFromTribe(AgentHandle.file("/jobs/j/files/a.md")!!))
        assertTrue(o.opensFromTribe(AgentHandle.file("/peers/acme/jobs/j/files/a.md")!!))
        assertFalse(openings(emptyList()).opensFromTribe(AgentHandle.app("@acme/board")!!))
    }

    @Test fun `a workspace is read by its name when live, else by its id`() {
        assertEquals("Acme", openings().workspaceName("acme"))
        assertEquals("@initech", openings().workspaceName("initech"))
    }

    // MARK: The app-wide handler

    private class Recorder(var tap: LinkTap, var reopened: Opening? = null) {
        val pages = mutableListOf<OrgPageRequest>()
        val said = mutableListOf<String>()
        val system = mutableListOf<String>()
        fun handler() = AppLinkHandler({ tap }, { reopened ?: Opening.OnMac }, { pages += it }, { said += it }, { system += it })
    }

    @Test fun `a tapped link for a paired workspace opens its page and nothing goes to the browser`() {
        val o = openings()
        val url = "https://hive.sphinx.chat/w/acme/apps/board"
        val r = Recorder(o.tap(url), reopened = o.opening(OrgPage.app("board", "acme")))
        r.handler().open(url)
        assertEquals(1, r.pages.size)
        assertEquals("acme", r.pages.single().peer)
        assertTrue(r.said.isEmpty() && r.system.isEmpty())
    }

    @Test fun `a link for an unpaired workspace says to connect it first and still goes to the browser`() {
        val url = "https://hive.sphinx.chat/w/globex/apps/board"
        val r = Recorder(openings().tap(url))
        r.handler().open(url)
        assertEquals(listOf("Connect @globex first"), r.said)
        assertEquals(listOf(url), r.system)
        assertTrue(r.pages.isEmpty())
    }

    @Test fun `any other link is the systems`() {
        val r = Recorder(LinkTap.NotOurs)
        r.handler().open("https://example.com/")
        assertEquals(listOf("https://example.com/"), r.system)
        assertTrue(r.pages.isEmpty() && r.said.isEmpty())
    }

    @Test fun `a page that cannot be opened again as it was tapped opens nothing`() {
        val o = openings()
        val url = "https://hive.sphinx.chat/w/acme/apps/board"
        val r = Recorder(o.tap(url), reopened = Opening.NotPaired("acme"))
        r.handler().open(url)
        assertTrue(r.pages.isEmpty() && r.system.isEmpty() && r.said.isEmpty())
    }

    // MARK: An artifact's line

    private fun artifact(handle: AgentHandle? = null, url: String? = null) = AgentArtifact("a", "Plan", handle = handle, url = url)

    @Test fun `a line says where it is`() {
        val o = openings()
        fun where(a: AgentArtifact) = com.sphinx.peel.ui.apps.ArtifactOpening.whereTo(a) { o.opening(it) }
        assertEquals("Acme", where(artifact(AgentHandle.app("@acme/board"))))
        assertEquals("example.com", where(artifact(url = "https://example.com/plan")))
        assertNull(where(artifact()))
        assertEquals("@globex isn't connected here", where(artifact(AgentHandle.app("@globex/board"))))
        assertEquals("On your Mac", where(artifact(AgentHandle.file("/jobs/j/files/a.md"))))
        // A link beats the reason a handle does not open.
        assertEquals("example.com", where(artifact(AgentHandle.app("@globex/board"), url = "https://example.com/p")))
        val offline = Openings(emptyList(), connected = false)
        assertEquals("Connect Stadeum in You to open", com.sphinx.peel.ui.apps.ArtifactOpening.whereTo(artifact(AgentHandle.app("@acme/board"))) { offline.opening(it) })
    }

    @Test fun `a tap opens its page on its orgs strut, else its link, else nothing`() {
        val o = openings()
        fun action(a: AgentArtifact) = com.sphinx.peel.ui.apps.ArtifactOpening.action(a) { handle, title -> o.opening(handle, title) }
        val page = action(artifact(AgentHandle.app("@acme/board")))
        assertEquals(com.sphinx.peel.ui.apps.ArtifactOpening.Action.Page(AgentHandle.app("@acme/board")!!, "Plan"), page)
        assertEquals(com.sphinx.peel.ui.apps.ArtifactOpening.Action.Link("https://example.com/p"), action(artifact(AgentHandle.app("@globex/board"), url = "https://example.com/p")))
        assertNull(action(artifact(AgentHandle.app("@globex/board"))))
        assertNull(action(artifact()))
        assertEquals(com.sphinx.peel.ui.apps.ArtifactOpening.Action.Link("https://example.com/p"), action(artifact(url = "https://example.com/p")))
    }

    // MARK: A link to another app in the page, Back returning

    private val strut = OrgPageRequest("https://acme.sphinx.chat:3355/lab/?app=x&solo=1", "tok", "acme", "Acme", "Board")

    @Test fun `a link to another app opens in the same page at the view it names, only on a tap`() {
        val from = strut.url
        val link = "https://acme.sphinx.chat:3355/lab/?app=board&input=view%3D%252Fruns%26run%3D12"
        val here = OrgNavigation.window(strut, link, from, personActed = true) as OrgNavigation.Window.Here
        assertEquals("https://acme.sphinx.chat:3355/lab/?app=board&input=view%3D%252Fruns%26run%3D12&solo=1", here.url)
        assertEquals("Bearer tok", here.request!!.headers["Authorization"])
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, link, from, personActed = false))
        // The page then shows another app: the title is its workspace; the first app's own title is back on going Back.
        assertTrue(strut.showsAnotherApp(here.url))
        assertEquals("Acme", strut.title(showingAnotherApp = true))
        assertFalse(strut.showsAnotherApp(from))
        assertEquals("Board", strut.title(showingAnotherApp = false))
        // The same app at another view is not another app.
        assertFalse(strut.showsAnotherApp("https://acme.sphinx.chat:3355/lab/?app=x&input=v%3D2&solo=1"))
    }

    @Test fun `a link to the orgs peers app, a run or a file is not followed`() {
        val from = strut.url
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, "https://acme.sphinx.chat:3355/lab/?app=%40globex%2Fx", from, true))
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, "https://acme.sphinx.chat:3355/lab/?wf=clip&run=1", from, true))
        // An input past a view's address is no link.
        val long = "%C3%A9".repeat(StrutAppLink.LONGEST_INPUT / 2 + 1)
        assertEquals(OrgNavigation.Window.Nothing, OrgNavigation.window(strut, "https://acme.sphinx.chat:3355/lab/?app=board&input=$long", from, true))
        // From a file's page, none.
        assertEquals(
            OrgNavigation.Window.Nothing,
            OrgNavigation.window(strut, "https://acme.sphinx.chat:3355/lab/?app=board", "https://acme.sphinx.chat:3355/lab/jobs/j/files/a.md", true),
        )
    }

    @Test fun `a page closes when its workspace is no longer live`() {
        assertTrue(strut.isLive(listOf(acme)))
        assertFalse(strut.isLive(listOf(HivePeer("globex", "https://g.example", "t", null))))
        assertFalse(strut.isLive(emptyList()))
    }
}
