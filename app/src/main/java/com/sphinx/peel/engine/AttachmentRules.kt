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
            .joinToString("") { "%02x".format(it) }

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
