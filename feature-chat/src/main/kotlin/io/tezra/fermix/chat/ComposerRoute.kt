package io.tezra.fermix.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.textButtonColors
import io.tezra.fermix.session.MAX_ATTACHMENTS
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A tray item's thumbnail, decoded at its 56 dp on a 3× screen. */
private const val TRAY_PX = 168

private const val COMPOSER_TAG = "FermixComposer"

/**
 * The composer's media on the phone (design sections 8.5 and 13.6): the attach sheet's sources, the voice note's
 * controls, the microphone's system prompt ([onAskMic]), the camera's screen while it is open ([cameraOpen]), the
 * microphone's rationale while it shows ([rationale]), and whether the owner refused the microphone ([micOff]).
 */
internal class ComposerMedia(
    val attach: AttachActions,
    val voice: VoiceActions,
    val onAskMic: () -> Unit,
    cameraOpen: MutableState<Boolean>,
    rationale: MutableState<Boolean>,
    micOff: MutableState<Boolean>,
) {
    var cameraOpen by cameraOpen
    var rationale by rationale
    var micOff by micOff
}

/**
 * The attach sheet's sources and the voice note's microphone (design section 8.5): the mic held asks for
 * RECORD_AUDIO on its first hold, its rationale first, and records once it may. Refused, the mic is off
 * ("Microphone is off for Fermix" with "Open settings"), and a hold asks again only while the system would still
 * prompt; a resume finds it allowed again.
 */
@Composable
internal fun rememberComposerMedia(
    model: ChatViewModel,
    outside: ChatOutside,
): ComposerMedia {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val cameraOpen = rememberSaveable { mutableStateOf(false) }
    val rationale = rememberSaveable { mutableStateOf(false) }
    val micOff = rememberSaveable { mutableStateOf(false) }
    val attach = rememberAttachActions(model, outside, cameraOpen)
    val microphone =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micOff.value =
                !granted
        }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (outside.allowed(context, Manifest.permission.RECORD_AUDIO)) micOff.value = false
    }
    val voice =
        remember(model, outside, context, activity) {
            voiceActionsOf(model, outside, context) {
                val refusedForGood = micOff.value && activity?.asksAgain() == false
                if (!refusedForGood) rationale.value = true
            }
        }
    return ComposerMedia(attach, voice, {
        microphone.launch(Manifest.permission.RECORD_AUDIO)
    }, cameraOpen, rationale, micOff)
}

/**
 * The attach sheet's sources: Photos through the system Photo Picker (PickMultipleVisualMedia, at most ten) where
 * the embedded one cannot draw, Files through the documents UI (OpenMultipleDocuments), Camera, the sheet put
 * down, once the app may use it ([cameraOpen]; "Camera is off for Fermix" when refused), Paste from the
 * clipboard, the keyboard's images, the caption into the composer's field, and the tray's thumbnails.
 */
@Composable
private fun rememberAttachActions(
    model: ChatViewModel,
    outside: ChatOutside,
    cameraOpen: MutableState<Boolean>,
): AttachActions {
    val context = LocalContext.current
    val photos =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { uris ->
            model.attach.add(uris.map(Uri::toString), PickedFrom.PHOTOS)
        }
    val files =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            model.attach.add(uris.map(Uri::toString), PickedFrom.FILES)
        }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraOpen.value = true
            } else {
                Toast
                    .makeText(
                        context,
                        R.string.chat_camera_off,
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    return remember(model, outside, context) {
        AttachActions(
            onOpen = model.attach::open,
            onClose = model.attach::close,
            onPhotos = { photos.launch(PickVisualMediaRequest()) },
            onCamera = {
                // The camera's screen is the chat's own, under the sheet's window: the sheet goes down first.
                model.attach.close()
                val allowed = outside.allowed(context, Manifest.permission.CAMERA)
                if (allowed) cameraOpen.value = true else camera.launch(Manifest.permission.CAMERA)
            },
            onFiles = { files.launch(arrayOf(ANY_TYPE)) },
            onPaste = model.attach::paste,
            onPicked = { model.attach.add(it, PickedFrom.PHOTOS) },
            onUnpicked = model.attach::unpick,
            onRemove = model.attach::remove,
            onAsFiles = model.attach::sendAsFiles,
            onCaption = model.composer::caption,
            onKeyboard = { uris, grant -> model.attach.add(uris, PickedFrom.KEYBOARD, grant) },
            thumbnail = { picked -> trayThumbnail(context, picked) },
        )
    }
}

/** Any type the documents UI offers. */
private const val ANY_TYPE = "*/*"

/** Whether the system would show its microphone prompt again: refused once, not for good. */
private fun Activity.asksAgain(): Boolean = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

/**
 * The voice composer's controls: the hold records once the app may use the microphone and asks for it
 * ([onAsk]) otherwise; "Open settings" opens the app's page of the system settings.
 */
private fun voiceActionsOf(
    model: ChatViewModel,
    outside: ChatOutside,
    context: Context,
    onAsk: () -> Unit,
): VoiceActions {
    val voice = model.voice
    return VoiceActions(
        onHold = { if (outside.allowed(context, Manifest.permission.RECORD_AUDIO)) voice.start() else onAsk() },
        onLock = voice::lock,
        onRelease = model.notes::release,
        onSend = model.notes::send,
        onDiscard = model.notes::discard,
        onPause = voice::pause,
        onResume = voice::resume,
        onStop = voice::stop,
        onPlayDraft = model.notes::playDraft,
        onSettings = {
            val page =
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            outside.start(context, page)
        },
    )
}

/**
 * The rationale before the microphone's system prompt (design section 8.5): what the microphone is for, then
 * Continue to the prompt or Not now.
 */
@Composable
internal fun MicRationale(
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
) {
    val colors = LocalFermixColors.current
    AlertDialog(
        onDismissRequest = onNotNow,
        containerColor = colors.tonalSolid,
        title = {
            Text(
                stringResource(R.string.chat_mic_rationale_title),
                style = FermixType.title,
                color = colors.ink,
            )
        },
        text = {
            Text(
                stringResource(R.string.chat_mic_rationale_body),
                style = FermixType.body,
                color = colors.inkSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue, colors = textButtonColors(colors)) {
                Text(stringResource(R.string.chat_continue), style = FermixType.label)
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow, colors = textButtonColors(colors)) {
                Text(stringResource(R.string.chat_not_now), style = FermixType.label)
            }
        },
    )
}

/**
 * A picked item's thumbnail for the tray, at [TRAY_PX]: a provider's from its own thumbnail, a file the chat made
 * decoded; none for an item that draws none (a document), logged, nor for a file past the pixels the app decodes
 * (refusePastPixels), refused before a pixel is decoded. An item the chat may not read as the tray holds it
 * (readableUri) is a SecurityException: the tray holds none such.
 */
internal suspend fun trayThumbnail(
    context: Context,
    picked: Picked,
    io: CoroutineDispatcher = Dispatchers.IO,
): ImageBitmap? {
    val drawn = picked.kind == PickedKind.IMAGE || picked.kind == PickedKind.VIDEO
    if (!drawn) return null
    return withContext(io) {
        val uri = readableUri(context, picked.uri, landing = null)
        try {
            val bitmap =
                if (uri.scheme == "file") {
                    ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(File(requireNotNull(uri.path))),
                    ) { decoder, info, _ ->
                        refusePastPixels(info.size)
                        val (width, height) = cappedSize(info.size.width, info.size.height, TRAY_PX)
                        decoder.setTargetSize(width, height)
                    }
                } else {
                    context.contentResolver.loadThumbnail(uri, Size(TRAY_PX, TRAY_PX), null)
                }
            bitmap.asImageBitmap()
        } catch (unreadable: IOException) {
            Log.w(COMPOSER_TAG, "a picked item drew no thumbnail", unreadable)
            null
        }
    }
}
