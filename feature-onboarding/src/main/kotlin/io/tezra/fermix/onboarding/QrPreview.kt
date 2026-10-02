package io.tezra.fermix.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import zxingcpp.BarcodeReader
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

private const val TAG = "FermixScan"

/**
 * The frames zxing-cpp reads, 16:9 at 1280 × 720 or the nearest the camera has. A pairing link's QR code,
 * some 300 characters at medium error correction (`fermix pair`'s), is about 70 modules a side, so filling
 * the 240 dp reticle it spans several pixels a module; a larger frame only slows each read.
 */
private val ANALYSIS_SIZE = Size(1280, 720)

/** A bound camera's torch (design section 13.3, step 3): whether it is lit, as the camera says, and its switch. */
class CameraTorch(
    val lit: Boolean,
    val switch: (Boolean) -> Unit,
)

/**
 * The camera behind the scan's frame: whether this app may use it ([allowed]), and its [preview], which
 * draws what the camera sees, hands the text of each QR code it reads to `onRead` on the main thread, and
 * reports its torch to `onTorch` once bound, null while it has none. The phone's is [phoneCamera]; the
 * instrumented tests put a stub in its place, with no camera.
 */
class ScanCamera(
    val allowed: (Context) -> Boolean,
    val preview: @Composable (onRead: (String) -> Unit, onTorch: (CameraTorch?) -> Unit) -> Unit,
)

/** The phone's camera: the CAMERA permission as the system grants it, and [QrPreview]. */
fun phoneCamera(): ScanCamera =
    ScanCamera(
        allowed = { context ->
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        },
        preview = { onRead, onTorch -> QrPreview(onRead = onRead, onTorch = onTorch) },
    )

/**
 * How zxing-cpp reads a frame: QR codes of Model 2 alone, the model `fermix pair` draws (design section
 * 12.1), so nothing else in front of the camera reads as a code; zxing-cpp's QR_CODE is the whole family,
 * Micro QR and rMQR with their own detectors among it. Inverted ones too, as a dark terminal draws `fermix
 * pair`'s (its dark modules are full blocks in the terminal's light ink), and trying harder than the
 * binding's quick default.
 */
fun qrReaderOptions(): BarcodeReader.Options =
    BarcodeReader.Options(
        formats = setOf(BarcodeReader.Format.QR_CODE_MODEL_2),
        tryHarder = true,
        tryInvert = true,
    )

/** The scan's reader, which the analysis reads every frame with; it loads zxing-cpp's native library. */
fun qrReader(): BarcodeReader = BarcodeReader(qrReaderOptions())

/**
 * The scan's camera (design sections 13.3, step 3, and 12.1): CameraX's back camera, or the front one on a
 * device without it, full bleed, each frame read in this process by zxing-cpp on an executor of the
 * preview's own, never by Play services or anything outside the app, as the code carries the pairing's
 * secret. The text of each code goes to [onRead] on the main thread, once until another code is read, and
 * is never logged. The torch goes to [onTorch]. The camera is bound to the screen's lifecycle, and unbound,
 * with the executor shut down, when the screen leaves; a rotation or a fold draws the screen anew, which
 * binds the camera again (section 13.11, rule 3).
 */
@Composable
fun QrPreview(
    onRead: (String) -> Unit,
    onTorch: (CameraTorch?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val read by rememberUpdatedState(onRead)
    val torch by rememberUpdatedState(onTorch)
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    LaunchedEffect(context, owner) {
        runCamera(context, owner, onRequest = { request = it }, onRead = { read(it) }, onTorch = { torch(it) })
    }
    val shown = request ?: return
    CameraXViewfinder(surfaceRequest = shown, modifier = modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}

/**
 * Binds the camera to [owner] with a preview, whose surface requests go to [onRequest], and the analysis,
 * and reads codes until the caller is cancelled; then unbinds them, shuts the executor down and reports no
 * torch, however it ends.
 */
private suspend fun runCamera(
    context: Context,
    owner: LifecycleOwner,
    onRequest: (SurfaceRequest) -> Unit,
    onRead: (String) -> Unit,
    onTorch: (CameraTorch?) -> Unit,
) {
    val executor = Executors.newSingleThreadExecutor()
    // Conflated: a frame read while the main thread is busy replaces the one before it.
    val reads = Channel<String>(Channel.CONFLATED)
    val preview = Preview.Builder().build()
    preview.setSurfaceProvider { surface -> onRequest(surface) }
    val analysis = qrAnalysis()
    var provider: ProcessCameraProvider? = null
    try {
        // A frame read after the screen left finds the channel closed, and its text goes nowhere.
        analysis.setAnalyzer(executor, qrAnalyzer { reads.trySend(it) })
        val bound = ProcessCameraProvider.awaitInstance(context)
        provider = bound
        val camera = bindCamera(bound, owner, preview, analysis) ?: return
        followTorch(camera, owner, onTorch) { deliver(reads, onRead) }
    } finally {
        provider?.unbind(preview, analysis)
        analysis.clearAnalyzer()
        executor.shutdown()
        reads.close()
        onTorch(null)
    }
}

/**
 * The analysis's reading of each frame: the text of the first code [qrReader] reads in it goes to [onText],
 * on the analysis executor, and the frame is closed. The reader is made on the first frame, there, where it
 * loads zxing-cpp's native library, off the main thread.
 */
internal fun qrAnalyzer(onText: (String) -> Unit): ImageAnalysis.Analyzer {
    val reader = lazy(::qrReader)
    return ImageAnalysis.Analyzer { image ->
        val text = image.use { reader.value.read(it) }.firstNotNullOfOrNull { it.text }
        if (text != null) onText(text)
    }
}

/**
 * The analysis use case: [ANALYSIS_SIZE] frames, the latest only, so a slow read drops frames rather than
 * queueing them.
 */
private fun qrAnalysis(): ImageAnalysis {
    val resolution =
        ResolutionSelector
            .Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            ).build()
    return ImageAnalysis
        .Builder()
        .setResolutionSelector(resolution)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
}

/**
 * The back camera bound to [owner], or the front one on a device without it, or null on one with no camera
 * at all, where the scan keeps its frame and the paste is the way in (the manifest requires no camera).
 */
private fun bindCamera(
    provider: ProcessCameraProvider,
    owner: LifecycleOwner,
    preview: Preview,
    analysis: ImageAnalysis,
): Camera? {
    val selector =
        listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA).firstOrNull(provider::hasCamera)
    if (selector == null) {
        Log.w(TAG, "this device has no camera; the scan offers the paste alone")
        return null
    }
    return provider.bindToLifecycle(owner, selector, preview, analysis)
}

/**
 * Reports [camera]'s torch to [onTorch] while [block] runs: lit or not as the camera says, so a switch the
 * camera does not carry out leaves the toggle as it was, and is logged, and nothing for a camera without a
 * flash unit.
 */
private suspend fun followTorch(
    camera: Camera,
    owner: LifecycleOwner,
    onTorch: (CameraTorch?) -> Unit,
    block: suspend () -> Unit,
) {
    if (!camera.cameraInfo.hasFlashUnit()) return block()
    val switch: (Boolean) -> Unit = { on -> logIfRefused(camera.cameraControl.enableTorch(on), on) }
    val observer = Observer<Int> { state -> onTorch(CameraTorch(state == TorchState.ON, switch)) }
    camera.cameraInfo.torchState.observe(owner, observer)
    try {
        block()
    } finally {
        camera.cameraInfo.torchState.removeObserver(observer)
    }
}

/**
 * Logs the torch's switch to [on] once [switched] ends, when the camera did not carry it out, as when the
 * camera closed under it or a later switch replaced it.
 */
private fun logIfRefused(
    switched: ListenableFuture<*>,
    on: Boolean,
) {
    // Run where the switch ends: a log line needs no other thread.
    switched.addListener(
        {
            try {
                switched.get()
            } catch (refused: ExecutionException) {
                Log.w(TAG, "the camera did not turn its torch ${if (on) "on" else "off"}", refused)
            }
        },
        Runnable::run,
    )
}

/**
 * Hands the text of each code in [reads] to [onRead], once until another code's comes: the camera reads the
 * same code in every frame it is in. It runs for as long as the screen shows the camera.
 */
internal suspend fun deliver(
    reads: ReceiveChannel<String>,
    onRead: (String) -> Unit,
) {
    var last: String? = null
    for (text in reads) {
        if (text != last) onRead(text)
        last = text
    }
}
