package com.sphinx.peel.engine

import android.webkit.MimeTypeMap
import org.json.JSONObject

/// A message of this type carries an attachment — PeelCore's
/// `MessageType.attachment`. Read by `Engine`'s send and download
/// paths and by `ConversationScreen`'s row rendering alike, so it lives
/// here once rather than as a constant each side keeps its own copy of.
const val ATTACHMENT_MESSAGE_TYPE = 6

/// A message of this type is a boost — PeelCore's `MessageType.boost`.
/// Not an attachment itself, but kept alongside it for the same reason:
/// `Engine.sendBoost` and `ConversationScreen`'s own boost rendering
/// both need it.
const val BOOST_MESSAGE_TYPE = 29

/// What a one-tap boost sends before anyone has ever chosen their own
/// amount, in msat — 10 sats, this port's own default (PeelCore's own
/// `defaultBoostAmountMsat` is 100 sats; the user explicitly chose a
/// different number for this port, not a porting mistake). Once someone
/// sets it from Profile, `Engine.boostAmountMsat()` reads their own
/// choice instead — see `Setting.BOOST_AMOUNT_MSAT`.
const val BOOST_AMOUNT_MSAT = 10_000L

/// What an attachment message's JSON says — PeelCore's
/// `AttachmentFields`, trimmed to what sending an image and receiving
/// anything needs: no `price`, no "sell it" fields — this port's own
/// `Engine.sendImageAttachment` never sets one, so there's nothing to
/// read back out for a price this port could ever have sent.
data class AttachmentFields(val mediaToken: String?, val mediaKey: String?, val mediaType: String?, val mediaKeyForSelf: String?) {
    /// The key this device can decrypt with: `mediaKey` (anyone's
    /// unpaid attachment, or a tribe's paid one, which keeps it), else
    /// `mediaKeyForSelf` (this account's own paid one, kept for itself
    /// and its other devices only).
    val ownKey: String? get() = mediaKey?.takeIf { it.isNotEmpty() } ?: mediaKeyForSelf?.takeIf { it.isNotEmpty() }

    companion object {
        fun decode(body: String?): AttachmentFields? {
            val json = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            return AttachmentFields(
                mediaToken = json.optString("mediaToken").takeIf { it.isNotEmpty() },
                mediaKey = json.optString("mediaKey").takeIf { it.isNotEmpty() },
                mediaType = json.optString("mediaType").takeIf { it.isNotEmpty() },
                mediaKeyForSelf = json.optString("mediaKeyForSelf").takeIf { it.isNotEmpty() },
            )
        }
    }
}

/// A media token, as the core makes it:
/// `b64url(host).muid.b64url(buyer).b64url(expiry).b64url(meta).sig` —
/// ported from PeelCore's `MediaToken`, trimmed to the two fields
/// receiving needs (`host`, to check it's this account's own memes
/// server before trusting it with anything; `muid`, the file's id
/// there). No price: this port shows a paid attachment's own row like
/// any other once it has the key, it just can't yet show what buying
/// one who doesn't would look like.
data class MediaToken(val host: String, val muid: String) {
    companion object {
        fun parse(token: String): MediaToken? {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val host = base64UrlDecode(parts[0])?.let { String(it, Charsets.UTF_8) } ?: return null
            val muid = parts[1].takeIf { it.isNotEmpty() } ?: return null
            return MediaToken(host, muid)
        }

        /// A muid safe to use as part of a URL path and a cache file
        /// name: ASCII letters, digits and `-_=`, as the memes server
        /// issues them.
        fun isSafeMuid(muid: String): Boolean =
            muid.isNotEmpty() && muid.length <= 256 && muid.all { it.isLetterOrDigit() && it.code < 128 || it in "-_=" }

        private fun base64UrlDecode(text: String): ByteArray? {
            val padded = text.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
            return runCatching { java.util.Base64.getDecoder().decode(padded) }.getOrNull()
        }
    }
}

/// What an attachment is, as the official apps tell from its
/// `mediaType` — PeelCore's `AttachmentKind`, trimmed: `text` (a paid
/// text message, `sphinx/text`) folds into `file` here, since nothing
/// in this port reads a price to make that distinction meaningful yet.
enum class AttachmentKind { IMAGE, VIDEO, AUDIO, PDF, FILE }

fun attachmentKind(mediaType: String?): AttachmentKind {
    val type = mediaType?.lowercase() ?: ""
    return when {
        type.isEmpty() || type.contains("image") || type.contains("gif") -> AttachmentKind.IMAGE
        type.contains("video") -> AttachmentKind.VIDEO
        type.contains("audio") -> AttachmentKind.AUDIO
        type.contains("pdf") -> AttachmentKind.PDF
        else -> AttachmentKind.FILE
    }
}

/// What a downloaded attachment turned out to be, decrypted and cached
/// locally — PeelCore's `DownloadedAttachment`, trimmed (no `cached`
/// flag: nothing here distinguishes a cache hit from a fresh download
/// for the caller, since both end the same way, a local file to show).
data class DownloadedAttachment(val file: java.io.File, val kind: AttachmentKind, val fileName: String?, val size: Int)

/// What a picked file is actually sent as — PeelCore's own
/// `OutgoingFile.init(name:data:)`, trimmed: no animated-GIF-vs-still
/// split (this port's image path always re-encodes to a still JPEG
/// before it ever reaches here — `ImagePreparation.kt` — so a picked
/// GIF never survives as one anyway) and no sniffing a file's bytes for
/// a WAV container (a voice recording already carries its own exact
/// name/type from `VoiceRecorder`, never routed through this). What's
/// left, and still worth porting: a video keeps the same cross-client
/// convention every Sphinx client uses for one, whatever its real
/// container — always `"video.mov"`/`"video/mov"` — so a `.mp4` from
/// Android's own camera or gallery reads as a video on an iOS or Mac
/// peer the same way a `.mov` from theirs reads here (both are ISO
/// base media underneath; nothing here or there tells `.mov` from
/// `.mp4` apart by content, only the declared type).
fun classifyOutgoingFile(fileName: String, declaredMediaType: String?): Pair<String, String> {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext in VIDEO_EXTENSIONS) return "video.mov" to "video/mov"
    val mediaType = declaredMediaType?.takeIf { it.isNotEmpty() }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: "application/octet-stream"
    return fileName to mediaType
}

private val VIDEO_EXTENSIONS = setOf("mov", "mp4", "m4v", "3gp")
