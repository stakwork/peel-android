package com.sphinx.peel.ui.dashboard

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.engine.VideoCompressor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/// One oversized video being compressed for a chat, and what to do when it is done. Send can be tapped
/// meanwhile: the caption and reply are kept and the video goes the moment compression finishes. The
/// original is only read; the compressed copy lives in the cache and is deleted after it is sent or removed.
class VideoOutbox(private val context: Context, private val engine: Engine, private val scope: CoroutineScope) {
    enum class Phase { NONE, COMPRESSING, READY }

    class Queued(val caption: String, val replyUuid: String?, val inThread: String?)

    var phase by mutableStateOf(Phase.NONE); private set
    var name by mutableStateOf(""); private set
    var queued by mutableStateOf<Queued?>(null); private set

    /// Sends the compressed file (set by the screen). A throw leaves the video ready to retry.
    var deliver: suspend (file: File, name: String, queued: Queued) -> Unit = { _, _, _ -> }

    /// Called with the queued send's text when a queued video fails, so the screen can put it back.
    var onFailed: (Queued?, String) -> Unit = { _, _ -> }

    private var job: Job? = null
    private var output: File? = null

    val active: Boolean get() = phase != Phase.NONE

    fun start(uri: Uri, displayName: String) {
        if (active) return
        phase = Phase.COMPRESSING
        name = displayName
        val file = File(context.cacheDir, "outgoing/compressed-${System.currentTimeMillis()}.mp4")
        output = file
        engine.transfer.compressing(0f)
        job = scope.launch {
            try {
                VideoCompressor(context).compress(uri, file) { engine.transfer.compressing(it) }
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                file.delete()
                output = null
                phase = Phase.NONE
                val waiting = queued
                queued = null
                val reason = e.message ?: "Couldn't compress this video."
                engine.transfer.failed(reason)
                onFailed(waiting, reason)
                return@launch
            }
            engine.transfer.idle()
            phase = Phase.READY
            queued?.let { deliverNow(it) }
        }
    }

    /// Send was tapped: now if the video is ready, else as soon as it is.
    fun send(request: Queued) {
        if (phase == Phase.READY) deliverNow(request) else if (phase == Phase.COMPRESSING) queued = request
    }

    private var delivering = false

    private fun deliverNow(request: Queued) {
        val file = output ?: return
        if (delivering) return
        delivering = true
        queued = request
        scope.launch {
            try {
                deliver(file, name, request)
                delivering = false
                clear()
            } catch (e: CancellationException) {
                delivering = false
                throw e
            } catch (e: Exception) {
                delivering = false
                queued = null
                onFailed(request, e.message ?: "Couldn't send the video.")
            }
        }
    }

    /// Removes the video: stops the encode, deletes the copy.
    fun cancel() {
        job?.cancel()
        if (phase == Phase.COMPRESSING) engine.transfer.idle()
        clear()
    }

    private fun clear() {
        job = null
        output?.delete()
        output = null
        queued = null
        phase = Phase.NONE
    }
}

/// A picked document's name, size (-1 unknown) and declared type, read without opening its content.
class PickedFile(val name: String, val size: Long, val mediaType: String?)

fun pickedFile(context: Context, uri: Uri): PickedFile {
    val resolver = context.contentResolver
    var name: String? = null
    var size = -1L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val n = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (n >= 0) name = cursor.getString(n)
            val s = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
        }
    }
    return PickedFile(name ?: uri.lastPathSegment ?: "file", size, resolver.getType(uri))
}
