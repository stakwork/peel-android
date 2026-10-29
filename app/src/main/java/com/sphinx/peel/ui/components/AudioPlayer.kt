package com.sphinx.peel.ui.components

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// One `MediaPlayer`, shared by every voice message in a chat screen —
/// playing one stops whatever was already playing, the way any
/// messaging app's own voice notes behave; nothing here is specific to
/// one row. Not a composable itself: a plain holder a screen `remember`s
/// once and releases when it leaves.
class AudioPlayer {
    private var player: MediaPlayer? = null

    var playingFile by mutableStateOf<File?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0)
        private set
    var durationMs by mutableStateOf(0)
        private set

    /// Starts `file` playing from wherever it last stopped (resets to
    /// the beginning for a different file than whatever was already
    /// loaded); pauses it if it's already the one playing, resumes it
    /// if it's already loaded but paused.
    suspend fun toggle(file: File) {
        if (playingFile == file) {
            if (isPlaying) pause() else resume()
            return
        }
        release()
        val prepared = withContext(Dispatchers.IO) {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
        }
        player = prepared
        playingFile = file
        durationMs = prepared.duration
        prepared.setOnCompletionListener {
            positionMs = durationMs
            isPlaying = false
        }
        prepared.start()
        isPlaying = true
    }

    private fun pause() {
        player?.pause()
        isPlaying = false
    }

    private fun resume() {
        player?.start()
        isPlaying = true
    }

    /// Where playback actually is right now — polled by whoever shows
    /// the progress bar (`ConversationScreen`'s own `LaunchedEffect`),
    /// not pushed, since `MediaPlayer` has no position-changed callback.
    fun refreshPosition() {
        if (isPlaying) positionMs = player?.currentPosition ?: positionMs
    }

    fun seekTo(ms: Int) {
        player?.seekTo(ms)
        positionMs = ms
    }

    /// Stops and frees the current player, if any — called before
    /// loading a different file, and when the screen holding this goes
    /// away (`DisposableEffect`).
    fun release() {
        player?.release()
        player = null
        playingFile = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }
}

/// "0:07", "12:30" — PeelCore's own `MediaInfo.format`, minus the hour
/// place: a voice message is never that long.
fun formatDuration(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
