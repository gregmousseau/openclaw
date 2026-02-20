package ai.openclaw.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun QRScannerScreen(
  onResult: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var permissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      permissionGranted = granted
      if (!granted) onDismiss()
    }

  LaunchedEffect(Unit) {
    if (!permissionGranted) {
      permissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  if (!permissionGranted) return

  val lifecycleOwner = LocalLifecycleOwner.current
  val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
  var resultFired by remember { mutableStateOf(false) }

  DisposableEffect(Unit) {
    onDispose { cameraExecutor.shutdown() }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { ctx ->
        val previewView = PreviewView(ctx)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener(
          {
            val cameraProvider = cameraProviderFuture.get()
            val preview =
              Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
              }

            val barcodeScanner = BarcodeScanning.getClient()

            val analysis =
              ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                  imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImage(imageProxy, barcodeScanner) { rawValue ->
                      if (!resultFired) {
                        resultFired = true
                        onResult(rawValue)
                      }
                    }
                  }
                }

            try {
              cameraProvider.unbindAll()
              cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
              )
            } catch (e: Exception) {
              Log.e("QRScanner", "Camera bind failed", e)
            }
          },
          ContextCompat.getMainExecutor(ctx),
        )
        previewView
      },
    )

    // Scanning overlay: darkened edges with clear center
    Canvas(modifier = Modifier.fillMaxSize()) {
      val scanSize = size.minDimension * 0.6f
      val left = (size.width - scanSize) / 2f
      val top = (size.height - scanSize) / 2f
      val cutout =
        Path().apply {
          addRoundRect(
            RoundRect(
              Rect(Offset(left, top), Size(scanSize, scanSize)),
              CornerRadius(16.dp.toPx()),
            ),
          )
        }
      clipPath(cutout, clipOp = ClipOp.Difference) {
        drawRect(Color.Black.copy(alpha = 0.5f))
      }
    }

    Text(
      "Point at a QR code",
      color = Color.White,
      style = MaterialTheme.typography.titleMedium,
      modifier =
        Modifier
          .align(Alignment.Center)
          .padding(top = 280.dp),
    )

    Button(
      onClick = onDismiss,
      modifier =
        Modifier
          .align(Alignment.TopStart)
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(16.dp),
    ) {
      Text("Cancel")
    }
  }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImage(
  imageProxy: ImageProxy,
  scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
  onDetected: (String) -> Unit,
) {
  val mediaImage = imageProxy.image
  if (mediaImage == null) {
    imageProxy.close()
    return
  }
  val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
  scanner
    .process(inputImage)
    .addOnSuccessListener { barcodes ->
      for (barcode in barcodes) {
        if (barcode.format == Barcode.FORMAT_QR_CODE) {
          barcode.rawValue?.let { onDetected(it) }
          return@addOnSuccessListener
        }
      }
    }
    .addOnCompleteListener { imageProxy.close() }
}
