package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.engine.WorkspaceApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SidebarLayoutTest {
    @Test
    fun `defaults to separated`() {
        assertEquals(SidebarLayout(false, false), SidebarLayout.decode(null))
        assertEquals(SidebarLayout(false, false), SidebarLayout.decode("not json"))
    }

    @Test
    fun `reads and writes PeelCore's field names, keeping the others`() {
        val saved = """{"tribesOpen":false,"pinnedApps":["a"],"combinedView":true}"""
        assertEquals(SidebarLayout(true, false, listOf("a")), SidebarLayout.decode(saved))
        val out = SidebarLayout.encode(SidebarLayout(false, true), saved)
        assertEquals(SidebarLayout(false, true), SidebarLayout.decode(out))
        assertTrue(out.contains("\"tribesOpen\":false") && out.contains("pinnedApps"))
    }

    @Test
    fun `a layout the Swift apps saved round-trips with pinned apps and the fold`() {
        val saved = """{"tribesOpen":false,"pinnedApps":["@acme/board","mine"],"appsOpen":false,"combinedView":true,"allRecent":true}"""
        val layout = SidebarLayout.decode(saved)
        assertEquals(SidebarLayout(true, true, listOf("@acme/board", "mine"), false), layout)
        val out = SidebarLayout.encode(layout, saved)
        assertEquals(layout, SidebarLayout.decode(out))
        // org.json may escape the "/" in a key (`\/`): still the same JSON to the Swift apps.
        val json = org.json.JSONObject(out)
        assertEquals(false, json.getBoolean("tribesOpen"))
        assertEquals("@acme/board", json.getJSONArray("pinnedApps").getString(0))
        assertEquals(false, json.getBoolean("appsOpen"))
    }

    @Test
    fun `missing or garbage app fields keep their defaults`() {
        assertEquals(SidebarLayout(), SidebarLayout.decode("""{"combinedView":false}"""))
        val bad = SidebarLayout.decode("""{"pinnedApps":"x","appsOpen":"no"}""")
        assertEquals(emptyList<String>(), bad.pinnedApps)
        assertTrue(bad.appsOpen)
        assertEquals(listOf("a"), SidebarLayout.decode("""{"pinnedApps":["a",1,"a",null]}""").pinnedApps)
    }

    @Test
    fun `pins keep their order, do not duplicate, and unpin`() {
        var layout = SidebarLayout()
        layout = layout.withPinned("@a/one", true).withPinned("@b/two", true).withPinned("@a/one", true)
        assertEquals(listOf("@b/two", "@a/one"), layout.pinnedApps)
        assertTrue(layout.isPinned("@a/one"))
        layout = layout.withPinned("@a/one", false).withPinned("@zzz/none", false)
        assertEquals(listOf("@b/two"), layout.pinnedApps)
        assertTrue(!layout.isPinned("@a/one"))
    }

    @Test
    fun `the first pin reopens a closed fold and a later one leaves it`() {
        val first = SidebarLayout(appsOpen = false).withPinned("@a/one", true)
        assertTrue(first.appsOpen)
        val later = first.copy(appsOpen = false).withPinned("@b/two", true)
        assertTrue(!later.appsOpen)
    }

    @Test
    fun `pinned keeps the listed apps in their order and other fields stay`() {
        val apps = listOf(WorkspaceApp("a", "x", "X"), WorkspaceApp("a", "y", "Y"), WorkspaceApp(null, "z", "Z"))
        val layout = SidebarLayout(combinedView = true, pinnedApps = listOf("z", "@a/x"))
        assertEquals(listOf("@a/x", "z"), layout.pinned(apps).map { it.id })
        assertTrue(layout.withPinned("@a/y", true).combinedView)
    }
}
