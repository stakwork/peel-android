package com.sphinx.peel.ui.components

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// A press-and-hold voice recording — PeelCore's own `VoiceMessage`:
/// 16-bit linear PCM, mono, 16 kHz, a plain WAV container, named
/// `"audio.wav"` with `mediaType` `"audio/wav"` — the one format every
/// Sphinx client (iOS, Mac, the official Android app) sends and plays
/// as a voice message (`VoiceMessage.swift`'s own comment: iOS records
/// 44.1 kHz and the official Android 8 kHz, so 16 kHz plays on both).
/// Built on `AudioRecord`, not `MediaRecorder` — `MediaRecorder` has no
/// output format that is a plain, uncompressed WAV, and nothing short
/// of that is what's needed here. Not a composable itself: a plain
/// holder a screen `remember`s once.
///
/// Slide-to-cancel (PeelCore's own `cancelDistance`, 80 points left or
/// up) is the gesture's job, not this class's: the caller
/// (`ConversationScreen`'s own `pointerInput` loop) tracks the drag and
/// calls `updateCancelling` as it crosses the threshold, purely so the
/// composer can show "Release to cancel" — the actual discard happens
/// through the same `cancel()` this class already needed for the app
/// backgrounding mid-recording.
class VoiceRecorder {
    var isRecording by mutableStateOf(false)
        private set
    var elapsedMs by mutableStateOf(0L)
        private set
    var isCancelling by mutableStateOf(false)
        private set

    private var stopRequested = false
    private var cancelled = false

    /// Starts recording, or does nothing if already recording. Calls
    /// `onFinished` exactly once, off the main thread's caller (always
    /// on `Dispatchers.Main`, safe to touch Compose state from): the
    /// WAV bytes, or null for a permission/device failure, a recording
    /// under `MINIMUM_DURATION_MS` (PeelCore's own "a tap, not a
    /// message" rule), or one `cancel()` discarded.
    fun start(scope: CoroutineScope, onFinished: (ByteArray?) -> Unit) {
        if (isRecording) return
        stopRequested = false
        cancelled = false
        isCancelling = false

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferSize <= 0) {
            onFinished(null)
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2,
            )
        } catch (e: SecurityException) {
            onFinished(null)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onFinished(null)
            return
        }

        isRecording = true
        elapsedMs = 0L
        record.startRecording()

        scope.launch(Dispatchers.IO) {
            val pcm = ByteArrayOutputStream()
            val chunk = ByteArray(minBufferSize)
            val startedAt = System.currentTimeMillis()
            try {
                while (isActive && !stopRequested) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) pcm.write(chunk, 0, read)
                    val elapsed = System.currentTimeMillis() - startedAt
                    withContext(Dispatchers.Main) { elapsedMs = elapsed }
                    if (elapsed >= MAXIMUM_DURATION_MS) break
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
            val durationMs = System.currentTimeMillis() - startedAt
            withContext(Dispatchers.Main) { isRecording = false }
            val wav = if (!cancelled && durationMs >= MINIMUM_DURATION_MS && pcm.size() > 0) {
                toWav(pcm.toByteArray())
            } else {
                null
            }
            onFinished(wav)
        }
    }

    /// Whether the held finger has slid past the cancel threshold right
    /// now — the gesture handler's own live read of the drag, not
    /// something this class computes itself. Purely for the composer's
    /// "Release to cancel" feedback; doesn't by itself discard anything
    /// (the gesture handler calls `cancel()` instead of `release()` at
    /// the end if this was true when the finger lifted).
    fun updateCancelling(value: Boolean) {
        isCancelling = value
    }

    /// The finger lifted — the recording loop finishes on its own next
    /// read, same path as hitting `MAXIMUM_DURATION_MS` on its own.
    fun release() {
        stopRequested = true
    }

    /// Discards whatever was recorded instead of finishing it — a
    /// slide-to-cancel release, or the app backgrounded mid-recording
    /// (PeelCore's own `.onChange(of: scenePhase)`).
    fun cancel() {
        cancelled = true
        stopRequested = true
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16

        /// A recording this short or shorter was a tap, not a message —
        /// PeelCore's own `VoiceMessage.minimumDuration`.
        const val MINIMUM_DURATION_MS = 1_000L

        /// The recorder stops and sends at this length on its own —
        /// PeelCore's own `VoiceMessage.maximumDuration`.
        const val MAXIMUM_DURATION_MS = 300_000L

        /// A RIFF/WAVE header (`fmt `, then `data`) in front of raw PCM
        /// — what `peel`'s own `WavHeader(_:)` parses in reverse.
        private fun toWav(pcm: ByteArray): ByteArray {
            val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
            val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + pcm.size)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM, uncompressed
                putShort(CHANNELS.toShort())
                putInt(SAMPLE_RATE)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(BITS_PER_SAMPLE.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcm.size)
            }.array()
            return header + pcm
        }
    }
}
