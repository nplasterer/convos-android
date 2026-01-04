package com.naomiplasterer.convos.ui.newconversation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.naomiplasterer.convos.ui.theme.Spacing
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "QRScanner"

@Composable
fun QRScanner(
    onQRCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier
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
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        when {
            hasCameraPermission -> {
                CameraPreviewWithScanner(
                    onQRCodeScanned = onQRCodeScanned,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                PermissionDeniedContent(
                    onRequestPermission = {
                        launcher.launch(Manifest.permission.CAMERA)
                    }
                )
            }
        }
    }
}

@Composable
private fun CameraPreviewWithScanner(
    onQRCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val previewView = remember { PreviewView(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    var hasScanned by remember { mutableStateOf(false) }
    var showSuccessFeedback by remember { mutableStateOf(false) }
    var scanAttempts by remember { mutableStateOf(0) }
    var lastScanTime by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            // Properly unbind camera use cases before disposing
            try {
                cameraProviderFuture.get()?.unbindAll()
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera", e)
            }
            cameraExecutor.shutdown()
            barcodeScanner.close()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        ) { view ->
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(view.surfaceProvider)
                    }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(
                                barcodeScanner = barcodeScanner,
                                imageProxy = imageProxy,
                                hasScanned = hasScanned,
                                onQRCodeDetected = { qrCode ->
                                    val currentTime = System.currentTimeMillis()
                                    // Prevent duplicate scans within 2 seconds
                                    if (!hasScanned && currentTime - lastScanTime > 2000) {
                                        hasScanned = true
                                        lastScanTime = currentTime
                                        showSuccessFeedback = true
                                        scanAttempts++

                                        // Haptic feedback
                                        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                        } else {
                                            @Suppress("DEPRECATION")
                                            vibrator?.vibrate(100)
                                        }

                                        Log.d(TAG, "QR code successfully scanned (attempt #$scanAttempts), showing feedback")
                                        // Add a small delay before calling the callback to ensure UI updates first
                                        kotlinx.coroutines.MainScope().launch {
                                            kotlinx.coroutines.delay(200)
                                            onQRCodeScanned(qrCode)
                                        }
                                    }
                                }
                            )
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }

        // QR code frame overlay with success indication
        QRScannerOverlay(
            modifier = Modifier.fillMaxSize(),
            showSuccess = showSuccessFeedback
        )

        // Instructions or Success message
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(Spacing.step6x),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.step2x)
        ) {
            Surface(
                color = if (showSuccessFeedback) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = if (showSuccessFeedback) {
                        "✓ QR Code Scanned Successfully!"
                    } else {
                        "Position QR code in the frame"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (showSuccessFeedback) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(
                        horizontal = Spacing.step4x,
                        vertical = Spacing.step3x
                    )
                )
            }
        }
    }
}

@Composable
private fun QRScannerOverlay(
    modifier: Modifier = Modifier,
    showSuccess: Boolean = false
) {
    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // QR code scan area (square in center)
        val scanSize = minOf(canvasWidth, canvasHeight) * 0.65f
        val left = (canvasWidth - scanSize) / 2
        val top = (canvasHeight - scanSize) / 2

        // Semi-transparent overlay with clear center
        drawRect(
            color = Color.Black.copy(alpha = 0.5f),
            size = Size(canvasWidth, top)
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.5f),
            topLeft = Offset(0f, top + scanSize),
            size = Size(canvasWidth, canvasHeight - top - scanSize)
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.5f),
            topLeft = Offset(0f, top),
            size = Size(left, scanSize)
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.5f),
            topLeft = Offset(left + scanSize, top),
            size = Size(canvasWidth - left - scanSize, scanSize)
        )

        // Corner indicators - change color based on success
        val cornerLength = 40f
        val cornerWidth = 4f
        val cornerColor = if (showSuccess) Color.Green else Color.White

        // Top-left
        drawLine(
            color = cornerColor,
            start = Offset(left, top),
            end = Offset(left + cornerLength, top),
            strokeWidth = cornerWidth
        )
        drawLine(
            color = cornerColor,
            start = Offset(left, top),
            end = Offset(left, top + cornerLength),
            strokeWidth = cornerWidth
        )

        // Top-right
        drawLine(
            color = cornerColor,
            start = Offset(left + scanSize, top),
            end = Offset(left + scanSize - cornerLength, top),
            strokeWidth = cornerWidth
        )
        drawLine(
            color = cornerColor,
            start = Offset(left + scanSize, top),
            end = Offset(left + scanSize, top + cornerLength),
            strokeWidth = cornerWidth
        )

        // Bottom-left
        drawLine(
            color = cornerColor,
            start = Offset(left, top + scanSize),
            end = Offset(left + cornerLength, top + scanSize),
            strokeWidth = cornerWidth
        )
        drawLine(
            color = cornerColor,
            start = Offset(left, top + scanSize),
            end = Offset(left, top + scanSize - cornerLength),
            strokeWidth = cornerWidth
        )

        // Bottom-right
        drawLine(
            color = cornerColor,
            start = Offset(left + scanSize, top + scanSize),
            end = Offset(left + scanSize - cornerLength, top + scanSize),
            strokeWidth = cornerWidth
        )
        drawLine(
            color = cornerColor,
            start = Offset(left + scanSize, top + scanSize),
            end = Offset(left + scanSize, top + scanSize - cornerLength),
            strokeWidth = cornerWidth
        )
    }
}

@Composable
private fun PermissionDeniedContent(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(Spacing.step6x),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.step6x)
    ) {
        Text(
            text = "Camera Access Required",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Please grant camera permission to scan QR codes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Button(
            onClick = onRequestPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant Permission")
        }
    }
}

@androidx.camera.core.ExperimentalGetImage
private fun processImageProxy(
    barcodeScanner: BarcodeScanner,
    imageProxy: ImageProxy,
    hasScanned: Boolean,
    onQRCodeDetected: (String) -> Unit
) {
    try {
        if (hasScanned) {
            return
        }

        imageProxy.image?.let { image ->
            val inputImage = InputImage.fromMediaImage(
                image,
                imageProxy.imageInfo.rotationDegrees
            )

            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    // Process only the first barcode
                    barcodes.firstOrNull()?.rawValue?.let { qrCode ->
                        Log.d(TAG, "QR Code detected: $qrCode")
                        onQRCodeDetected(qrCode)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Barcode scanning failed", exception)
                }
                .addOnCompleteListener {
                    // Always close the image proxy when done
                    try {
                        imageProxy.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing imageProxy", e)
                    }
                }
        } ?: imageProxy.close()
    } catch (e: Exception) {
        Log.e(TAG, "Error processing image proxy", e)
        try {
            imageProxy.close()
        } catch (closeError: Exception) {
            Log.e(TAG, "Error closing imageProxy after exception", closeError)
        }
    }
}
