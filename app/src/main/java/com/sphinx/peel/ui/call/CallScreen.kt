package com.sphinx.peel.ui.call

import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sphinx.peel.ui.components.Avatar
import com.sphinx.peel.ui.components.AvatarShape
import com.sphinx.peel.ui.components.PersonHue
import com.sphinx.peel.ui.theme.Theme
import com.sphinx.peel.ui.toast.LocalToast
import com.sphinx.peel.ui.toast.ToastType
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay

/// The call screen: the chat and the running time on top, one tile per
/// person, the control bar — PeelCore's own `IOSCallScreen`, trimmed: no
/// screen sharing, no device menus, no keep-on-top (this round has none of
/// what any of those need). A pure renderer of [phase] — fetching the
/// ticket, owning the [CallSession], and deciding whether a call is even
/// active at all live above this, in `MainTabs` (`docs/phases/15-call-
/// background.md`): this screen comes and goes (the chevron hides it, a
/// tap on `CallBanner` brings it back) without the call itself ending —
/// only `onLeave` does that.
@Composable
fun CallScreen(phase: CallPhase, onHide: () -> Unit, onLeave: () -> Unit, onRetry: () -> Unit) {
    when (phase) {
        is CallPhase.Connecting -> Column(Modifier.fillMaxSize().background(Theme.colors.background)) {
            CallHeader(title = phase.title, status = "Connecting…", onBack = onHide)
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Theme.colors.accent)
            }
        }
        is CallPhase.Failed -> Column(Modifier.fillMaxSize().background(Theme.colors.background)) {
            CallHeader(title = phase.title, status = "", onBack = onHide)
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(phase.error, color = Theme.colors.secondaryText, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
        }
        is CallPhase.Active -> {
            val session = phase.session
            Column(Modifier.fillMaxSize().background(Theme.colors.background)) {
                CallHeader(title = session.title, status = CallStatusText(session), onBack = onHide)
                Box(Modifier.weight(1f).fillMaxSize()) { CallStage(session) }
                CallControls(session, onLeave = onLeave, onRejoin = onRetry)
            }
        }
    }
}

/// The running time, ticking every second while connected; otherwise what
/// the connection is doing.
@Composable
internal fun CallStatusText(session: CallSession): String {
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
            // 4:3 for one or two people (closer to the real app's own
            // proportions — a tall-ish rectangle, not a wide letterboxed
            // one) stacked in a single column; square once there are
            // enough people to need a real grid.
            CallTileView(tile, session, modifier = Modifier.fillMaxWidth().aspectRatio(if (columns == 1) 4f / 3f else 1f))
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
                onClick = onLeave,
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
        val toast = LocalToast.current
        val notice = session.deviceNotice
        LaunchedEffect(notice) {
            if (notice != null) {
                toast.show(notice, ToastType.Warning)
                session.clearDeviceNotice()
            }
        }
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
internal fun CallNote(text: String, action: String? = null, onAction: () -> Unit = {}) {
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
