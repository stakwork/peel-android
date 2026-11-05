package com.sphinx.peel.engine

import org.json.JSONObject

/// A chat's last message as one line for a dashboard row — PeelCore's
/// `ConversationPreview.swift` (`Conversation.previewLine`): its text
/// without markup, or for one with no words what it is ("Photo", "Video",
/// "Voice message", "File", "GIF", "Payment", "Payment request", "Call",
/// "Message deleted"); "You: " first for your own, and in a tribe the
/// sender's nickname ("Paul: ") for anyone else's. Null for a chat with no
/// message. The row truncates it to one line.
///
/// Not ported: `Giphy`'s host allowlist (only the caption is read), since
/// nothing is fetched here.
object ConversationPreview {
    /// `body` is the stored message body (JSON with `content`, and for an
    /// attachment `mediaType`); `senderJson` the stored `sender` JSON.
    fun line(
        type: Int?,
        body: String?,
        deleted: Boolean,
        fromMe: Boolean?,
        isTribe: Boolean,
        senderJson: String?,
    ): String? {
        if (type == null) return null
        val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val content = json?.optString("content")?.takeIf { it.isNotEmpty() }
        val mediaType = json?.optString("mediaType")?.takeIf { it.isNotEmpty() }
        val text = text(type, content, mediaType, deleted)
        if (text.isEmpty()) return null
        if (fromMe == true) return "You: $text"
        // In a tribe, who said it; a contact's row is theirs alone.
        if (isTribe) {
            val name = senderJson?.let { runCatching { JSONObject(it).optString("alias") }.getOrNull() }?.trim()
            if (!name.isNullOrEmpty()) return "$name: $text"
        }
        return text
    }

    private fun text(type: Int, content: String?, mediaType: String?, deleted: Boolean): String {
        if (deleted) return "Message deleted"
        if (type == 0) giphyCaption(content)?.let { return if (it.isEmpty()) "GIF" else "GIF: $it" }
        when (type) {
            DIRECT_PAYMENT_MESSAGE_TYPE, BOOST_MESSAGE_TYPE -> return "Payment"
            INVOICE_MESSAGE_TYPE -> return "Payment request"
            CALL_MESSAGE_TYPE -> return "Call"
        }
        content?.let { MessageMarkup.plainText(it).trim() }?.takeIf { it.isNotEmpty() }?.let { return it.replace(Regex("\\s*\\R\\s*"), " ") }
        if (type != ATTACHMENT_MESSAGE_TYPE) return ""
        return when (attachmentKind(mediaType)) {
            AttachmentKind.IMAGE -> "Photo"
            AttachmentKind.VIDEO -> "Video"
            AttachmentKind.AUDIO -> "Voice message"
            AttachmentKind.PDF, AttachmentKind.FILE -> "File"
        }
    }

    /// The caption of a `giphy::` message (base64 of JSON with `text`),
    /// "" when it has none, null when `content` is not one.
    private fun giphyCaption(content: String?): String? {
        if (content == null || !content.startsWith("giphy::")) return null
        val decoded = runCatching { String(java.util.Base64.getDecoder().decode(content.removePrefix("giphy::")), Charsets.UTF_8) }.getOrNull()
            ?: return null
        val fields = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        return MessageMarkup.plainText(fields.optString("text")).trim()
    }
}
