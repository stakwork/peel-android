package com.sphinx.peel.engine

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class StagedAttachmentsTest {
    @Test fun sendNeedsTextOrAStagedFile() {
        assertFalse(StagedAttachments.canSend("", 0))
        assertFalse(StagedAttachments.canSend("   ", 0))
        assertTrue(StagedAttachments.canSend("hi", 0))
        assertTrue(StagedAttachments.canSend("", 1))
    }

    @Test fun captionGoesWithTheFirstOnly() {
        assertEquals("hello", StagedAttachments.captionFor(0, "hello"))
        assertEquals("", StagedAttachments.captionFor(1, "hello"))
        assertEquals("", StagedAttachments.captionFor(2, "hello"))
    }

    @Test fun roomIsWhatIsLeft() {
        assertEquals(StagedAttachments.MAX_PICTURES, StagedAttachments.room(0))
        assertEquals(7, StagedAttachments.room(3))
        assertEquals(0, StagedAttachments.room(StagedAttachments.MAX_PICTURES + 3))
    }

    @Test fun tooManyNamesTheCap() {
        assertEquals("You can attach up to 10 photos at a time.", StagedAttachments.tooMany())
    }

    @Test fun otherChatsKeepTheEngineLimit() {
        assertTrue(StagedAttachments.fits(50_000_000L, agent = false))
        assertTrue(StagedAttachments.fits(100_000_000L, agent = false))
        assertFalse(StagedAttachments.fits(100_000_001L, agent = false))
        assertTrue(StagedAttachments.fits(32_000_000L, agent = true))
        assertFalse(StagedAttachments.fits(32_000_001L, agent = true))
    }
}
