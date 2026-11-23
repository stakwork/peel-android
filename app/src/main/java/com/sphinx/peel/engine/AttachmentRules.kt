package com.sphinx.peel.engine

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/// The one decision every automatic attachment fetch goes through
/// (`MediaPrefetcher`: a row showing, a live message arriving, a retry).
/// Pure. An explicit tap never asks it: a tap always downloads, up to
/// `Engine`'s `ATTACHMENT_LIMIT`.
object AutoDownloadPolicy {
    /// The largest video or recording fetched unasked (PeelCore's `autoDownloadLimit`).
    const val VIDEO_AUDIO_LIMIT = 25_000_000

    /// The largest image fetched unasked.
    const val IMAGE_LIMIT = 10_000_000

    sealed class Decision {
        /// Fetch it now, refusing anything over `upTo` bytes (held for a tap instead).
        data class FetchNow(val upTo: Int) : Decision()

        /// The setting is off: only a copy already cached is shown.
        data object CachedOnly : Decision()

        /// The setting is on but this is not fetched unasked (a file, or a declared size over the cap):
        /// only a cached copy is shown, else a tile that waits for a tap.
        data object Hold : Decision()
    }

    /// With the setting off nothing is fetched. On: images up to [IMAGE_LIMIT], video and voice up
    /// to [VIDEO_AUDIO_LIMIT]; PDFs and other files are always held for a tap (conservative: they
    /// are the likeliest to be large or unwanted, and nothing shows them inline anyway).
    /// `declaredSize` is the size the sender declared, when known (null today: a message carries none).
    fun decide(kind: AttachmentKind, declaredSize: Long?, autoOn: Boolean): Decision {
        if (!autoOn) return Decision.CachedOnly
        val limit = when (kind) {
            AttachmentKind.IMAGE -> IMAGE_LIMIT
            AttachmentKind.VIDEO, AttachmentKind.AUDIO -> VIDEO_AUDIO_LIMIT
            AttachmentKind.PDF, AttachmentKind.FILE -> return Decision.Hold
        }
        if (declaredSize != null && declaredSize > limit) return Decision.Hold
        return Decision.FetchNow(limit)
    }
}

/// Whether a failed download is worth trying again, and what to tell the person.
object AttachmentFailure {
    enum class Kind { TRANSIENT, PERMANENT }

    /// Offline, timeouts, resets and server-side trouble (5xx, 404 before the file is
    /// there, 408, 429, and 401/403 once the one re-sign-in has not helped) are transient;
    /// an untrusted host, an unreadable token, no key, a failed decrypt or a file that is
    /// too large are not: trying again changes nothing.
    fun classify(error: Throwable): Kind = when (error) {
        is MemesServer.RefusedException ->
            if (error.status in 500..599 || error.status in TRANSIENT_STATUSES || error.isUnauthorized) Kind.TRANSIENT else Kind.PERMANENT
        is MemesServer.UnreadableException -> Kind.TRANSIENT
        is MemesServer.TooLargeException -> Kind.PERMANENT
        is IOException -> Kind.TRANSIENT // SocketTimeout, UnknownHost, resets, SSL: all IOExceptions.
        else -> Kind.PERMANENT
    }

    private val TRANSIENT_STATUSES = setOf(404, 408, 429)

    /// A short cause for a permanent failure, safe to show (never a raw exception message
    /// from outside this app).
    fun describe(error: Throwable): String = when (error) {
        is IllegalStateException -> error.message ?: "It could not be read."
        is MemesServer.TooLargeException -> "It is larger than this app downloads."
        is MemesServer.RefusedException -> "The server refused it (HTTP ${error.status})."
        is MediaCrypto.WrongKeyException, is MediaCrypto.MalformedException -> "It could not be decrypted."
        else -> "It could not be read."
    }
}

/// Automatic retries of a transient failure: 2 s, 10 s, 30 s, then it waits for a tap
/// (or the connection coming back, or the app returning to the front).
object RetryBackoff {
    val DELAYS_MS = listOf(2_000L, 10_000L, 30_000L)

    /// The wait before retry number `attempt` (0 = the first retry), or null when attempts are used up.
    fun delayMs(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt)
}

/// Where a decrypted attachment lives in `Storage.attachmentCacheDir`.
/// Named by SHA-256 of "muid\nkey" (as the iOS `MediaStore.cached`), never by the
/// sender-chosen muid alone: another sender reusing a muid with a different key gets a
/// different file, not someone else's plaintext. Names are hex: no separators, no traversal.
object AttachmentCache {
    fun fileKey(muid: String, key: String): String =
        MessageDigest.getInstance("SHA-256").digest("$muid\n$key".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    fun file(dir: File, muid: String, key: String): File = File(dir, fileKey(muid, key))

    fun nameFile(dir: File, muid: String, key: String): File = File(dir, fileKey(muid, key) + ".name")

    /// A file name read back from the sidecar (or a server header), made safe to show and to
    /// type a share by: no separators, no control characters, no leading dots, at most 200 characters.
    fun sanitizedName(raw: String?): String? {
        if (raw == null) return null
        val clean = raw.filter { it != '/' && it != '\\' && it.code >= 0x20 && it.code != 0x7f }
            .trimStart('.')
            .take(200)
        return clean.ifEmpty { null }
    }

    /// Writes `bytes` under `target` through a temp file in the same directory and a rename,
    /// so a reader never sees a half-written file.
    fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${java.util.UUID.randomUUID()}.tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) throw IOException("could not store the attachment")
        } finally {
            temp.delete()
        }
    }

    /// Stores a file and its name sidecar (the name first, so a file is never without it).
    fun store(dir: File, muid: String, key: String, bytes: ByteArray, fileName: String?) {
        sanitizedName(fileName)?.let { writeAtomically(nameFile(dir, muid, key), it.toByteArray(Charsets.UTF_8)) }
        writeAtomically(file(dir, muid, key), bytes)
    }

    /// Removes both files of an attachment.
    fun remove(dir: File, muid: String, key: String) {
        file(dir, muid, key).delete()
        nameFile(dir, muid, key).delete()
    }
}

/// What the agent's DM takes as attachments (peel's `Engine.sendLimit`, 32_000_000 bytes; not 32 MiB), and the words and names of its pending files.
/// Every other chat keeps `Engine`'s own 100 MB limit.
object AgentAttachments {
    /// The largest file sent to the agent, in bytes (`Engine.sendLimit`).
    const val SEND_LIMIT = 32_000_000

    /// The longest side of a picture sent to the agent: the Mac's text reader wants enough pixels.
    const val PICTURE_MAX_DIMENSION = 4096

    /// The note under the pending files when any is not a picture the model sees (iOS `FilesTray`).
    const val MAC_NOTE = "The agent reads files on your Mac, so Peel needs to be open there."

    /// Whether [size] bytes may go to the agent.
    fun fits(size: Long): Boolean = size <= SEND_LIMIT

    /// What a refused file says, in the style of the engine's "That file is too large to send."
    fun tooLarge(name: String): String = "$name is too large to send. The agent takes files up to 32 MB."

    /// Whether the note shows: a pending file the model will not see (not a picture, or its switch off).
    fun showsMacNote(files: List<Pair<Boolean, Boolean>>): Boolean = files.any { (isPicture, modelSees) -> !isPicture || !modelSees }

    /// A picture's name once it is a JPEG: its own with the extension replaced by `.jpg` ("scan.heic" -> "scan.jpg").
    fun jpegName(name: String): String {
        val base = name.substringBeforeLast('.', name).ifEmpty { "image" }
        return "$base.jpg"
    }
}

/// Pictures staged in a chat's composer before Send (every chat, not only the agent's DM): the pure rules.
/// Not ported from peel: the Mac/iPhone composers have their own tray; this is the Android equivalent, WhatsApp/Signal-style.
object StagedAttachments {
    /// Most pictures staged at once, and the gallery picker's `maxItems`.
    const val MAX_PICTURES = 10

    /// How many more pictures may be staged when [staged] are already held (never negative).
    fun room(staged: Int): Int = (MAX_PICTURES - staged).coerceAtLeast(0)

    /// Send is enabled with text, or with at least one staged file.
    fun canSend(text: String, staged: Int): Boolean = text.isNotBlank() || staged > 0

    /// A message carries one attachment, so the typed text is the caption of the first one only.
    fun captionFor(index: Int, caption: String): String = if (index == 0) caption else ""

    /// Shown when a pick had more pictures than [room] left.
    fun tooMany(): String = "You can attach up to $MAX_PICTURES photos at a time."

    /// The biggest file held for a chat: the agent's 32 MB, else the engine's own 100 MB (`Engine.ATTACHMENT_LIMIT`).
    fun sendLimit(agent: Boolean): Long = if (agent) AgentAttachments.SEND_LIMIT.toLong() else 100_000_000L

    /// Whether [size] bytes may be staged in a chat ([agent]: the agent's DM).
    fun fits(size: Long, agent: Boolean): Boolean = size <= sendLimit(agent)
}

/// Local video compression before upload: the pure rules (bitrate, resolution tier, output size).
/// `VideoCompressor` is the media3 part that applies them. A video over the chat send limit is
/// re-encoded to fit [TARGET_BYTES]; the original is never touched.
object VideoCompression {
    /// The client's send limit for a chat with a person or a tribe (`Engine.ATTACHMENT_LIMIT`).
    const val SEND_LIMIT = 100_000_000L

    /// What a compressed video aims for: a margin under [SEND_LIMIT] for container overhead and encryption.
    const val COMPRESSION_TARGET_BYTES = 95L * 1024 * 1024

    /// AAC audio bitrate, bits per second.
    const val AUDIO_BITRATE = 128_000

    /// Below this video bitrate the picture is not worth sending: the video is "too long to compress".
    /// Easy to change.
    const val FLOOR_BITRATE = 500_000

    /// Whether a file of [size] bytes needs compressing before it can be sent.
    fun needsCompression(size: Long, limit: Long = SEND_LIMIT): Boolean = size > limit

    /// The video bitrate (bits per second) that fits [targetBytes] in [durationMs], after the audio track.
    fun videoBitrate(durationMs: Long, targetBytes: Long = COMPRESSION_TARGET_BYTES): Long {
        val seconds = durationMs.coerceAtLeast(1) / 1000.0
        return (targetBytes * 8 / seconds).toLong() - AUDIO_BITRATE
    }

    /// The longest side and the shortest side an output may have for [bitrate].
    data class Tier(val longSide: Int, val shortSide: Int)

    fun tier(bitrate: Long): Tier = when {
        bitrate >= 6_500_000 -> Tier(1920, 1080)
        bitrate >= 2_500_000 -> Tier(1280, 720)
        bitrate >= 750_000 -> Tier(854, 480)
        else -> Tier(640, 360)
    }

    /// The upright size to encode, from the stored size and rotation: it keeps the aspect, never scales
    /// up, fits [tier] in the picture's own orientation, and both sides are even.
    fun outputSize(width: Int, height: Int, rotationDegrees: Int, tier: Tier): Pair<Int, Int> {
        val turned = rotationDegrees % 180 != 0
        val w = if (turned) height else width
        val h = if (turned) width else height
        if (w <= 0 || h <= 0) return 2 to 2
        val landscape = w >= h
        val maxW = if (landscape) tier.longSide else tier.shortSide
        val maxH = if (landscape) tier.shortSide else tier.longSide
        val scale = minOf(1.0, maxW.toDouble() / w, maxH.toDouble() / h)
        fun even(v: Double) = (Math.round(v).toInt() and 1.inv()).coerceAtLeast(2)
        return even(w * scale) to even(h * scale)
    }

    class Plan(val videoBitrate: Int, val width: Int, val height: Int)

    /// The encode for a video of this shape, or null when it is too long to compress under [floor].
    fun plan(
        durationMs: Long, width: Int, height: Int, rotationDegrees: Int,
        targetBytes: Long = COMPRESSION_TARGET_BYTES, floor: Long = FLOOR_BITRATE.toLong(),
    ): Plan? {
        val bitrate = videoBitrate(durationMs, targetBytes)
        if (bitrate < floor) return null
        val (w, h) = outputSize(width, height, rotationDegrees, tier(bitrate))
        return Plan(minOf(bitrate, Int.MAX_VALUE.toLong()).toInt(), w, h)
    }

    const val TOO_LONG = "This video is too long to compress enough to send."
    const val TOO_LARGE = "That file is too large to send."
    fun isVideo(mediaType: String?): Boolean = mediaType?.startsWith("video/") == true
}
