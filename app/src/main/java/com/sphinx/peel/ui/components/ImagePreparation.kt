package com.sphinx.peel.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/// A picked image's bytes, ready to send: decoded, rotated upright by
/// its own EXIF orientation, then re-encoded as a plain JPEG — PeelCore's
/// own picker path (`Apps/iOS/Peel/Composer.swift`'s `jpeg(_:)`): "only
/// the picture goes, never its metadata" — no GPS, no camera make or
/// model, no original EXIF at all, since none of it survives a fresh
/// encode of an already-decoded bitmap. Downscaled to at most
/// `maxDimension` on the long side: a phone camera's own resolution is
/// far more than a chat attachment needs. Null for anything that
/// doesn't actually decode as an image.
fun prepareImageForSending(context: Context, uri: Uri, maxDimension: Int = 2048, quality: Int = 85): ByteArray? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotated = rotateUpright(decoded, exifRotationDegrees(bytes))
    val scaled = downscale(rotated, maxDimension)
    val output = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
    return output.toByteArray()
}

private fun exifRotationDegrees(bytes: ByteArray): Int {
    val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull() ?: return 0
    return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}

private fun rotateUpright(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val largest = maxOf(bitmap.width, bitmap.height)
    if (largest <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / largest
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
}
