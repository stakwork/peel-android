package com.sphinx.peel.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `org.json` is Android's: Robolectric supplies it, as in `ControlEnvelopeTest`.
@RunWith(RobolectricTestRunner::class)
class AttachmentMetadataTest {
    @Test fun `vision alone is exactly peel v1 vision`() {
        assertEquals("""{"peel":{"v":1,"vision":true}}""", AttachmentMetadata.VISION)
        val merged = JSONObject(AttachmentMetadata.merged(null, true)!!)
        assertEquals(1, merged.getJSONObject("peel").getInt("v"))
        assertTrue(merged.getJSONObject("peel").getBoolean("vision"))
        assertEquals(1, merged.length())
    }

    @Test fun `vision merges with the time zone and both keys survive`() {
        val merged = JSONObject(AttachmentMetadata.merged("""{"tz":"GMT+1"}""", true)!!)
        assertEquals("GMT+1", merged.getString("tz"))
        assertTrue(merged.getJSONObject("peel").getBoolean("vision"))
    }

    @Test fun `off means no vision key and the time zone is untouched`() {
        assertEquals("""{"tz":"GMT+1"}""", AttachmentMetadata.merged("""{"tz":"GMT+1"}""", false))
        assertNull(AttachmentMetadata.merged(null, false))
    }

    @Test fun `an unreadable time zone is replaced rather than carried`() {
        assertEquals(JSONObject(AttachmentMetadata.VISION).toString(), AttachmentMetadata.merged("not json", true))
    }

    @Test fun `metadata over 64 KB is dropped`() {
        val big = JSONObject().put("tz", "x".repeat(MessageMetadata.MAX_LENGTH)).toString()
        assertNull(AttachmentMetadata.merged(big, true))
    }

    @Test fun `vision only for a picture, in the agent's DM, when allowed`() {
        assertTrue(AttachmentMetadata.vision(true, "image/jpg", true))
        assertTrue(AttachmentMetadata.vision(true, "image/png", true))
        assertFalse(AttachmentMetadata.vision(false, "image/jpg", true))
        assertFalse(AttachmentMetadata.vision(true, "image/jpg", false))
        assertFalse(AttachmentMetadata.vision(true, "application/pdf", true))
        assertFalse(AttachmentMetadata.vision(true, "audio/wav", true))
    }
}
