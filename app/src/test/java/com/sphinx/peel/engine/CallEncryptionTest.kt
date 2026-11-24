package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// An encrypted group call as the call screen says it ([CallEncryption]) and Jamie's recording on the canvas ([CanvasRecording];
/// docs/phases/56-box-group-calls.md).
class CallEncryptionTest {
    @Test fun `nothing is said while every track encrypts and decrypts`() {
        assertNull(CallEncryption().notice)
        assertNull(CallEncryption().track("TR_1", local = false, failed = false).notice)
    }

    @Test fun `someone unheard is said before the person unheard, and a track that recovers is no longer said`() {
        val mine = CallEncryption().track("TR_mine", local = true, failed = true)
        assertEquals(CallEncryption.YOU_UNHEARD, mine.notice)
        val both = mine.track("TR_theirs", local = false, failed = true)
        assertEquals(CallEncryption.SOMEONE_UNHEARD, both.notice)
        assertEquals(CallEncryption.YOU_UNHEARD, both.track("TR_theirs", local = false, failed = false).notice)
        assertNull(both.track("TR_theirs", local = false, failed = false).track("TR_mine", local = true, failed = false).notice)
    }

    @Test fun `only Jamie saying so is the call recorded on the canvas`() {
        assertTrue(CanvasRecording.isOn("Jamie", mapOf("recording" to "true")))
        assertTrue(CanvasRecording.isOn(" jamie ", mapOf("recording" to "true")))
        // Stopped: the voice service clears it.
        assertFalse(CanvasRecording.isOn("Jamie", mapOf("recording" to "")))
        assertFalse(CanvasRecording.isOn("Jamie", emptyMap()))
        assertFalse(CanvasRecording.isOn("Ada", mapOf("recording" to "true")))
        assertFalse(CanvasRecording.isOn(null, mapOf("recording" to "true")))
    }
}
