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

    /// Each page that comes on screen is told to [PublicPageRouter]'s `opened` (kept for the Apps list,
    /// docs/phases/37-worker-onboarding.md); a link still waiting for the unlock is not, until it opens.
    @Test fun eachPageOpenedIsTold() {
        val seen = mutableListOf<PublicAppLink>()
        val router = PublicPageRouter(opened = { seen += it })
        router.open(a, ready = false)
        assertEquals(emptyList<PublicAppLink>(), seen)
        router.openWaitingLink()
        router.open(b, ready = true)
        router.openWaitingLink()
        assertEquals(listOf(a, b), seen)
    }
}
