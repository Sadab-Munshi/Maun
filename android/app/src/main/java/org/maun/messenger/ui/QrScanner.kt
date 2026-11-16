package org.maun.messenger.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import org.maun.messenger.crypto.QrCodec

/**
 * Live camera view that reports the text of any QR code it sees.
 *
 * Frames are examined on this phone and then discarded; nothing is saved or
 * sent. [onCode] may be called many times for the same code. [onError] is
 * called once if the camera cannot be started. The caller must already hold
 * the camera permission.
 */
@Composable
fun QrScanner(onCode: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnError by rememberUpdatedState(onError)
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analysisThread = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisThread.shutdown() }
    }

    LaunchedEffect(lifecycleOwner) {
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentOnError("The camera could not be started.")
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val analysis = ImageAnalysis.Builder()
            // Reading a frame is slower than the camera produces them: skip to the newest.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val mainThread = ContextCompat.getMainExecutor(context)
        analysis.setAnalyzer(analysisThread, QrAnalyzer { text -> mainThread.execute { currentOnCode(text) } })
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        } catch (e: IllegalArgumentException) {
            // No back camera on this device.
            currentOnError("This device has no camera Maun can use.")
            return@LaunchedEffect
        } catch (e: IllegalStateException) {
            currentOnError("The camera could not be started.")
            return@LaunchedEffect
        } catch (_: SecurityException) {
            currentOnError("Camera access is turned off in Android settings.")
            return@LaunchedEffect
        }
        try {
            // Keep the camera until the scanner leaves the screen.
            awaitCancellation()
        } finally {
            provider.unbindAll()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Hands the brightness plane of each camera frame to the QR reader. */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private var luma = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        image.use {
            // Plane 0 of the camera's YUV format is plain brightness, one byte per pixel.
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val size = buffer.remaining()
            if (luma.size < size) luma = ByteArray(size)
            buffer.get(luma, 0, size)
            if (size < plane.rowStride * (image.height - 1) + image.width) return
            QrCodec.decode(luma, plane.rowStride, image.width, image.height)?.let(onText)
        }
    }
}
