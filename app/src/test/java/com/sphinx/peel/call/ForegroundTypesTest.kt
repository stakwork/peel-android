package com.sphinx.peel.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTypesTest {
    @Test
    fun `an audio-only call is the microphone type alone`() {
        assertEquals(ForegroundTypes.MICROPHONE, ForegroundTypes.select(micGranted = true, wantsCamera = false, cameraGranted = false))
        assertEquals(ForegroundTypes.MICROPHONE, ForegroundTypes.select(micGranted = true, wantsCamera = false, cameraGranted = true))
    }

    @Test
    fun `a video call or the camera on adds the camera type`() {
        assertEquals(
            ForegroundTypes.MICROPHONE or ForegroundTypes.CAMERA,
            ForegroundTypes.select(micGranted = true, wantsCamera = true, cameraGranted = true),
        )
    }

    @Test
    fun `video without the camera permission stays microphone only`() {
        assertEquals(ForegroundTypes.MICROPHONE, ForegroundTypes.select(micGranted = true, wantsCamera = true, cameraGranted = false))
    }

    @Test
    fun `without the microphone there is no type to start with`() {
        assertEquals(ForegroundTypes.NONE, ForegroundTypes.select(micGranted = false, wantsCamera = false, cameraGranted = false))
        assertEquals(ForegroundTypes.NONE, ForegroundTypes.select(micGranted = false, wantsCamera = true, cameraGranted = true))
    }

    @Test
    fun `the two types are distinct bits`() {
        assertEquals(0, ForegroundTypes.MICROPHONE and ForegroundTypes.CAMERA)
    }

    @Test
    fun `only gaining the camera type counts as adding it`() {
        val both = ForegroundTypes.MICROPHONE or ForegroundTypes.CAMERA
        assertTrue(ForegroundTypes.addsCamera(ForegroundTypes.MICROPHONE, both))
        assertFalse(ForegroundTypes.addsCamera(both, both))
        assertFalse(ForegroundTypes.addsCamera(both, ForegroundTypes.MICROPHONE))
        assertFalse(ForegroundTypes.addsCamera(ForegroundTypes.MICROPHONE, ForegroundTypes.MICROPHONE))
    }
}
