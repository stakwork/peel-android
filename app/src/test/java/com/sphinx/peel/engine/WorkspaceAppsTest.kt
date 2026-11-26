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
