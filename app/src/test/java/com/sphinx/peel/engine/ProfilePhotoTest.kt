package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePhotoTest {
    @Test
    fun `a link is trimmed, https only, empty means none`() {
        assertEquals("https://m/public/x", ProfilePhoto.clean("  https://m/public/x \n").getOrThrow())
        assertEquals("", ProfilePhoto.clean("   ").getOrThrow())
        assertTrue(ProfilePhoto.clean("http://m/x").isFailure)
        assertTrue(ProfilePhoto.clean("javascript:1").isFailure)
        assertTrue(ProfilePhoto.clean("http://m/x").exceptionOrNull()!!.message!!.contains("https"))
    }

    @Test
    fun `the long side is capped, the aspect kept, nothing enlarged`() {
        assertEquals(1024 to 512, ProfilePhoto.fit(4096, 2048))
        assertEquals(512 to 1024, ProfilePhoto.fit(2048, 4096))
        assertEquals(800 to 600, ProfilePhoto.fit(800, 600))
        assertEquals(1024 to 1024, ProfilePhoto.fit(1024, 1024))
        assertEquals(1024 to 1, ProfilePhoto.fit(100_000, 10))
    }

    @Test
    fun `a file over the limit is refused before it is read`() {
        assertNull(ProfilePhoto.refusal(null))
        assertNull(ProfilePhoto.refusal(ProfilePhoto.MAX_FILE_BYTES))
        assertTrue(ProfilePhoto.refusal(ProfilePhoto.MAX_FILE_BYTES + 1)!!.contains("too large"))
    }

    @Test
    fun `a failure has words for the person`() {
        assertEquals("Could not change your photo: nope", ProfilePhoto.message(IllegalStateException("nope")))
        assertEquals("Could not change your photo: try again", ProfilePhoto.message(IllegalStateException()))
    }
}
