package com.sphinx.peel.ui.call

import com.sphinx.peel.engine.CallParticipant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallSessionTest {
    @Test
    fun `formatElapsed shows minutes and seconds under an hour`() {
        assertEquals("0:00", CallSession.formatElapsed(0))
        assertEquals("0:05", CallSession.formatElapsed(5))
        assertEquals("3:18", CallSession.formatElapsed(198))
        assertEquals("59:59", CallSession.formatElapsed(3599))
    }

    @Test
    fun `formatElapsed switches to hours past sixty minutes`() {
        assertEquals("1:00:00", CallSession.formatElapsed(3600))
        assertEquals("1:02:07", CallSession.formatElapsed(3727))
    }

    @Test
    fun `earliestStart picks the earliest join time`() {
        assertEquals(100L, CallSession.earliestStart(null, listOf(300L, 100L, 200L), 999L))
    }

    @Test
    fun `earliestStart only moves earlier`() {
        assertEquals(100L, CallSession.earliestStart(100L, listOf(300L), 999L))
        assertEquals(50L, CallSession.earliestStart(100L, listOf(50L, 300L), 999L))
    }

    @Test
    fun `earliestStart ignores null join times`() {
        assertEquals(200L, CallSession.earliestStart(null, listOf(null, 200L, null), 999L))
    }

    private fun tile(identity: String, screen: Boolean = false) =
        CallTile(identity, CallParticipant(identity, identity, null), isYou = false, speaking = false, muted = false, video = null, isScreen = screen)

    @Test
    fun `the big tile is the one pinned, else the first screen not sent back to the grid`() {
        val ada = tile("ada")
        val jamieScreen = tile("jamie", screen = true)
        val bobScreen = tile("bob", screen = true)
        val tiles = listOf(ada, tile("jamie"), jamieScreen, tile("bob"), bobScreen)
        // A screen shared takes the big spot by itself.
        assertEquals(jamieScreen, CallSession.focus(tiles, pinned = null, grid = emptySet()))
        assertEquals(ada, CallSession.focus(tiles, pinned = ada.id, grid = emptySet()))
        // Sent back to the grid: the next screen, then none (the grid).
        assertEquals(bobScreen, CallSession.focus(tiles, pinned = null, grid = setOf(jamieScreen.id)))
        assertNull(CallSession.focus(tiles, pinned = null, grid = setOf(jamieScreen.id, bobScreen.id)))
        // Pinned again from the grid: big again.
        assertEquals(jamieScreen, CallSession.focus(tiles, pinned = jamieScreen.id, grid = setOf(jamieScreen.id, bobScreen.id)))
        assertNull(CallSession.focus(listOf(ada), pinned = null, grid = emptySet()))
    }

    @Test
    fun `earliestStart falls back to the connect time only with nothing else`() {
        assertEquals(999L, CallSession.earliestStart(null, listOf(null, null), 999L))
        assertEquals(999L, CallSession.earliestStart(null, emptyList(), 999L))
        assertEquals(500L, CallSession.earliestStart(500L, listOf(null), 999L))
    }
}
