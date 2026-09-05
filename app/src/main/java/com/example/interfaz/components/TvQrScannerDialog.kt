package com.example.interfaz.components

import android.graphics.ImageFormat
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                com.google.zxing.DecodeHintType.TRY_HARDER to true
            )
        )
    }

    private var isScanned = false

    override fun analyze(image: ImageProxy) {
        if (isScanned) {
            image.close()
            return
        }

        try {
            val planes = image.planes
            if (planes.isNotEmpty()) {
                val plane = planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val width = image.width
                val height = image.height

                val data = if (rowStride == width && pixelStride == 1) {
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    bytes
                } else {
                    val bytes = ByteArray(width * height)
                    var offset = 0
                    val row = ByteArray(rowStride)
                    for (i in 0 until height) {
                        buffer.position(i * rowStride)
                        if (pixelStride == 1) {
                            val toRead = minOf(width, buffer.remaining())
                            buffer.get(bytes, offset, toRead)
                            offset += width
                        } else {
                            val toRead = minOf(rowStride, buffer.remaining())
                            buffer.get(row, 0, toRead)
                            for (j in 0 until width) {
                                bytes[offset++] = row[j * pixelStride]
                            }
                        }
                    }
                    bytes
                }

                val source = PlanarYUVLuminanceSource(
                    data,
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    false
                )

                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val result = reader.decodeWithState(binaryBitmap)
                if (result != null && !result.text.isNullOrBlank()) {
                    isScanned = true
                    onQrCodeScanned(result.text)
                }
            }
        } catch (_: Throwable) {
            // Frame did not contain a readable QR code
        } finally {
            reader.reset()
            image.close()
        }
    }
}

@Composable
fun TvQrScannerDialog(
    lang: String,
    accentColor: Color,
    onDismiss: () -> Unit,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top bar: title and close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, start = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (lang == "es") "Escanear QR de la TV" else "Scan TV QR Code",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    TvIconButton(
                        onClick = onDismiss,
                        containerColor = Color.White.copy(alpha = 0.15f),
                        shape = CircleShape,
                        modifier = Modifier.size(36.dp)
                    ) { isFocused ->
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Middle: Camera Preview with Viewfinder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            val executor = ContextCompat.getMainExecutor(ctx)

                            cameraProviderFuture.addListener({
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                        .also {
                                            it.setAnalyzer(executor, QrCodeAnalyzer { scannedText ->
                                                onQrScanned(scannedText)
                                            })
                                        }

                                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                                    cameraProvider.unbindAll()
                                    val camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageAnalysis
                                    )
                                    cameraControl = camera.cameraControl
                                } catch (e: Exception) {
                                    Log.e("TV_SYNC_QR", "Error starting CameraX: ${e.message}", e)
                                }
                            }, executor)

                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Viewfinder overlay
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 5.dp.toPx()
                        val cornerLength = 32.dp.toPx()
                        val margin = 20.dp.toPx()
                        val rectSize = size.width - (margin * 2)

                        // Top Left
                        drawLine(
                            color = accentColor,
                            start = Offset(margin, margin),
                            end = Offset(margin + cornerLength, margin),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = accentColor,
                            start = Offset(margin, margin),
                            end = Offset(margin, margin + cornerLength),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )

                        // Top Right
                        drawLine(
                            color = accentColor,
                            start = Offset(margin + rectSize, margin),
                            end = Offset(margin + rectSize - cornerLength, margin),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = accentColor,
                            start = Offset(margin + rectSize, margin),
                            end = Offset(margin + rectSize, margin + cornerLength),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )

                        // Bottom Left
                        drawLine(
                            color = accentColor,
                            start = Offset(margin, margin + rectSize),
                            end = Offset(margin + cornerLength, margin + rectSize),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = accentColor,
                            start = Offset(margin, margin + rectSize),
                            end = Offset(margin, margin + rectSize - cornerLength),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )

                        // Bottom Right
                        drawLine(
                            color = accentColor,
                            start = Offset(margin + rectSize, margin + rectSize),
                            end = Offset(margin + rectSize - cornerLength, margin + rectSize),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = accentColor,
                            start = Offset(margin + rectSize, margin + rectSize),
                            end = Offset(margin + rectSize, margin + rectSize - cornerLength),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }

                // Bottom instructions and flash toggle
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Text(
                        text = if (lang == "es") "Apunta con la cámara al código QR de la Smart TV." else "Point the camera at the Smart TV QR code.",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (lang == "es") "Las listas se transferirán automáticamente al detectarlo." else "Playlists will transfer automatically upon detection.",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    TvIconButton(
                        onClick = {
                            isFlashOn = !isFlashOn
                            cameraControl?.enableTorch(isFlashOn)
                        },
                        containerColor = if (isFlashOn) accentColor else Color.White.copy(alpha = 0.2f),
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp)
                    ) { isFocused ->
                        Icon(
                            imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Flash",
                            tint = if (isFocused) TV_FOCUSED_TEXT else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
