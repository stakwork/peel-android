package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentTest {
    @Test
    fun `a video is always relabeled video dot mov, the cross-client convention`() {
        for (name in listOf("clip.mp4", "clip.MOV", "clip.m4v", "clip.3gp")) {
            val (fileName, mediaType) = classifyOutgoingFile(name, "video/whatever")
            assertEquals("video.mov", fileName)
            assertEquals("video/mov", mediaType)
        }
    }

    @Test
    fun `anything else keeps its own name and the declared media type`() {
        val (fileName, mediaType) = classifyOutgoingFile("report.pdf", "application/pdf")
        assertEquals("report.pdf", fileName)
        assertEquals("application/pdf", mediaType)
    }

    @Test
    fun `a missing declared type is guessed from the extension`() {
        val (fileName, mediaType) = classifyOutgoingFile("notes.txt", null)
        assertEquals("notes.txt", fileName)
        assertEquals("text/plain", mediaType)
    }

    @Test
    fun `an unrecognised extension with no declared type falls back to a generic file`() {
        val (fileName, mediaType) = classifyOutgoingFile("mystery.xyz123", null)
        assertEquals("mystery.xyz123", fileName)
        assertEquals("application/octet-stream", mediaType)
    }

    @Test
    fun `a blank declared type is treated the same as none`() {
        val (_, mediaType) = classifyOutgoingFile("notes.txt", "")
        assertEquals("text/plain", mediaType)
    }
}
