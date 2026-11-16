package com.sphinx.peel.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// Ports of iOS `ControlEnvelopeTests` (deliverables and their handles), `CallChatTests` (strut's entries resolved against a workspace),
/// `ControlRowTests` (a tribe's app asks first, strut's entries in a tribe's message) and `OrgPageTests` (`onOrg`). Robolectric for
/// `org.json`.
@RunWith(RobolectricTestRunner::class)
class AgentArtifactTest {
    /// A message body: `{"content": …, "metadata": "<json text>"}`.
    private fun body(metadata: String, content: String = "Here."): String =
        JSONObject().put("content", content).put("metadata", metadata).toString()

    // MARK: Deliverables of an agent's reply

    @Test fun `a replys deliverables are read and a link kept only when https`() {
        val reply = body(
            """{"peel":{"job":{"id":"j_7","title":"Lisbon flights","state":"asking"},
            "artifacts":[{"id":"plan","title":"Plan","label":"Plan","url":"https://example.com/plan"},
            {"id":"page","title":"Page","url":"file:///etc/passwd"},{"title":""},"junk",{"id":"x","title":"Shot","url":"javascript:alert(1)"},
            {"title":"Plain","url":"http://example.com"},{"title":"Spoof","url":"https://bank.com@evil.example/x"}],
            "tools":[{"name":"web_search"}]}}""",
        )
        val artifacts = AgentMessages.artifacts(reply)
        assertEquals(listOf("Plan", "Page", "Shot", "Plain", "Spoof"), artifacts.map { it.title })
        assertEquals(listOf("https://example.com/plan", null, null, null, null), artifacts.map { it.url })
        assertEquals("Plan", artifacts[0].label)
        assertEquals("plan", artifacts[0].id)
        // No id: the title is it.
        assertEquals("Plain", artifacts[3].id)
    }

    @Test fun `where the bridge says each deliverable opens is read beside its words`() {
        val reply = body(
            """{"peel":{"job":{"id":"j-7","state":"done"},"artifacts":[
            {"id":"plan","kind":"markdown","title":"Plan","path":"/peers/stakwork/jobs/j-7/files/plan.md"},
            {"id":"visits","kind":"app","title":"Visits","app":"@stakwork/visits"},
            {"id":"out","title":"Escape","path":"/peers/stakwork/jobs/j-7/files/../../secrets.json"},
            {"id":"odd","title":"Odd","path":5}]}}""",
        )
        val artifacts = AgentMessages.artifacts(reply)
        assertEquals(listOf("Plan", "Visits", "Escape", "Odd"), artifacts.map { it.title })
        assertEquals(
            listOf(AgentHandle.File("/peers/stakwork/jobs/j-7/files/plan.md"), AgentHandle.App("@stakwork/visits"), null, null),
            artifacts.map { it.handle },
        )
        assertEquals(listOf("stakwork", "stakwork", null, null), artifacts.map { it.handle?.peer })
    }

    @Test fun `a control message has no deliverables and nothing malformed costs the rest`() {
        assertTrue(AgentMessages.artifacts(body("""{"peel":{"v":1,"op":"ack","re":"r","artifacts":[{"title":"x"}]}}""")).isEmpty())
        for (bad in listOf("""{"peel":"x"}""", """{"peel":{"artifacts":"x"}}""", """{"tz":"GMT+1"}""", "not json", "")) {
            assertTrue(bad, AgentMessages.artifacts(body(bad)).isEmpty())
        }
        assertTrue(AgentMessages.artifacts(null).isEmpty())
        assertTrue(AgentMessages.artifacts("not json").isEmpty())
        assertTrue(AgentMessages.artifacts("""{"content":"hi"}""").isEmpty())
        // At most 20 are read from a message; metadata past 64 KB is none.
        val many = (0 until 30).joinToString(",") { """{"title":"T$it"}""" }
        assertEquals(AgentArtifact.MOST, AgentMessages.artifacts(body("""{"peel":{"artifacts":[$many]}}""")).size)
        val big = """{"peel":{"artifacts":[{"title":"T","summary":"${"x".repeat(AgentMessages.MAX_METADATA)}"}]}}"""
        assertTrue(AgentMessages.artifacts(body(big)).isEmpty())
    }

    @Test fun `words are cut at 200`() {
        val one = AgentMessages.artifacts(body("""{"peel":{"artifacts":[{"title":"${"t".repeat(300)}","label":"${"l".repeat(300)}","id":"${"i".repeat(300)}"}]}}""")).single()
        assertEquals(200, one.title.length)
        assertEquals(200, one.label!!.length)
        assertEquals(200, one.id.length)
    }

    // MARK: Handles

    @Test fun `a file strut serves is kept`() {
        for ((path, peer) in listOf(
            "/jobs/j-1/files/plan.md" to null,
            "/artifacts/1792155720000/shots/001.png" to null,
            "/peers/stakwork/jobs/peer%2Fj-1/files/docs/Lisbon%20plan.md" to "stakwork",
            "/peers/acme/peers/billing/artifacts/r7/100%25.md" to "acme",
            "/peers/a/peers/b/peers/c/jobs/j/files/x" to "a",
        )) {
            val handle = AgentHandle.file(path)
            assertEquals(path, AgentHandle.File(path), handle)
            assertEquals(path, peer, handle?.peer)
        }
    }

    @Test fun `any other path is dropped`() {
        for (path in listOf(
            "/jobs/j/files/../secrets.json", "/jobs/j/files/./x", "/jobs/j/files/%2e%2E/x", "/jobs/../files/x",
            "/jobs/j/files/a%2F..%2Fb", "/jobs/j/files/a%5Cb", "/jobs/j/files/a%zz", "/jobs/j/files/a b.md",
            "/jobs/j/files/x.md?key=k", "/jobs/j/files/x.md#top", "/jobs/j/files/a\\b", "/jobs/j/files/a\nb",
            "/jobs/j/files", "/jobs/j/files/", "/jobs/j/files//x", "/jobs/-j/files/x", "jobs/j/files/x",
            "//evil.example/jobs/j/files/x", "https://example.com/jobs/j/files/x", "javascript:alert(1)",
            "/apps/visits/v2/files/dist/index.html", "/workflows/job/runs/r1", "/peers/acme",
            "/peers/a/peers/b/peers/c/peers/d/jobs/j/files/x", "/peers/../jobs/j/files/x", "/peers/a%2Fb/jobs/j/files/x",
            "/artifacts/r7", "/artifacts/a%2Fb/x", "/jobs/j/x/files/y", "/jobs/j/files/a%00b", "/jobs/j/files/a%0Ab",
            "/jobs/j/files/" + "a".repeat(500),
        )) assertNull(path, AgentHandle.file(path))
    }

    @Test fun `an app strut names is kept`() {
        for ((app, peer) in listOf(
            "visits" to null, "strut/views" to null, "@stakwork/visits" to "stakwork", "@acme/hive/board" to "acme",
            "visits@v2" to null, "@acme/hive/board@v12" to "acme",
        )) {
            val handle = AgentHandle.app(app)
            assertEquals(app, AgentHandle.App(app), handle)
            assertEquals(app, peer, handle?.peer)
        }
    }

    @Test fun `it names the app and its version`() {
        assertEquals("hive/board" to "v12", AgentHandle.app("@acme/hive/board@v12")?.appNameAndVersion)
        assertEquals("visits" to null, AgentHandle.app("visits")?.appNameAndVersion)
        assertNull(AgentHandle.file("/artifacts/r7/x.md")?.appNameAndVersion)
    }

    @Test fun `any other app is dropped`() {
        for (app in listOf(
            "", "@acme", "@acme/", "@/visits", "@a b/visits", "@acme/@b/x", "../x", "visits@", "visits@2", "visits@v2@v3",
            "@acme/x@latest", "@acme/@b/x@v1", "a//b", "/a", "a b", "v2", "x/v2", "@acme/x/v12", "data.db", "x/data.db",
        )) assertNull(app, AgentHandle.app(app))
    }

    /// iOS `OrgPageTests`: an org's file or app opens by its handle, the brain's `/peers/<org>` hop taken off.
    @Test fun `a handle opens on its orgs own strut`() {
        for ((path, peer, onOrg) in listOf(
            Triple("/peers/acme/jobs/j-1/files/plan.md", "acme", "/jobs/j-1/files/plan.md"),
            Triple("/peers/acme/jobs/peer%2Fj-1/files/Lisbon%20plan.md", "acme", "/jobs/peer%2Fj-1/files/Lisbon%20plan.md"),
            Triple("/peers/acme/peers/billing/jobs/j-2/files/r.md", "acme", "/peers/billing/jobs/j-2/files/r.md"),
            Triple("/peers/acme.io/artifacts/1793/shot.png", "acme.io", "/artifacts/1793/shot.png"),
        )) assertEquals(path, OrgPage(peer, "artifact", onOrg), AgentHandle.file(path)?.onOrg)
        assertEquals(OrgPage("acme", "app", "hive/board"), AgentHandle.app("@acme/hive/board")?.onOrg)
        assertEquals("app=hive%2Fboard&solo=1", UrlParts.parse(AgentHandle.app("@acme/hive/board")!!.onOrg!!.url("https://acme.sphinx.chat/lab")!!)?.query)
        // The version pinned stays in the name strut's UI is asked for.
        assertEquals("hive/board@v3", AgentHandle.app("@acme/hive/board@v3")?.onOrg?.value)
        // One of the brain's own has no page on an org.
        assertNull(AgentHandle.file("/jobs/j-1/files/plan.md")!!.onOrg)
        assertNull(AgentHandle.file("/artifacts/1793/shot.png")!!.onOrg)
        assertNull(AgentHandle.app("reports")!!.onOrg)
    }

    // MARK: strut's entries (a call's chat, a tribe's post), resolved against the workspace

    private fun strut(entries: String, workspace: String = "acme") = """{"workspace":"$workspace","artifacts":[$entries]}"""
    private fun read(entries: String, workspace: String = "acme") = AgentMessages.strutEntries(JSONObject(strut(entries, workspace)))

    @Test fun `each entry is resolved against the workspace`() {
        val read = read(
            """{"id":"plan","kind":"markdown","title":"Plan","label":"Plan","url":"/jobs/j-41/files/plan.md"},
            {"id":"ui","kind":"app","title":"Bench","url":"/apps/bench/v3/files/dist/index.html","app":{"name":"bench","version":"v3"}},
            {"id":"live","kind":"app","title":"Board","app":{"name":"hive/board"}},
            {"id":"shot","kind":"image","title":"Shot","peer":"web","job":"peer/j-7","path":"shots/a b.png"},
            {"id":"far","title":"Far","peer":"web","via":"billing","job":"j-9","path":"/r.md"},
            {"id":"pr","kind":"pull_request","title":"Fix login","url":"https://github.com/acme/web/pull/12"}""",
        )
        assertEquals(
            listOf(
                AgentHandle.File("/peers/acme/jobs/j-41/files/plan.md"),
                AgentHandle.App("@acme/bench@v3"),
                AgentHandle.App("@acme/hive/board"),
                AgentHandle.File("/peers/acme/peers/web/jobs/peer%2Fj-7/files/shots/a%20b.png"),
                AgentHandle.File("/peers/acme/peers/web/peers/billing/jobs/j-9/files/r.md"),
                null,
            ),
            read.map { it.handle },
        )
        assertEquals("Plan", read.first().label)
        assertNull(read.first().url)
        assertEquals("https://github.com/acme/web/pull/12", read.last().url)
        // The org's own strut is one hop nearer.
        assertEquals("/peers/web/peers/billing/jobs/j-9/files/r.md", read[4].handle?.onOrg?.value)
    }

    @Test fun `it opens nothing it cannot name`() {
        val read = read(
            """{"id":"up","title":"Up","peer":"web","job":"j","path":"../secrets.json"},
            {"id":"bad","title":"Bad","peer":"a/b","job":"j","path":"x.md"},
            {"id":"peerapp","kind":"app","title":"Peer app","peer":"web","app":{"name":"bench"}},
            {"id":"host","title":"Host","url":"//evil.example/jobs/j/files/x"},
            {"id":"route","title":"Route","url":"/workflows/job/runs/r1"}""",
        )
        assertEquals(listOf("Up", "Bad", "Peer app", "Host", "Route"), read.map { it.title })
        assertTrue(read.all { it.handle == null && it.url == null })
        assertNull(read("""{"id":"x","title":"X","url":"/jobs/j/files/x"}""", workspace = "../x").first().handle)
    }

    @Test fun `an app is read by its document link`() {
        val read = read(
            """{"id":"a","kind":"app","title":"Canvas","url":"/apps/infinite-canvas/v21/files/dist/index.html"},
            {"id":"b","kind":"app","title":"Active","url":"/apps/tools%2Fboard/files/dist/index.html"},
            {"id":"c","kind":"app","title":"Other file","url":"/apps/infinite-canvas/v21/files/data.json"},
            {"id":"d","kind":"app","title":"Bad name","url":"/apps/bad%20name/v1/files/dist/index.html"}""",
            workspace = "hive",
        )
        assertEquals(listOf(AgentHandle.App("@hive/infinite-canvas@v21"), AgentHandle.App("@hive/tools/board"), null, null), read.map { it.handle })
    }

    @Test fun `strut's entries are never malformed into a failure`() {
        for (strut in listOf("\"x\"", "{}", """{"workspace":"acme","artifacts":"x"}""", """{"workspace":"acme","artifacts":[{"id":"no-title"}]}""", "null")) {
            assertTrue(strut, AgentMessages.strutEntries(runCatching { JSONObject(strut) }.getOrNull() ?: strut).isEmpty())
        }
    }

    /// A tribe member's message carries them in `metadata.strut`; never `metadata.peel`'s.
    @Test fun `a tribe's post carries strut's entries`() {
        val strutText = """{"workspace":"acme","artifacts":[{"id":"plan","title":"Plan","url":"/jobs/j-1/files/plan.md"},""" +
            """{"id":"board","kind":"app","title":"Board","app":{"name":"board"}}]}"""
        val post = body("""{"strut":$strutText}""")
        assertEquals(
            listOf(AgentHandle.File("/peers/acme/jobs/j-1/files/plan.md"), AgentHandle.App("@acme/board")),
            AgentMessages.strutArtifacts(post).map { it.handle },
        )
        // Not from `peel`, and the other way round.
        assertTrue(AgentMessages.artifacts(post).isEmpty())
        assertTrue(AgentMessages.strutArtifacts(body("""{"peel":{"artifacts":[{"title":"x"}]}}""")).isEmpty())
        assertTrue(AgentMessages.strutArtifacts(body("\"x\"")).isEmpty())
        assertTrue(AgentMessages.strutArtifacts(body("""{"strut":"x"}""")).isEmpty())
    }

    // MARK: A tribe's app asks first

    @Test fun `a tribes app asks first naming what it opens`() {
        val app = AgentArtifact("a", "Weekly report", handle = AgentHandle.app("@acme/board@v3"))
        assertTrue(app.asksBeforeOpening(inTribe = true))
        assertFalse(app.asksBeforeOpening(inTribe = false))
        assertEquals("Open the app \u201cboard\u201d v3 from Acme? Anyone in the tribe could have sent this link.", app.tribeAppQuestion("Acme"))
        assertEquals("Open the app \u201cboard\u201d from Acme? Anyone in the tribe could have sent this link.",
            AgentArtifact("a", "x", handle = AgentHandle.app("@acme/board")).tribeAppQuestion("Acme"))
        val file = AgentArtifact("f", "Plan", handle = AgentHandle.file("/peers/acme/jobs/j/files/plan.md"))
        assertFalse(file.asksBeforeOpening(inTribe = true))
        assertEquals("Open the app this app from Acme? Anyone in the tribe could have sent this link.", file.tribeAppQuestion("Acme"))
    }

    @Test fun `percent decoding keeps a plus and is strict`() {
        assertEquals("a+b", percentDecode("a+b"))
        assertEquals("a b", percentDecode("a%20b"))
        assertNull(percentDecode("%zz"))
        assertNull(percentDecode("%C3"))
        assertEquals("a/b", percentDecode("a%2Fb"))
    }
}
