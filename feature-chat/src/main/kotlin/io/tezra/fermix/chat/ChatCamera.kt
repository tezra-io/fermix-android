package io.tezra.fermix.chat

import android.content.Context
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import java.io.File
import java.io.IOException

private const val CAMERA_TAG = "FermixCamera"

/**
 * The attach sheet's Camera (design section 8.5, "CameraX capture"): the back camera full bleed, ✕ to close and
 * the shutter; a photo goes to [onTaken] as a file under the cache, which the tray holds and the chat deletes as
 * it leaves. The camera is bound to the screen's lifecycle and unbound however it leaves; a phone with no camera
 * shows the frame and its ✕.
 */
@Composable
internal fun CameraCapture(
    onTaken: (File) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val taken by rememberUpdatedState(onTaken)
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    LaunchedEffect(context, owner) { runCapture(context, owner, capture) { request = it } }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        request?.let {
            CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        CloseButton(onClose, Modifier.align(Alignment.TopStart))
        Shutter(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
        ) { shoot(context, capture) { taken(it) } }
    }
}

/** Binds a preview, whose surface requests go to [onRequest], and [capture] to [owner] until cancelled. */
private suspend fun runCapture(
    context: Context,
    owner: LifecycleOwner,
    capture: ImageCapture,
    onRequest: (SurfaceRequest) -> Unit,
) {
    val preview = Preview.Builder().build()
    preview.setSurfaceProvider { onRequest(it) }
    var provider: ProcessCameraProvider? = null
    try {
        val bound = ProcessCameraProvider.awaitInstance(context)
        provider = bound
        val selector =
            listOf(
                CameraSelector.DEFAULT_BACK_CAMERA,
                CameraSelector.DEFAULT_FRONT_CAMERA,
            ).firstOrNull(bound::hasCamera)
        if (selector == null) {
            Log.w(CAMERA_TAG, "this device has no camera to take a photo with")
            return
        }
        bound.bindToLifecycle(owner, selector, preview, capture)
        awaitCancellation()
    } finally {
        provider?.unbind(preview, capture)
    }
}

/** Takes a photo into a new file under the cache: [onSaved] with it on the main thread, or the failure logged. */
private fun shoot(
    context: Context,
    capture: ImageCapture,
    onSaved: (File) -> Unit,
) {
    val file =
        try {
            File.createTempFile("camera", ".jpg", context.cacheDir)
        } catch (full: IOException) {
            Log.w(CAMERA_TAG, "no file for the photo", full)
            return
        }
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    capture.takePicture(
        options,
        context.mainExecutor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onSaved(file)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(CAMERA_TAG, "the photo was not taken", exception)
                file.delete()
            }
        },
    )
}

/** ✕, white on the frame. */
@Composable
private fun CloseButton(
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val label = stringResource(R.string.chat_close)
    Box(
        modifier = modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_chat_x), label, tint = Color.White)
    }
}

/** The shutter: a white ring around a white disc, 72 dp. */
@Composable
private fun Shutter(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.chat_take_photo)
    Box(
        modifier =
            modifier
                .size(72.dp)
                .border(4.dp, Color.White, CircleShape)
                .padding(8.dp)
                .background(Color.White, CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
    )
}
