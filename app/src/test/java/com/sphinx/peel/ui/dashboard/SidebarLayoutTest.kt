package com.sphinx.peel.ui.dashboard

import com.sphinx.peel.engine.WorkspaceApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// The saved layout (docs/phases/58-chats-one-list.md; PeelCore's `pagesPinInOrder`, `openedAppsAreRecentNewestFirst`). Under
/// Robolectric for `org.json`.
@RunWith(RobolectricTestRunner::class)
class SidebarLayoutTest {
    @Test
    fun `nothing saved, or garbage, is the defaults`() {
        assertEquals(SidebarLayout(), SidebarLayout.decode(null))
        assertEquals(SidebarLayout(), SidebarLayout.decode("not json"))
        val bad = SidebarLayout.decode("""{"pinnedPages":"x","recentApps":["a",1,"a",null]}""")
        assertEquals(emptyList<String>(), bad.pinnedPages)
        assertEquals(listOf("a"), bad.recentApps)
    }

    @Test
    fun `a layout saved before pages and recent apps keeps their defaults and every old field`() {
        val saved = """{"tribesOpen":false,"pinnedApps":["@acme/board","mine"],"appsOpen":false,"combinedView":true,"allRecent":true}"""
        val older = SidebarLayout.decode(saved)
        assertEquals(SidebarLayout(pinnedApps = listOf("@acme/board", "mine")), older)
        val out = SidebarLayout.encode(older.withPagePinned("threads", true).withOpened("@acme/board"), saved)
        val json = org.json.JSONObject(out)
        // The Swift apps' fields, and the old pins, stay as they were.
        assertEquals(false, json.getBoolean("tribesOpen"))
        assertEquals(false, json.getBoolean("appsOpen"))
        assertEquals(true, json.getBoolean("combinedView"))
        assertEquals(true, json.getBoolean("allRecent"))
        // org.json may escape the "/" in a key (`\/`): still the same JSON to the Swift apps.
        assertEquals("@acme/board", json.getJSONArray("pinnedApps").getString(0))
        val back = SidebarLayout.decode(out)
        assertEquals(listOf("threads"), back.pinnedPages)
        assertEquals(listOf("@acme/board"), back.recentApps)
        assertEquals(listOf("@acme/board", "mine"), back.pinnedApps)
    }

    @Test
    fun `pages pin last, unpin in place, and a repeat changes nothing`() {
        var layout = SidebarLayout()
        layout = layout.withPagePinned("mentions", true).withPagePinned("threads", true)
        assertSame(layout, layout.withPagePinned("threads", true))
        // A repeat of one pinned earlier moves it last, as PeelCore's `setPagePinned` does.
        layout = layout.withPagePinned("mentions", true)
        assertEquals(listOf("threads", "mentions"), layout.pinnedPages)
        layout = layout.withPagePinned("threads", false)
        assertEquals(listOf("mentions"), layout.pinnedPages)
        assertTrue(layout.isPagePinned("mentions") && !layout.isPagePinned("threads"))
        assertSame(layout, layout.withPagePinned("threads", false))
    }

    @Test
    fun `an opened app goes first, once, and only the newest few are kept`() {
        var layout = SidebarLayout().withOpened("@hive/one").withOpened("two").withOpened("@hive/one")
        assertEquals(listOf("@hive/one", "two"), layout.recentApps)
        assertSame(layout, layout.withOpened("@hive/one"))
        for (index in 0 until 20) layout = layout.withOpened("app-$index")
        assertEquals(SidebarLayout.RECENT_APPS_KEPT, layout.recentApps.size)
        assertEquals("app-19", layout.recentApps.first())
        // A longer list saved elsewhere is kept to the same few.
        val long = org.json.JSONObject().put("recentApps", org.json.JSONArray((1..20).map { "a$it" })).toString()
        assertEquals(SidebarLayout.RECENT_APPS_KEPT, SidebarLayout.decode(long).recentApps.size)
    }

    @Test
    fun `recent shows the listed apps opened last, newest first, at most five`() {
        val apps = (1..7).map { WorkspaceApp("acme", "a$it", "A$it") } + WorkspaceApp(null, "mine", "Mine")
        var layout = SidebarLayout()
        listOf("@acme/a1", "@gone/x", "mine", "@acme/a2").forEach { layout = layout.withOpened(it) }
        // One no longer listed is skipped.
        assertEquals(listOf("@acme/a2", "mine", "@acme/a1"), layout.recent(apps).map { it.id })
        (3..7).forEach { layout = layout.withOpened("@acme/a$it") }
        assertEquals(listOf("@acme/a7", "@acme/a6", "@acme/a5", "@acme/a4", "@acme/a3"), layout.recent(apps).map { it.id })
    }
}
