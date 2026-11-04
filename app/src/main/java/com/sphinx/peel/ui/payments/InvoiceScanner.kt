package com.sphinx.peel.ui.payments

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import java.util.concurrent.atomic.AtomicBoolean

/// The camera, looking for a QR code that holds an invoice (the iPhone's
/// `InvoiceScanner`). Only a BOLT 11 invoice is taken: anything else it reads
/// is untrusted text and is passed over with a word. Paste is the other way
/// in. The camera permission is asked when this opens (`CAMERA` is declared
/// in the manifest already, for calls); refused, it says so and points at paste.
@Composable
fun InvoiceScanner(onFound: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        refused = !it
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    var note by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview { text ->
                when (val input = PaymentInput.parse(text)) {
                    is PaymentInput.Bolt11 -> { onFound(input.bolt11); true }
                    is PaymentInput.Unsupported -> { note = "That's ${input.kind}, which Peel can't pay. Scan an invoice."; false }
                    PaymentInput.Unknown -> { note = "That code isn't an invoice."; false }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when {
                    refused -> "Peel can't use the camera. Allow it in Settings, or paste the invoice."
                    !granted -> "Waiting for camera access…"
                    else -> note ?: "Point the camera at an invoice's QR code."
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(12.dp),
            )
            TextButton(onClick = onCancel) { Text("Cancel", color = Theme.colors.accent) }
        }
    }
}

/// CameraX's back camera into a `PreviewView`, every frame's luminance
/// plane offered to ZXing; `accept` returns true once it took a code (the
/// analysis then stops, so one scan is one result).
@Composable
private fun CameraPreview(accept: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val latestAccept by androidx.compose.runtime.rememberUpdatedState(accept)
    val previewView = remember { PreviewView(context) }

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
            analysis.setAnalyzer(executor) { image -> analyze(image, done) { text ->
                // Back on the main thread: `accept` touches Compose state.
                ContextCompat.getMainExecutor(context).execute { if (!done.get() && latestAccept(text)) done.set(true) }
            } }
            runCatching {
                bound.unbindAll()
                bound.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun analyze(image: ImageProxy, done: AtomicBoolean, found: (String) -> Unit) {
    try {
        if (done.get()) return
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
        QrCode.decode(data, width, height)?.let(found)
    } finally {
        image.close()
    }
}
