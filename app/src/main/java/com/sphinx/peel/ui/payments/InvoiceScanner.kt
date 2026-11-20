package com.sphinx.peel.ui.payments

import com.sphinx.peel.ui.str
import com.sphinx.peel.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.camera.view.TransformExperimental
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sphinx.peel.engine.PaymentInput
import com.sphinx.peel.ui.theme.Theme
import java.util.concurrent.Executors

/// The camera, looking for a QR code that holds an invoice (the iPhone's
/// `InvoiceScanner`). Only a BOLT 11 invoice is taken: anything else it reads
/// is untrusted text and is passed over with a word. Paste is the other way
/// in. The camera permission is asked when this opens (`CAMERA` is declared
/// in the manifest already, for calls); refused, it says so and points at paste.
@Composable
fun InvoiceScanner(onFound: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    CodeScanner(
        prompt = androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.scan_invoice_prompt),
        refused = androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.scan_invoice_refused),
        accept = { text ->
            when (val input = PaymentInput.parse(text)) {
                is PaymentInput.Bolt11 -> { onFound(input.bolt11); null }
                is PaymentInput.Unsupported -> context.str(com.sphinx.peel.R.string.scan_unsupported, input.kind)
                PaymentInput.Unknown -> context.str(com.sphinx.peel.R.string.scan_not_invoice)
            }
        },
        onCancel = onCancel,
    )
}

/// The camera, looking for a QR code, the way the iOS Camera app does: yellow brackets follow
/// any code in view and a pill names it; nothing happens until the pill is tapped. Then `accept`
/// runs: it returns null once it took the code (the scan then stops, so one scan is one result),
/// or a word for the person about why not. A refused link Peel handles itself is opened in the
/// app, and any other address with a scheme in whichever app opens it (`ScanRoute`).
/// Shared by the invoice scan and the invite and contact-code scans.
@Composable
fun CodeScanner(
    prompt: String,
    accept: (String) -> String?,
    onCancel: () -> Unit,
    refused: String = androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.scanner_camera_refused),
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    var note by remember { mutableStateOf<String?>(null) }
    val noApp = stringResource(R.string.scan_no_app)
    val scanWords = ScanWords(
        stringResource(R.string.scan_label_open_peel), { context.str(R.string.scan_label_open_host_peel, it) },
        stringResource(R.string.scan_label_link_box), stringResource(R.string.scan_label_invite),
        stringResource(R.string.scan_label_contact), stringResource(R.string.scan_label_tribe),
        stringResource(R.string.scan_label_invoice), { context.str(R.string.scan_label_invoice_amount, it) },
    )
    val scanLocale = context.resources.configuration.locales[0]
    var done by remember { mutableStateOf(false) }
    // What the camera last saw, and when (a counter, so each frame restarts the fades).
    var sighting by remember { mutableStateOf<Sighting?>(null) }
    var seenCount by remember { mutableIntStateOf(0) }
    var pill by remember { mutableStateOf<Pair<String, String>?>(null) } // text, label

    LaunchedEffect(seenCount) {
        if (seenCount == 0) return@LaunchedEffect
        delay(PILL_GRACE_MS)
        pill = null
    }

    fun tapped(text: String) {
        val problem = accept(text)
        if (problem == null) {
            note = null
            done = true
            pill = null
            sighting = null
            return
        }
        when (ScanRoute.of(text)) {
            ScanRoute.InApp -> {
                val sent = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text.trim())).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (sent) { done = true; onCancel() } else note = problem
            }
            ScanRoute.External -> {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    note = noApp
                } catch (e: SecurityException) {
                    note = noApp
                }
            }
            ScanRoute.Refuse -> note = problem
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(active = !done) { seen ->
                sighting = seen
                seenCount += 1
                if (seen.text != pill?.first) pill = seen.text to ScanLabel.of(seen.text, scanLocale, scanWords)
            }
            if (!done) ScanBrackets(sighting, seenCount)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScanPill(pill) { pill?.let { tapped(it.first) } }
            Text(
                when {
                    denied -> refused
                    !granted -> androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.scanner_waiting_camera)
                    else -> note ?: prompt
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(12.dp),
            )
            if (denied) TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.scanner_open_settings), color = Theme.colors.accent) }
            TextButton(onClick = onCancel) { Text(androidx.compose.ui.res.stringResource(com.sphinx.peel.R.string.common_cancel), color = Theme.colors.accent) }
        }
    }
}

/// How long a code can be out of sight before its pill goes (no flicker between frames).
private const val PILL_GRACE_MS = 1_000L

/// A code the camera saw: its text and its corners (top-left, clockwise) in the preview's pixels,
/// null when ZXing gave too few points to place it.
private class Sighting(val text: String, val corners: List<ScanPoint>?)

/// CameraX's back camera into a `PreviewView`, every frame's luminance plane offered to ZXing for
/// as long as [active]; each code found is reported on the main thread with its place in the view.
@OptIn(TransformExperimental::class)
@Composable
private fun CameraPreview(active: Boolean, seen: (Sighting) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val latestSeen by androidx.compose.runtime.rememberUpdatedState(seen)
    val running = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    running.set(active)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    // The preview's side of CameraX's coordinate map, read on the main thread for the analyzer's.
    val target = remember { java.util.concurrent.atomic.AtomicReference<OutputTransform?>(null) }
    val main = remember { ContextCompat.getMainExecutor(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val bound = providerFuture.get()
            provider = bound
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                main.execute { target.set(previewView.outputTransform) }
                analyze(image, running, target.get(), previewView) { found ->
                    // Back on the main thread: `seen` touches Compose state.
                    main.execute { if (running.get()) latestSeen(found) }
                }
            }
            runCatching {
                bound.unbindAll()
                bound.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, main)
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@OptIn(TransformExperimental::class)
private fun analyze(
    image: ImageProxy,
    running: java.util.concurrent.atomic.AtomicBoolean,
    target: OutputTransform?,
    previewView: PreviewView,
    found: (Sighting) -> Unit,
) {
    try {
        if (!running.get()) return
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        // Copy the luminance plane without the row padding the camera adds.
        val data = ByteArray(width * height)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(data, row * width, width)
        }
        val hit = QrCode.detect(data, width, height) ?: return
        val corners = if (hit.corners.size != 4) null else {
            val map = target?.let { runCatching { CoordinateTransform(ImageProxyTransformFactory().getOutputTransform(image), it) }.getOrNull() }
            hit.corners.map { p ->
                if (map != null) {
                    val out = android.graphics.PointF(p.x, p.y)
                    map.mapPoint(out)
                    ScanPoint(out.x, out.y)
                } else {
                    ScanGeometry.toView(p, width, height, image.imageInfo.rotationDegrees, previewView.width.toFloat(), previewView.height.toFloat())
                }
            }
        }
        found(Sighting(hit.text, corners))
    } finally {
        image.close()
    }
}

/// iOS Camera yellow.
private val ScanYellow = Color(0xFFFFD60A)

/// The pill: what the code is, in a yellow capsule; tapping it is the only way a code is acted on.
@Composable
private fun ScanPill(pill: Pair<String, String>?, onTap: () -> Unit) {
    // Kept past `pill` going null so it can fade out with its words.
    var last by remember { mutableStateOf(pill) }
    if (pill != null) last = pill
    AnimatedVisibility(
        visible = pill != null,
        enter = fadeIn(tween(150)) + scaleIn(spring(stiffness = Spring.StiffnessMedium), initialScale = 0.8f),
        exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.9f),
    ) {
        Box(
            Modifier.padding(bottom = 12.dp).clip(CircleShape).background(ScanYellow).clickable(onClick = onTap)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(last?.second.orEmpty(), color = Color.Black, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/// Four yellow corner brackets on the code: they glide to follow it, scale in when it first
/// appears and fade out about half a second after the last frame that saw it.
@Composable
private fun ScanBrackets(sighting: Sighting?, seenCount: Int) {
    val corners = sighting?.corners
    var last by remember { mutableStateOf<List<ScanPoint>?>(null) }
    if (corners != null) last = corners
    val alpha = remember { Animatable(0f) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(seenCount, corners != null) {
        if (corners == null) return@LaunchedEffect
        if (alpha.value < 0.01f) generation += 1
        alpha.snapTo(1f)
        delay(500)
        alpha.animateTo(0f, tween(400))
    }
    val quad = last ?: return
    if (alpha.value <= 0f) return
    key(generation) {
        val scale = remember { Animatable(1.3f) }
        LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) }
        val spec = spring<Offset>(stiffness = Spring.StiffnessMedium)
        val points = quad.map { animateOffsetAsState(Offset(it.x, it.y), spec).value }
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(points.sumOf { it.x.toDouble() }.toFloat() / 4, points.sumOf { it.y.toDouble() }.toFloat() / 4)
            val scaled = points.map { centre + (it - centre) * scale.value }
            val stroke = 5.dp.toPx()
            val color = ScanYellow.copy(alpha = alpha.value)
            for (i in 0 until 4) {
                val c = scaled[i]
                val toNext = scaled[(i + 1) % 4] - c
                val toPrev = scaled[(i + 3) % 4] - c
                val arm = minOf(toNext.getDistance(), toPrev.getDistance()) * 0.22f
                val a = c + toNext / toNext.getDistance().coerceAtLeast(1f) * arm
                val b = c + toPrev / toPrev.getDistance().coerceAtLeast(1f) * arm
                val path = Path().apply { moveTo(a.x, a.y); lineTo(c.x, c.y); lineTo(b.x, b.y) }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.cornerPathEffect(stroke * 3)))
            }
        }
    }
}
