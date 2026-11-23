package com.sphinx.peel.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/// Re-encodes a video to fit the send limit (androidx.media3 Transformer), per `VideoCompression`'s
/// rules. The source uri is only read; the result is a new file the caller deletes. Not unit-tested
/// (needs a device codec); the rules it applies are.
@androidx.annotation.OptIn(UnstableApi::class)
class VideoCompressor(private val context: Context) {
    class TooLongException : Exception(VideoCompression.TOO_LONG)

    private class Probe(val durationMs: Long, val width: Int, val height: Int, val rotation: Int)

    private fun probe(uri: Uri): Probe {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return Probe(
                duration, int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            )
        } finally {
            retriever.release()
        }
    }

    /// Compresses [uri] into [output], reporting 0..1. Cancelling the caller cancels the encode; on any
    /// failure or cancel the partial [output] is deleted. Throws [TooLongException] when the bitrate
    /// that fits would be under the floor.
    suspend fun compress(uri: Uri, output: File, onProgress: (Float) -> Unit): File {
        val probe = withContext(Dispatchers.IO) { probe(uri) }
        val plan = VideoCompression.plan(probe.durationMs, probe.width, probe.height, probe.rotation)
            ?: throw TooLongException()
        output.parentFile?.mkdirs()
        output.delete()
        try {
            // Transformer needs a thread with a Looper: the main one.
            withContext(Dispatchers.Main) { encode(uri, output, plan, onProgress) }
            return output
        } catch (e: Throwable) {
            output.delete()
            throw e
        }
    }

    private suspend fun encode(uri: Uri, output: File, plan: VideoCompression.Plan, onProgress: (Float) -> Unit) {
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate).build())
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(VideoCompression.AUDIO_BITRATE).build())
            .build()
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT))))
            .build()
        suspendCancellableCoroutine<Unit> { continuation ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoders)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()
            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!continuation.isActive) return
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    handler.postDelayed(this, 250)
                }
            }
            continuation.invokeOnCancellation {
                handler.post { handler.removeCallbacks(poll); transformer.cancel() }
            }
            transformer.start(edited, output.path)
            handler.post(poll)
        }
    }
}
