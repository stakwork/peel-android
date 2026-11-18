package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSightTest {
    @Test fun `only pictures are offered the switch`() {
        val sight = ModelSight()
        assertTrue(sight.offers("image/jpg", "image.jpg"))
        assertTrue(sight.offers("image/heic", "x"))
        assertTrue(sight.offers(null, "photo.PNG"))
        assertTrue(sight.offers("application/octet-stream", "photo.webp"))
        assertFalse(sight.offers("application/pdf", "a.pdf"))
        assertFalse(sight.offers("audio/wav", "audio.wav"))
        assertFalse(sight.offers(null, "notes.txt"))
    }

    @Test fun `a picture is on until turned off`() {
        val sight = ModelSight()
        assertTrue(sight.allows("a"))
        sight.set("a", false)
        assertFalse(sight.allows("a"))
        assertTrue(sight.allows("b"))
        sight.set("a", true)
        assertTrue(sight.allows("a"))
    }

    @Test fun `take reads and forgets, restore puts a refused file back, drop forgets`() {
        val sight = ModelSight()
        sight.set("a", false)
        assertFalse(sight.take("a"))
        assertTrue(sight.allows("a"))
        sight.restore("a", false)
        assertFalse(sight.allows("a"))
        sight.drop("a")
        assertTrue(sight.allows("a"))
        assertEquals(emptySet<String>(), sight.turnedOff)
        assertTrue(sight.take("never mentioned"))
    }

    @Test fun `the switch's words are exact`() {
        assertEquals("Let the model see this picture", ModelSight.TITLE)
        assertEquals("Off: only the text read from it goes to the model. On: the picture is sent to your model provider.", ModelSight.HELP)
    }
}
