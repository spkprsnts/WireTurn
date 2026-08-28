package com.wireturn.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.wireturn.app.R
import java.util.concurrent.Executors
import kotlin.math.abs

// Соответствует формату из QrCodeDialog в AppComponents.kt для длинных конфигов,
// которые не помещаются в один надёжно сканируемый QR-код.
private const val QR_CHUNK_PREFIX = "WTMQ1"

private data class QrChunk(val sessionId: String, val index: Int, val total: Int, val payload: String)

private fun parseQrChunk(raw: String): QrChunk? {
    if (!raw.startsWith("$QR_CHUNK_PREFIX|")) return null
    val parts = raw.split("|", limit = 5)
    if (parts.size != 5) return null
    val index = parts[2].toIntOrNull() ?: return null
    val total = parts[3].toIntOrNull() ?: return null
    if (total <= 0 || index !in 1..total) return null
    return QrChunk(sessionId = parts[1], index = index, total = total, payload = parts[4])
}

@Composable
fun QrScannerDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (!granted) {
                onDismiss()
            }
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        var zoom by remember { mutableFloatStateOf(0.15f) } // Начальное приближение
        var scanProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Контейнер для камеры с закругленными углами
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .clip(MaterialTheme.shapes.extraLarge)
                    ) {
                        CameraPreview(
                            zoom = zoom,
                            onZoomChange = { zoom = it },
                            onProgress = { collected, total -> scanProgress = collected to total },
                            onResult = {
                                onResult(it)
                                onDismiss()
                            }
                        )
                    }

                    scanProgress?.let { (collected, total) ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.qr_scan_progress, collected, total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Слайдер зума
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.math_minus),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = zoom,
                            onValueChange = { zoom = it },
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )
                        Text(
                            text = stringResource(R.string.math_plus),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onProgress: (collected: Int, total: Int) -> Unit = { _, _ -> },
    onResult: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val scanner = remember { BarcodeScanning.getClient() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }

    // Части многокадрового QR (см. QrCodeDialog в AppComponents.kt), собираемые
    // по мере сканирования до тех пор, пока не наберётся весь набор.
    val scannedParts = remember { mutableStateMapOf<Int, String>() }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var activeTotal by remember { mutableIntStateOf(0) }

    val onBarcodeDetected: (String) -> Unit = onBarcode@{ raw ->
        val chunk = parseQrChunk(raw)
        if (chunk == null) {
            onResult(raw)
            return@onBarcode
        }
        if (activeSessionId != chunk.sessionId) {
            activeSessionId = chunk.sessionId
            activeTotal = chunk.total
            scannedParts.clear()
        }
        scannedParts[chunk.index] = chunk.payload
        onProgress(scannedParts.size, activeTotal)
        if (scannedParts.size >= activeTotal) {
            val assembled = (1..activeTotal).joinToString("") { scannedParts[it].orEmpty() }
            onResult(assembled)
        }
    }

    // Установка начального зума 1.5x при первом бинде
    LaunchedEffect(camera) {
        camera?.let {
            it.cameraControl.setZoomRatio(1.5f)
            // Синхронизируем слайдер с реальным линейным зумом после установки 1.5x
            it.cameraInfo.zoomState.observe(lifecycleOwner) { state ->
                if (abs(state.linearZoom - zoom) > 0.05f) {
                    onZoomChange(state.linearZoom)
                }
            }
        }
    }

    LaunchedEffect(zoom, camera) {
        camera?.cameraControl?.setLinearZoom(zoom)
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(scanner, imageProxy, onBarcodeDetected)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (exc: Exception) {
                    Log.e("QrScanner", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(
    barcodeScanner: BarcodeScanner,
    imageProxy: ImageProxy,
    onResult: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let {
                        onResult(it)
                    }
                }
            }
            .addOnFailureListener {
                Log.e("QrScanner", "Barcode scanning failed", it)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
