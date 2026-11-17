package com.sphinx.peel.engine

import org.json.JSONObject

/// A chat's last message as one line for a dashboard row — PeelCore's
/// `ConversationPreview.swift` (`Conversation.previewLine`): its text
/// without markup, or for one with no words what it is ("Photo", "Video",
/// "Voice message", "File", "GIF", "Payment", "Payment request", "Call",
/// "Message deleted"); "You: " first for your own, and in a tribe the
/// sender's nickname ("Paul: ") for anyone else's. Null for a chat with no
/// message. The row truncates it to one line. A boost reads as a sentence
/// of its own ("Paul boosted your message: 5 sats", "You boosted a message:
/// 5 sats"), with no "You: "/nickname prefix in front of it.
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
        msat: Long? = null,
    ): String? {
        if (type == null) return null
        if (type == BOOST_MESSAGE_TYPE && !deleted) return boostLine(fromMe == true, msat, senderJson)
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

    /// Someone else's boost reaches a row only when it is on our message
    /// (`Storage.conversations()`), so it reads "boosted your message".
    private fun boostLine(fromMe: Boolean, msat: Long?, senderJson: String?): String {
        val amount = if (msat != null && msat > 0) ": ${Sats.format(msat)}" else ""
        if (fromMe) return "You boosted a message$amount"
        val name = senderJson?.let { runCatching { JSONObject(it).optString("alias") }.getOrNull() }?.trim()
        return "${name?.takeIf { it.isNotEmpty() } ?: "Someone"} boosted your message$amount"
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
