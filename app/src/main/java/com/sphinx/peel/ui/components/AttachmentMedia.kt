package com.sphinx.peel.ui.components

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalConfiguration
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

/// An attachment's aspect ratio, by file path, once known: scrolling a
/// chat recomposes a media row fresh every time it re-enters the lazy
/// list's window, which otherwise meant re-reading the file and, once
/// that finished, resizing the bubble again — each one a layout change
/// the list doesn't re-anchor to, so the scroll position drifted off the
/// bottom a little further with every attachment that scrolled past.
/// Cached once per file for this process's lifetime, a row that has been
/// seen before renders at its real height from its very first frame, no
/// placeholder-then-resize at all. Never persisted: the file's own
/// decode is cheap enough that there's no reason to survive a restart.
object MediaAspectCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Float>()

    /// Bumped on every newly learned aspect ratio, so a screen already
    /// positioned at the bottom can re-settle there as each of a fresh
    /// batch of attachments resolves its real height, without every row
    /// needing its own callback up to the list for it.
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun get(path: String): Float? = cache[path]
    fun put(path: String, aspect: Float) {
        cache[path] = aspect
        version++
    }
}

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

/// An image's pixel size from its header only (no decode); null if unreadable.
suspend fun readImageSize(file: File): Pair<Int, Int>? = withContext(Dispatchers.IO) {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
    if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
}

/// Sizes a media bubble: always 70% of the screen width, so a large photo
/// or video never fills the whole row — bare "width: fill" for every
/// attachment reads as a wall of media, not a chat. Fixed the moment the
/// row composes, not the asset's own size: that only arrives once the
/// file's been read (async), and sizing the bubble to it meant every media
/// row visibly resized the instant it loaded, jumping the scroll position
/// along with it. Height from the aspect ratio and bounded by the screen
/// height.
@Composable
fun Modifier.mediaBubble(aspect: Float): Modifier {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
    val width = (LocalConfiguration.current.screenWidthDp * 0.7f).dp
    return this.width(width).heightIn(max = maxHeight).aspectRatio(aspect)
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
    // The aspect alone, from the cache when there is one — decoupled from
    // `info` (the frame/duration, which still has to load fresh every
    // time) so a previously-seen video's bubble is never sized by the
    // placeholder 16:9 even for the one frame before `info` answers.
    val path = file.path
    var aspect by remember(path) { mutableStateOf(MediaAspectCache.get(path) ?: (16f / 9f)) }
    LaunchedEffect(info?.aspect) {
        val a = info?.aspect ?: return@LaunchedEffect
        if (MediaAspectCache.get(path) == null) {
            MediaAspectCache.put(path, a)
            aspect = a
        }
    }

    Box(
        Modifier
            .mediaBubble(aspect)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        if (playing) {
            InlineVideo(file, Modifier.fillMaxSize(), controls = false, onEnd = { playing = false })
        } else {
            info?.frame?.let {
                Image(it, contentDescription = "Video", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
