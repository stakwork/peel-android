package com.sphinx.peel.ui.components

import android.media.MediaMetadataRetriever
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// A video's first frame and length, read once off the main thread.
/// Null fields when the file can't be read (the row then shows a plain
/// dark box, still playable).
class VideoInfo(val frame: ImageBitmap?, val durationMs: Int, val aspect: Float)

private fun readVideoInfo(file: File): VideoInfo {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val bitmap = retriever.getFrameAtTime(0)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        val aspect = bitmap?.let { it.width.toFloat() / it.height }?.takeIf { it > 0f } ?: (16f / 9f)
        VideoInfo(bitmap?.asImageBitmap(), duration, aspect)
    } catch (_: Exception) {
        VideoInfo(null, 0, 16f / 9f)
    } finally {
        runCatching { retriever.release() }
    }
}

/// A voice message's length, so the row can say "0:00 / 0:06" before play.
suspend fun readMediaDurationMs(file: File): Int = withContext(Dispatchers.IO) { readVideoInfo(file).durationMs }

/// An inline video: thumbnail with a play button and duration badge;
/// tapping play swaps in a `VideoView` playing in place, and the corner
/// button opens it full screen with transport controls.
@Composable
fun VideoAttachment(file: File) {
    val info by produceVideoInfo(file)
    var playing by remember(file) { mutableStateOf(false) }
    var fullscreen by remember(file) { mutableStateOf(false) }
    val aspect = info?.aspect ?: (16f / 9f)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.6f, 2f))
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        if (playing) {
            InlineVideo(file, Modifier.fillMaxSize(), controls = false, onEnd = { playing = false })
        } else {
            info?.frame?.let {
                Image(it, contentDescription = "Video", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { playing = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(34.dp))
            }
            info?.durationMs?.takeIf { it > 0 }?.let {
                Text(
                    formatDuration(it),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        IconButton(
            onClick = { playing = false; fullscreen = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Icon(Icons.Filled.Fullscreen, contentDescription = "Full screen", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }

    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                InlineVideo(file, Modifier.fillMaxSize(), controls = true, onEnd = {})
                CloseButton({ fullscreen = false }, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
private fun produceVideoInfo(file: File) = androidx.compose.runtime.produceState<VideoInfo?>(null, file) {
    value = withContext(Dispatchers.IO) { readVideoInfo(file) }
}

@Composable
private fun InlineVideo(file: File, modifier: Modifier, controls: Boolean, onEnd: () -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VideoView(context).apply {
                setVideoPath(file.absolutePath)
                if (controls) setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnCompletionListener { onEnd() }
                setOnPreparedListener { start() }
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.padding(12.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
    }
}

/// Full-screen image on black: pinch to zoom, drag to pan when zoomed,
/// double-tap to toggle zoom; the X or back dismisses.
@Composable
fun ZoomableImageDialog(file: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f } else scale = 2.5f
                    })
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) {
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offsetX = (offsetX + pan.x * scale).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y * scale).coerceIn(-maxY, maxY)
                        } else {
                            offsetX = 0f; offsetY = 0f
                        }
                    }
                },
        ) {
            AsyncImage(
                model = file,
                contentDescription = "Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
            )
            CloseButton(onDismiss, Modifier.align(Alignment.TopEnd))
        }
    }
}
