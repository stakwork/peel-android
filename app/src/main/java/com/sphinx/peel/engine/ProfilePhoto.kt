package com.sphinx.peel.engine

/// The account's own photo — the pure rules (docs/phases/25-profile-photo.md). Ported from
/// PeelCore's `ProfilePhoto` (`Files.swift`) and `Engine.setPhotoURL`/`uploadPhoto`
/// (`Engine+Account.swift`). The picture is redrawn as a JPEG (quality 0.5, at most
/// `MAX_PIXELS` on the long side, upright, no metadata — `prepareImageForSending`), uploaded in
/// the clear to the memes server's `/public` route (`Engine.uploadPicture`), and its https link
/// is stored in `Setting.PHOTO_URL`; it then goes out as `img` with every message from then on,
/// the way the name does. Not ported: the Mac's drag-and-drop and its `PictureField`.
object ProfilePhoto {
    /// iOS `ProfilePhoto.maxPixels`: the long side of the uploaded picture.
    const val MAX_PIXELS = 1024

    /// iOS's `CGImageDestination` quality 0.5, as Android's 0-100.
    const val JPEG_QUALITY = 50

    /// iOS `Engine.attachmentLimit`: a picked file larger than this is refused before it is read.
    const val MAX_FILE_BYTES = 100_000_000L

    /// What to store for `url`: trimmed; empty means none (a removed photo); anything else must
    /// be https (iOS `setPhotoURL`, `Failure.photoNotHTTPS`).
    fun clean(url: String): Result<String> {
        val text = url.trim()
        return if (text.isEmpty() || text.startsWith("https://", ignoreCase = true)) Result.success(text)
        else Result.failure(IllegalArgumentException("The photo must be an https link."))
    }

    /// The size a `width` x `height` picture is drawn at: the long side at most `max`, the aspect
    /// kept, never below 1 px, never enlarged.
    fun fit(width: Int, height: Int, max: Int = MAX_PIXELS): Pair<Int, Int> {
        val largest = maxOf(width, height)
        if (largest <= max || largest <= 0) return width to height
        val scale = max.toDouble() / largest
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /// Why a picked file is refused before reading, or null (iOS `TooLarge`).
    fun refusal(sizeBytes: Long?): String? =
        if (sizeBytes != null && sizeBytes > MAX_FILE_BYTES) "That picture is too large." else null

    /// What to tell the person when choosing or uploading failed.
    fun message(error: Throwable): String =
        "Could not change your photo: " + (error.message?.takeIf { it.isNotBlank() } ?: "try again")
}
