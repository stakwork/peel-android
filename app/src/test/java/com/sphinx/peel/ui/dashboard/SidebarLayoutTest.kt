package com.sphinx.peel.ui.dashboard

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
        assertEquals(SidebarLayout(true, false), SidebarLayout.decode(saved))
        val out = SidebarLayout.encode(SidebarLayout(false, true), saved)
        assertEquals(SidebarLayout(false, true), SidebarLayout.decode(out))
        assertTrue(out.contains("\"tribesOpen\":false") && out.contains("pinnedApps"))
    }
}
