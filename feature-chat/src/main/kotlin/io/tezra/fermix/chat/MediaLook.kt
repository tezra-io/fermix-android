package io.tezra.fermix.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import java.util.Locale

/** Bytes in a kilobyte and a megabyte as the size line counts them, binary, as the limit's 20 MB is 20 MiB. */
private const val KILOBYTE = 1_024L
private const val MEGABYTE = KILOBYTE * KILOBYTE

/** Tenths in one: the size line's one decimal. */
private const val TENTHS = 10L

/** The most letters of an extension a document's tile shows. */
private const val EXTENSION_CHARS = 4

/**
 * What the chat's attachments and voice notes show (design sections 8.5, 13.5 and 13.6): the sheet and tray
 * ([attach]), the voice composer ([voice]), the note the player holds, each streaming blob's placeholder colour,
 * the bars and lengths of the notes it knows, recorded here or read from their files, by cache name, and whether
 * the microphone is off for the app ([micOff]).
 */
data class MediaUi(
    val attach: AttachUi = AttachUi(),
    val voice: VoiceUi = VoiceUi.Idle,
    val playing: Playing? = null,
    val colours: Map<String, Int> = emptyMap(),
    val bars: Map<String, List<Float>> = emptyMap(),
    val lengths: Map<String, Long> = emptyMap(),
    val micOff: Boolean = false,
)

/** An image as a bubble or the viewer draws it: decoded, gone from the daemon, or not to be had now. */
sealed interface MediaImage {
    class Shown(
        val bitmap: ImageBitmap,
    ) : MediaImage

    data object Gone : MediaImage

    data object Missing : MediaImage
}

/**
 * What the timeline's blobs do (design sections 13.5 and 13.7): an image decoded at most its long edge in pixels
 * ([image]); a document opened through the chooser once it is downloaded ([onOpen]), shared or saved; a voice note's
 * length read from its file ([length], none when it cannot be had now), the note played or paused ([onPlay]) and
 * the speed chip stepped ([onSpeed]).
 */
data class MediaActions(
    val image: suspend (ShownMedia, Int) -> MediaImage = { _, _ -> MediaImage.Missing },
    val length: suspend (ShownMedia) -> Long? = { null },
    val onOpen: (ShownMedia) -> Unit = {},
    val onShare: (ShownMedia) -> Unit = {},
    val onSave: (ShownMedia) -> Unit = {},
    val onPlay: (ShownMedia) -> Unit = {},
    val onSpeed: () -> Unit = {},
)

/**
 * What the composer's attachments do (design section 13.6): the sheet's +, its chips and its picker tile, the
 * embedded Photo Picker's picks and un-picks by URI, the tray's ✕, "Send as files", the caption, which is the
 * composer's words, an image the keyboard committed, and a picked item's [thumbnail] for the tray.
 */
data class AttachActions(
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onPhotos: () -> Unit = {},
    val onCamera: () -> Unit = {},
    val onFiles: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onPicked: (List<String>) -> Unit = {},
    val onUnpicked: (List<String>) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onAsFiles: (Boolean) -> Unit = {},
    val onCaption: (TextFieldValue) -> Unit = {},
    val onKeyboard: (uris: List<String>, grant: Any) -> Unit = { _, _ -> },
    val thumbnail: suspend (Picked) -> ImageBitmap? = { null },
)

/**
 * The voice composer's controls (design sections 8.5 and 13.6): the mic held ([onHold], which asks for the
 * microphone first when the app may not use it), slid up ([onLock]), let go ([onRelease], which sends only the
 * recording the hold made), the locked send and the draft's ([onSend]), each send with what to do once the session
 * took it, where the composer plays its haptic; slid left or the draft's trash ([onDiscard]); locked, pause, resume
 * and stop, which a hold the phone took from the finger comes to as well; the draft's play; and "Open settings"
 * while the microphone is off.
 */
data class VoiceActions(
    val onHold: () -> Unit = {},
    val onLock: () -> Unit = {},
    val onRelease: (onTaken: () -> Unit) -> Unit = {},
    val onSend: (onTaken: () -> Unit) -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onPlayDraft: () -> Unit = {},
    val onSettings: () -> Unit = {},
)

/** A size as the sheet's line and a document's row say it: "512 B", "84 KB", "18.4 MB", "20 MB". */
@Composable
internal fun sizeText(
    bytes: Long,
    locale: Locale,
): String {
    val tenths = (bytes * TENTHS + MEGABYTE / 2) / MEGABYTE
    val whole = tenths % TENTHS == 0L
    val megabytes = if (whole) "${tenths / TENTHS}" else String.format(locale, "%.1f", tenths / TENTHS.toDouble())
    return when {
        bytes < KILOBYTE -> stringResource(R.string.chat_size_bytes, bytes)
        bytes < MEGABYTE -> stringResource(R.string.chat_size_kilobytes, bytes / KILOBYTE)
        else -> stringResource(R.string.chat_size_megabytes, megabytes)
    }
}

/** A document's extension as its tile shows it, "PDF", at most four letters; none for a name with none. */
fun extensionOf(name: String?): String? {
    val extension = name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it != name }
    return extension?.take(EXTENSION_CHARS)?.uppercase(Locale.ROOT)
}
