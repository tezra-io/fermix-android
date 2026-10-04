package io.tezra.fermix.chat

import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityOptionsCompat
import java.io.File
import java.util.Collections

/** The bytes the fake camera's photo holds: a JPEG's first marker, enough for a file the tray lists. */
private val PHOTO = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

/**
 * The system's activities the chat launches for a result, as the tests answer them, at once: the Photo Picker
 * with [photos], the documents UI with [documents], a permission prompt with [granted]. Every launch is kept in
 * [launched] by its contract's name; any other launch fails the test.
 */
internal class PickerRegistry : ActivityResultRegistry() {
    @Volatile var photos: List<Uri> = emptyList()

    @Volatile var documents: List<Uri> = emptyList()

    @Volatile var granted = true

    val launched: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        val result: Any =
            when (contract) {
                is ActivityResultContracts.PickMultipleVisualMedia -> photos
                is ActivityResultContracts.OpenMultipleDocuments -> documents
                is ActivityResultContracts.RequestPermission -> granted
                else -> throw AssertionError("a launch the chat never makes: $contract")
            }
        launched += contract.javaClass.simpleName
        check(dispatchResult(requestCode, result)) { "no launcher waited for ${contract.javaClass.simpleName}" }
    }
}

/** The camera's screen as the tests stand in for it: "Take photo" hands a new file under the cache to [onTaken]. */
@Composable
internal fun FakeCamera(
    onTaken: (File) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Button(onClick = {
            val photo = File.createTempFile("camera", ".jpg", context.cacheDir)
            photo.writeBytes(PHOTO)
            onTaken(photo)
        }) { Text(stringResource(R.string.chat_take_photo)) }
        Button(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
            Text(stringResource(R.string.chat_close))
        }
    }
}
