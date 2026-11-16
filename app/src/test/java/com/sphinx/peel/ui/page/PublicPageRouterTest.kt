package com.sphinx.peel.ui.page

import com.sphinx.peel.engine.PublicAppLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PublicPageRouterTest {
    private val a = PublicAppLink.of("https://a.example.com")!!
    private val b = PublicAppLink.of("https://b.example.com")!!

    @Test fun opensAtOnceWhenReady() {
        val router = PublicPageRouter()
        router.open(a, ready = true)
        assertEquals(a, router.page)
        assertNull(router.waitingLink)
    }

    @Test fun waitsWhileLockedAndOpensAtTheUnlock() {
        val router = PublicPageRouter()
        router.open(a, ready = false)
        assertNull(router.page)
        assertEquals(a, router.waitingLink)
        router.openWaitingLink()
        assertEquals(a, router.page)
        assertNull(router.waitingLink)
    }

    @Test fun aLockClosesThePageButKeepsTheWaitingLink() {
        val router = PublicPageRouter()
        router.open(a, ready = true)
        router.open(b, ready = false)
        router.locked()
        assertNull(router.page)
        assertEquals(b, router.waitingLink)
        router.openWaitingLink()
        assertEquals(b, router.page)
    }

    @Test fun resetDropsBoth() {
        val router = PublicPageRouter()
        router.open(a, ready = true)
        router.open(b, ready = false)
        router.reset()
        assertNull(router.page)
        assertNull(router.waitingLink)
    }

    // iOS `PhoneRouter`: one holder of both pages.
    private val acme = com.sphinx.peel.engine.HivePeer("acme", "https://acme.sphinx.chat:3355/lab", "t", "Acme")
    private val board = com.sphinx.peel.engine.OrgPageRequest("https://acme.sphinx.chat:3355/lab/?app=board&solo=1", "tok", "acme", "Acme", "Board")

    @Test fun aPeelLinkReplacesAnOrgPage() {
        val router = PublicPageRouter()
        router.openOrg(board)
        router.open(a, ready = true)
        assertNull(router.orgPage)
        assertEquals(a, router.page)
    }

    @Test fun aWaitingLinkReplacesAnOrgPageWhenItOpens() {
        val router = PublicPageRouter()
        router.open(a, ready = false)
        router.openOrg(board)
        router.openWaitingLink()
        assertNull(router.orgPage)
        assertEquals(a, router.page)
    }

    @Test fun aLinkThatWaitsTouchesNoPage() {
        val router = PublicPageRouter()
        router.openOrg(board)
        router.open(a, ready = false)
        assertEquals(board, router.orgPage)
        assertNull(router.page)
    }

    @Test fun anOrgPageOpeningLeavesAPublicPageAlone() {
        // Nothing in iOS clears `publicPage` when `orgPage` is set.
        val router = PublicPageRouter()
        router.open(a, ready = true)
        router.openOrg(board)
        assertEquals(a, router.page)
        assertEquals(board, router.orgPage)
    }

    @Test fun aLockClosesBothPagesAndKeepsTheWaitingLink() {
        val router = PublicPageRouter()
        router.open(a, ready = true)
        router.openOrg(board)
        router.open(b, ready = false)
        router.locked()
        assertNull(router.page)
        assertNull(router.orgPage)
        assertEquals(b, router.waitingLink)
    }

    @Test fun resetDropsEverything() {
        val router = PublicPageRouter()
        router.openOrg(board)
        router.open(a, ready = true)
        router.open(b, ready = false)
        router.reset()
        assertNull(router.orgPage)
        assertNull(router.page)
        assertNull(router.waitingLink)
    }

    @Test fun anOrgPageGoesWhenItsWorkspaceIsNoLongerInUse() {
        val router = PublicPageRouter()
        router.openOrg(board)
        router.workspacesChanged(listOf(acme))
        assertEquals(board, router.orgPage)
        router.workspacesChanged(listOf(acme.copy(id = "other")))
        assertNull(router.orgPage)
    }
}
