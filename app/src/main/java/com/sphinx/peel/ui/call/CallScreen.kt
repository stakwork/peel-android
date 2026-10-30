package com.sphinx.peel.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.sphinx.peel.engine.CallLink
import com.sphinx.peel.engine.CallTicket
import com.sphinx.peel.engine.Engine
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.theme.Theme
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/// The call screen: the chat and the running time on top, one tile per
/// person, the control bar — PeelCore's own `IOSCallScreen`, trimmed: no
/// screen sharing, no device menus, no keep-on-top (this round has none of
/// what any of those need). Replaces the whole chat screen while on the
/// call, the same way `ThreadScreen` replaces it for a thread — leaving
/// (the chevron, not the system back gesture, matching every other screen
/// in this port) ends the call; there is no persistent background call or
/// banner to return to yet (`docs/phases/13-livekit-calls.md`'s own
/// "Open").
@Composable
fun CallScreen(engine: Engine, chat: String, title: String, link: CallLink, camera: Boolean, onBack: () -> Unit) {
    var ticket by remember(link) { mutableStateOf<CallTicket?>(null) }
    var ticketError by remember(link) { mutableStateOf<String?>(null) }
    var fetching by remember(link) { mutableStateOf(true) }
    var attempt by remember(link) { mutableStateOf(0) }

    LaunchedEffect(link, attempt) {
        fetching = true
        ticketError = null
        try {
            ticket = engine.callTicket(link)
        } catch (e: Exception) {
            ticketError = e.message ?: "Could not join the call."
        } finally {
            fetching = false
        }
    }

    val currentTicket = ticket
    if (currentTicket == null) {
        Column(Modifier.fillMaxSize().background(Theme.colors.background)) {
            CallHeader(title = title, status = if (fetching) "Connecting…" else "", onBack = onBack)
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (fetching) {
                    CircularProgressIndicator(color = Theme.colors.accent)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            ticketError ?: "Could not join the call.",
                            color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { attempt += 1 }) { Text("Try again") }
                    }
                }
            }
        }
        return
    }

    val context = LocalContext.current
    val appContext = context.applicationContext

    // A system permission dialog needs an ActivityResultLauncher, which
    // only exists here — CallSession only ever calls these two closures,
    // whether it's the automatic "microphone on after connecting" or an
    // explicit tap on the control bar.
    var micContinuation by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var cameraContinuation by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micContinuation?.invoke(granted)
        micContinuation = null
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraContinuation?.invoke(granted)
        cameraContinuation = null
    }

    suspend fun askMic(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        return suspendCancellableCoroutine { cont ->
            micContinuation = { granted -> cont.resume(granted) }
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    suspend fun askCamera(): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
        return suspendCancellableCoroutine { cont ->
            cameraContinuation = { granted -> cont.resume(granted) }
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val session = remember(currentTicket) {
        CallSession(appContext, currentTicket, link, chat, title, camera, askMicPermission = ::askMic, askCameraPermission = ::askCamera)
    }
    DisposableEffect(session) { onDispose { session.leave() } }

    Column(Modifier.fillMaxSize().background(Theme.colors.background)) {
        CallHeader(title = session.title, status = CallStatusText(session), onBack = onBack)
        Box(Modifier.weight(1f).fillMaxSize()) { CallStage(session) }
        CallControls(session, onLeave = onBack, onRejoin = { ticket = null; attempt += 1 })
    }
}

/// The running time, ticking every second while connected; otherwise what
/// the connection is doing.
@Composable
private fun CallStatusText(session: CallSession): String {
    val now by produceState(initialValue = System.currentTimeMillis(), session.state) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    return session.status(now)
}

@Composable
private fun CallHeader(title: String, status: String, onBack: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Back to chats", tint = Theme.colors.text)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Theme.colors.text, maxLines = 1)
                if (status.isNotEmpty()) {
                    Text(status, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
                }
            }
            Spacer(Modifier.width(48.dp))
        }
    }
}

/// A plain grid of tiles — PeelCore's own `IOSCallStage`, trimmed: no
/// screen-share focus (nothing here can share one yet), so there is no
/// large-tile-plus-strip layout to build, just the grid.
@Composable
private fun CallStage(session: CallSession) {
    val tiles = session.tiles
    val columns = if (tiles.size <= 2) 1 else 2
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(tiles, key = { it.identity }) { tile ->
            CallTileView(tile, session, modifier = Modifier.fillMaxWidth().aspectRatio(if (columns == 1) 16f / 10f else 1f))
        }
    }
}

@Composable
private fun CallTileView(tile: CallTile, session: CallSession, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.clip(shape).background(Theme.colors.fieldFill)
            .then(if (tile.speaking) Modifier.border(2.dp, Theme.colors.accent, shape) else Modifier),
    ) {
        val video = tile.video
        if (video != null) {
            CallVideoView(video, session, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Avatar(
                    name = tile.person.nickname.ifEmpty { if (tile.isYou) "You" else tile.person.identity },
                    url = tile.person.avatarUrl, size = 64.dp, hue = PersonHue.of(tile.person.identity), shape = AvatarShape.CIRCLE,
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                .background(Theme.colors.background.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tile.muted) {
                Icon(Icons.Filled.MicOff, contentDescription = "Muted", tint = Theme.colors.secondaryText, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                if (tile.isYou) "You" else tile.person.nickname.ifEmpty { tile.person.identity },
                style = MaterialTheme.typography.labelSmall, color = Theme.colors.text, maxLines = 1,
            )
        }
    }
}

/// Wraps LiveKit's own `SurfaceViewRenderer` (a plain Android `View`, not a
/// Compose-native one) — attaches the track's frames while this tile is
/// composed, detaches and releases when it leaves, same lifecycle the
/// renderer itself expects on either platform's SDK.
@Composable
private fun CallVideoView(video: VideoTrack, session: CallSession, modifier: Modifier = Modifier) {
    // `update` runs on every recomposition of this tile (a sibling tile's
    // speaking state changing is enough), not just when `video` itself
    // changes — re-adding the same renderer to the same track on every one
    // of those would be harmless at best, but a track swap (the camera
    // toggled off and back on makes a new `LocalVideoTrack` instance,
    // reusing the same `View`) needs the *old* track detached first, or
    // its frames would keep arriving at a renderer no tile shows anymore.
    // The renderer's own `tag` is the one place to remember which track is
    // currently attached to it between calls.
    AndroidView(
        factory = { context ->
            SurfaceViewRenderer(context).also { renderer ->
                session.initVideoRenderer(renderer)
                renderer.tag = video
                video.addRenderer(renderer)
            }
        },
        update = { renderer ->
            val attached = renderer.tag as? VideoTrack
            if (attached !== video) {
                attached?.removeRenderer(renderer)
                video.addRenderer(renderer)
                renderer.tag = video
            }
        },
        onRelease = { renderer ->
            (renderer.tag as? VideoTrack)?.removeRenderer(renderer)
            renderer.release()
        },
        modifier = modifier,
    )
}

@Composable
private fun CallControls(session: CallSession, onLeave: () -> Unit, onRejoin: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundControlButton(
                icon = if (session.micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                on = session.micOn, refused = session.micRefused, label = if (session.micOn) "Mute" else "Unmute",
                onClick = session::toggleMicrophone,
            )
            RoundControlButton(
                icon = if (session.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                on = session.cameraOn, refused = session.cameraRefused,
                label = if (session.cameraOn) "Turn camera off" else "Turn camera on",
                onClick = session::toggleCamera,
            )
            if (session.cameraOn) {
                RoundControlButton(icon = Icons.Filled.Cameraswitch, on = true, refused = false, label = "Flip camera", onClick = session::flipCamera)
            }
            IconButton(
                onClick = { session.leave(); onLeave() },
                modifier = Modifier.size(64.dp, 52.dp).clip(RoundedCornerShape(50)).background(Theme.colors.destructive),
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = "Leave the call", tint = Theme.colors.text)
            }
        }
        Spacer(Modifier.height(8.dp))
        val refused = listOfNotNull(
            "the microphone".takeIf { session.micRefused },
            "the camera".takeIf { session.cameraRefused },
        )
        if (refused.isNotEmpty()) {
            CallNote("Can't use ${refused.joinToString(" or ")}", action = "Open Settings") {
                context.startActivity(
                    android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }
        }
        session.deviceNotice?.let { CallNote(it) }
        if (session.state == CallConnectionState.LOST) {
            val text = if (session.connectedAtMs == null) "Could not connect to the call" else "Lost the connection to the call"
            CallNote(text, action = "Rejoin", onAction = onRejoin)
        }
        if (session.isRecording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Theme.colors.destructive, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(4.dp))
                Text("This call is being recorded", style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
            }
        }
    }
}

@Composable
private fun RoundControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, refused: Boolean, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick, enabled = !refused,
        modifier = Modifier.size(52.dp).clip(CircleShape).background(Theme.colors.fieldFill),
    ) {
        Icon(icon, contentDescription = label, tint = if (on) Theme.colors.text else Theme.colors.secondaryText)
    }
}

@Composable
private fun CallNote(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
        if (action != null) {
            Text(" · ", style = MaterialTheme.typography.labelSmall, color = Theme.colors.secondaryText)
            Text(
                action, style = MaterialTheme.typography.labelSmall, color = Theme.colors.accent,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}
