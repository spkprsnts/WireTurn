@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.wireturn.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.wireturn.app.R
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// Длинные конфиги (например turnable:// с крупным pub_key) дают QR слишком
// плотный, чтобы его надёжно сканировала камера телефона — выше этого порога
// показываем не один код, а зацикленную анимацию из нескольких более простых кадров.
private const val QR_CHUNK_PREFIX = "WTMQ1"
private const val QR_SINGLE_FRAME_MAX_LENGTH = 700
private const val QR_CHUNK_PAYLOAD_SIZE = 400
private const val QR_FRAME_INTERVAL_MS = 450L

private fun buildQrFrames(text: String): List<String> {
    if (text.length <= QR_SINGLE_FRAME_MAX_LENGTH) return listOf(text)
    val chunks = text.chunked(QR_CHUNK_PAYLOAD_SIZE)
    val total = chunks.size
    val sessionId = (text.hashCode() and 0xFFFF).toString(16).padStart(4, '0')
    return chunks.mapIndexed { i, chunk -> "$QR_CHUNK_PREFIX|$sessionId|${i + 1}|$total|$chunk" }
}

@Composable
fun QrCodeDialog(
    text: String,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.qr_import)
) {
    val autoFrames = remember(text) { buildQrFrames(text) }
    val isChunkable = autoFrames.size > 1
    var forceStaticQr by remember(text) { mutableStateOf(false) }
    val frames = if (isChunkable && forceStaticQr) listOf(text) else autoFrames
    var frameIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(frames) {
        frameIndex = 0
        if (frames.size > 1) {
            while (true) {
                delay(QR_FRAME_INTERVAL_MS.milliseconds)
                frameIndex = (frameIndex + 1) % frames.size
            }
        }
    }

    // frames может резко сузиться (свич "Один QR-код"/смена text) раньше, чем
    // LaunchedEffect выше успеет асинхронно сбросить frameIndex — подстраховываемся.
    val safeFrameIndex = frameIndex.coerceIn(0, frames.lastIndex)
    val bitmap = remember(frames, safeFrameIndex) { generateQrCode(frames[safeFrameIndex]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        },
        title = {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (bitmap != null) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = Color.White,
                        modifier = Modifier
                            .size(260.dp)
                            .padding(8.dp)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (frames.size > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.qr_frame_progress, safeFrameIndex + 1, frames.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                if (isChunkable) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.qr_force_static_label),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.qr_force_static_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = forceStaticQr,
                            onCheckedChange = { forceStaticQr = it }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

private fun generateQrCode(text: String, size: Int = 512): Bitmap? {
    return try {
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap[x, y] =
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}
