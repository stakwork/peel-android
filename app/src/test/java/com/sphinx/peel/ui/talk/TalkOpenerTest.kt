package com.sphinx.peel.ui.talk

import com.sphinx.peel.engine.HivePeer
import com.sphinx.peel.engine.OrgPageRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// iOS `TalkOpener` / `TalkView`'s sheet rules.
@RunWith(RobolectricTestRunner::class)
class TalkOpenerTest {
    private val acme = HivePeer("acme", "https://acme.sphinx.chat:3355/lab", "t", "Acme")
    private val board = OrgPageRequest("https://acme.sphinx.chat:3355/lab/?app=board&solo=1", "tok", "acme", "Acme", "Board")

    @Test fun `a card's page shows until closed`() {
        val opener = TalkOpener()
        opener.showPage(board)
        assertEquals(board, opener.page)
        opener.workspacesChanged(listOf(acme))
        assertEquals(board, opener.page)
        opener.close()
        assertNull(opener.page)
    }

    @Test fun `the page goes when its workspace is no longer in use`() {
        val opener = TalkOpener()
        opener.showPage(board)
        opener.workspacesChanged(emptyList())
        assertNull(opener.page)
    }

    @Test fun `a link keeps nothing and goes as it is`() {
        val opener = TalkOpener()
        assertEquals("https://example.com/x?a=1", opener.link("https://example.com/x?a=1"))
        assertNull(opener.page)
    }
}
