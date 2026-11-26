package com.sphinx.peel.engine

import java.io.IOException
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkspaceAppsTest {
    private val answer = """{"apps":[
        {"name":"b-app","title":"Beta 10","description":"does\nthings","pinned":true,"activeVersion":"v2"},
        {"name":"a-app","pinned":true},
        {"name":"hidden","title":"Hidden","pinned":false},
        {"name":"../bad","pinned":true},
        {"name":"beta2","title":"Beta 2","pinned":true},
        {"title":"nameless","pinned":true}]}"""

    @Test
    fun `listing keeps only pinned apps with valid names`() {
        val apps = WorkspaceApp.listing(answer, "w1")
        assertEquals(listOf("b-app", "a-app", "beta2"), apps.map { it.name })
        assertEquals("a-app", apps[1].title)
        assertEquals("does things", apps[0].about)
        assertEquals("v2", apps[0].version)
        assertEquals("@w1/b-app", apps[0].id)
    }

    @Test
    fun `byTitle sorts A-Z with numbers in order and ties by id`() {
        val apps = WorkspaceApp.byTitle(WorkspaceApp.listing(answer, "w1"))
        assertEquals(listOf("a-app", "Beta 2", "Beta 10").map { it }, apps.map { it.title })
        val same = listOf(WorkspaceApp("z", "x", "T"), WorkspaceApp("a", "x", "T"))
        assertEquals(listOf("a", "z"), WorkspaceApp.byTitle(same).map { it.peer })
    }

    @Test
    fun `sources and filter`() {
        val apps = WorkspaceApp.listing(answer, "w1") + WorkspaceApp.listing("""{"apps":[{"name":"q","pinned":true}]}""", "w0")
        assertEquals(listOf("@w0", "@w1"), WorkspaceApp.sources(apps))
        assertEquals(listOf("q"), WorkspaceApp.filter(apps, "", "@w0").map { it.name })
        assertEquals(listOf("b-app"), WorkspaceApp.filter(apps, "THINGS", null).map { it.name })
        assertEquals(4, WorkspaceApp.filter(apps, "@w", null).size)
    }

    @Test
    fun `share link encodes each segment and has no token`() {
        val app = WorkspaceApp("w 1", "pkg/my app", "T")
        assertEquals("https://hive.sphinx.chat/w/w%201/apps/pkg/my%20app", app.shareLink())
        assertNull(WorkspaceApp(null, "x", "T").shareLink())
    }

    @Test
    fun `opening builds the solo app url on the peer strut`() {
        val peer = HivePeer("w1", "https://w1.example.com/lab", "tok", null)
        val page = OrgPageRequest.opening(WorkspaceApp("w1", "pkg/a", "A"), listOf(peer))!!
        assertEquals("https://w1.example.com/lab/?app=pkg%2Fa&solo=1", page.url)
        assertTrue("tok" !in page.url)
        assertNull(OrgPageRequest.opening(WorkspaceApp("other", "a", "A"), listOf(peer)))
    }

    @Test
    fun `gate follows live workspaces or a development home`() {
        assertTrue(!Stadeum.showsApps(emptyList(), null))
        assertTrue(Stadeum.showsApps(listOf(HivePeer("w1", "https://w1.example.com", "t", null)), null))
        assertTrue(Stadeum.showsApps(emptyList(), TalkHome(TalkHome.DEVELOPMENT, "Dev", "https://dev.example.com", "t")))
    }

    // Android: a public app opened here (a worker's, docs/phases/41-worker-onboarding.md) shows Apps with no workspace; the workspace
    // gate is unchanged.
    @Test
    fun `gate follows a public app opened here with no workspace`() {
        assertTrue(Stadeum.showsApps(emptyList(), null, openedApps = true))
        assertFalse(Stadeum.showsApps(emptyList(), null, openedApps = false))
        assertTrue(Stadeum.showsApps(listOf(HivePeer("w1", "https://w1.example.com", "t", null)), null, openedApps = false))
    }
}

@RunWith(RobolectricTestRunner::class)
class StadeumReadAppsTest {
    private val fast = HivePeer("fast", "https://fast.example.com", "t", null)
    private val slow = HivePeer("slow", "https://slow.example.com", "t", null)

    @Test
    fun `a fast workspace's apps show before a slow one answers`() = kotlinx.coroutines.runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<List<WorkspaceApp>>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            Stadeum.readApps(listOf(fast, slow)) { if (it.id == "fast") listOf(WorkspaceApp("fast", "a", "A")) else gate.await() }
        }
        assertEquals(listOf("fast"), Stadeum.apps.map { it.peer })
        assertTrue(Stadeum.readingApps)
        gate.complete(listOf(WorkspaceApp("slow", "b", "B")))
        job.join()
        assertEquals(setOf("fast", "slow"), Stadeum.apps.map { it.peer }.toSet())
        assertFalse(Stadeum.readingApps)
        assertTrue(Stadeum.unreadAppIds.isEmpty())
    }

    @Test
    fun `a failed workspace keeps its previous apps and is reported`() = kotlinx.coroutines.runBlocking {
        Stadeum.readApps(listOf(fast, slow)) { listOf(WorkspaceApp(it.id, "x", "X")) }
        Stadeum.readApps(listOf(fast, slow)) { if (it.id == "slow") throw IOException("down") else listOf(WorkspaceApp("fast", "y", "Y")) }
        assertEquals(setOf("slow:x", "fast:y"), Stadeum.apps.map { "${it.peer}:${it.name}" }.toSet())
        assertEquals(listOf("slow"), Stadeum.unreadAppIds)
    }
}

@RunWith(RobolectricTestRunner::class)
class PinnedAppRecordTest {
    private fun app(peer: String, name: String, title: String = name, version: String? = null) =
        WorkspaceApp(peer, name, title, version = version)

    private fun records(vararg apps: WorkspaceApp) = apps.mapNotNull { PinnedAppRecord.of(it) }

    @Test
    fun `a record keeps only what shows the row and comes back refreshing`() {
        val record = PinnedAppRecord.of(WorkspaceApp("acme", "board", "Board", about = "secret words", icon = "flask-conical", version = "v3"))!!
        assertEquals(listOf(record), PinnedAppRecord.decode(PinnedAppRecord.encode(listOf(record))))
        assertFalse(PinnedAppRecord.encode(listOf(record)).contains("secret"))
        assertTrue(record.app.refreshing)
        assertEquals("@acme/board", record.app.id)
        assertNull(record.app.about)
        assertTrue(PinnedAppRecord.decode("not json").isEmpty() && PinnedAppRecord.decode(null).isEmpty())
    }

    @Test
    fun `a workspace that answers replaces its records`() {
        val old = records(app("acme", "board", "Board", "v1"), app("acme", "gone"))
        val read = mapOf("acme" to PeerRead.Answered(listOf(app("acme", "board", "Boards", "v2"), app("acme", "new"))))
        val kept = PinnedAppRecord.reconcile(old, read, listOf("@acme/board", "@acme/gone"), setOf("acme"))
        // Removed: dropped. Renamed and newer: updated. Not pinned: not stored.
        assertEquals(listOf("@acme/board"), kept.map { it.id })
        assertEquals("Boards", kept[0].title)
        assertEquals("v2", kept[0].version)
    }

    @Test
    fun `a refusal drops the workspace's records but a timeout keeps them`() {
        val old = records(app("acme", "board"), app("globex", "wiki"))
        val pins = listOf("@acme/board", "@globex/wiki")
        for (status in listOf(401, 403, 404)) {
            val kept = PinnedAppRecord.reconcile(old, mapOf("acme" to PeerRead.Failed(status)), pins, setOf("acme", "globex"))
            assertEquals(listOf("@globex/wiki"), kept.map { it.id })
        }
        for (status in listOf(null, 500, 503)) {
            val kept = PinnedAppRecord.reconcile(old, mapOf("acme" to PeerRead.Failed(status)), pins, setOf("acme", "globex"))
            assertEquals(pins, kept.map { it.id })
        }
    }

    @Test
    fun `an unpinned app or a workspace no longer live is dropped`() {
        val old = records(app("acme", "board"), app("globex", "wiki"))
        assertEquals(listOf("@globex/wiki"), PinnedAppRecord.reconcile(old, emptyMap(), listOf("@globex/wiki"), setOf("acme", "globex")).map { it.id })
        assertEquals(listOf("@acme/board"), PinnedAppRecord.reconcile(old, emptyMap(), listOf("@acme/board", "@globex/wiki"), setOf("acme")).map { it.id })
    }

    @Test
    fun `a pin follows the list and an unpin lets go`() {
        val old = records(app("acme", "board"))
        val kept = PinnedAppRecord.following(listOf("@acme/board", "@acme/wiki"), old, listOf(app("acme", "wiki", "Wiki")))
        assertEquals(listOf("@acme/board", "@acme/wiki"), kept.map { it.id })
        assertEquals(listOf("@acme/wiki"), PinnedAppRecord.following(listOf("@acme/wiki"), kept, emptyList()).map { it.id })
    }

    @Test
    fun `only a refusal of access is lost access`() {
        assertTrue(listOf(401, 403, 404).all { AppsReadException(it).lostAccess })
        assertFalse(listOf(null, 408, 429, 500, 502).any { AppsReadException(it).lostAccess })
        assertEquals(PeerRead.Failed(null), PeerRead.failed(IOException("down")))
        assertEquals(PeerRead.Failed(403), PeerRead.failed(AppsReadException(403)))
    }

    @Test
    fun `a read keeps the records, a refusal drops them, a timeout keeps them, and disconnecting lets them go`() = kotlinx.coroutines.runBlocking {
        val acme = HivePeer("acme", "https://acme.example.com", "t", null)
        val globex = HivePeer("globex", "https://globex.example.com", "t", null)
        var saved: List<PinnedAppRecord>? = null
        Stadeum.saveRecords = { saved = it }
        Stadeum.pinnedIds = { listOf("@acme/board", "@globex/wiki") }
        try {
            Stadeum.pinnedRecords = records(app("acme", "board", "Old name"))
            Stadeum.readApps(listOf(acme, globex)) { listOf(app(it.id, if (it.id == "acme") "board" else "wiki", "New")) }
            assertEquals(listOf("@acme/board", "@globex/wiki"), saved?.map { it.id })
            assertEquals("New", saved?.first()?.title)
            // A timeout: kept, as listed before.
            Stadeum.readApps(listOf(acme, globex)) { if (it.id == "globex") throw IOException("timed out") else listOf(app("acme", "board", "Newer")) }
            assertEquals(listOf("@acme/board", "@globex/wiki"), saved?.map { it.id })
            assertEquals(setOf("@acme/board", "@globex/wiki"), Stadeum.apps.map { it.id }.toSet())
            // Access lost: its apps and records go.
            Stadeum.readApps(listOf(acme, globex)) { if (it.id == "globex") throw AppsReadException(403) else listOf(app("acme", "board", "Newer")) }
            assertEquals(listOf("@acme/board"), Stadeum.apps.map { it.id })
            assertEquals(listOf("@acme/board"), saved?.map { it.id })
            Stadeum.model.disconnect()
            assertTrue(saved?.isEmpty() == true && Stadeum.apps.isEmpty() && Stadeum.pinnedRecords.isEmpty())
        } finally {
            Stadeum.saveRecords = null
            Stadeum.pinnedIds = { emptyList() }
            Stadeum.pinnedRecords = emptyList()
        }
    }

    @Test
    fun `kept records are listed for the workspaces in use until they answer`() {
        Stadeum.seed(records(app("acme", "board")))
        // Not in use here (no pairing in a test): nothing is listed for it.
        assertTrue(Stadeum.apps.none { it.refreshing })
        Stadeum.pinnedRecords = emptyList()
    }
}
