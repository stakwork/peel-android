package com.sphinx.peel.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `ConversationPreview.line`: PeelCore's `ConversationPreviewTests`.
@RunWith(RobolectricTestRunner::class)
class ConversationPreviewTest {
    private fun body(content: String? = null, mediaType: String? = null) =
        JSONObject().apply { content?.let { put("content", it) }; mediaType?.let { put("mediaType", it) } }.toString()

    private fun line(
        type: Int, content: String? = null, media: String? = null, me: Boolean = false,
        deleted: Boolean = false, tribe: Boolean = false, from: String? = null,
    ) = ConversationPreview.line(
        type, body(content, media), deleted, me, tribe, from?.let { JSONObject().put("alias", it).toString() },
    )

    @Test
    fun `text and own prefix`() {
        assertEquals("hello you", line(0, "hello **you**"))
        assertEquals("You: hi", line(0, "hi", me = true))
    }

    @Test
    fun `wordless messages say what they are`() {
        assertEquals("Photo", line(ATTACHMENT_MESSAGE_TYPE, media = "image/png"))
        assertEquals("Video", line(ATTACHMENT_MESSAGE_TYPE, media = "video/mp4"))
        assertEquals("Voice message", line(ATTACHMENT_MESSAGE_TYPE, media = "audio/wav"))
        assertEquals("File", line(ATTACHMENT_MESSAGE_TYPE, media = "application/zip"))
        assertEquals("Payment", line(DIRECT_PAYMENT_MESSAGE_TYPE))
        assertEquals("Payment request", line(INVOICE_MESSAGE_TYPE))
        assertEquals("Call", line(CALL_MESSAGE_TYPE))
        assertEquals("Message deleted", line(0, deleted = true))
        assertEquals("You: Photo", line(ATTACHMENT_MESSAGE_TYPE, media = "image/png", me = true))
    }

    @Test
    fun `a caption wins over the kind, and a gif is named`() {
        assertEquals("look", line(ATTACHMENT_MESSAGE_TYPE, "look", media = "image/png"))
        val gif = "giphy::" + java.util.Base64.getEncoder().encodeToString("""{"text":"wow"}""".toByteArray())
        assertEquals("GIF: wow", line(0, gif))
        val bare = "giphy::" + java.util.Base64.getEncoder().encodeToString("""{"id":"1"}""".toByteArray())
        assertEquals("GIF", line(0, bare))
    }

    @Test
    fun `a tribe names the sender and a contact does not`() {
        assertEquals("Paul: this is a test", line(0, "this is a test", tribe = true, from = "Paul"))
        assertEquals("Paul: Photo", line(ATTACHMENT_MESSAGE_TYPE, media = "image/png", tribe = true, from = "Paul"))
        assertEquals("You: hi", line(0, "hi", me = true, tribe = true, from = "Paul"))
        assertEquals("hi", line(0, "hi", tribe = true, from = null))
        assertEquals("hi", line(0, "hi", tribe = true, from = "  "))
        assertEquals("hi", line(0, "hi", tribe = false, from = "Paul"))
    }

    @Test
    fun `no message and wordless text give no line`() {
        assertNull(ConversationPreview.line(null, null, false, null, true, null))
        assertNull(line(0, ""))
    }
}
