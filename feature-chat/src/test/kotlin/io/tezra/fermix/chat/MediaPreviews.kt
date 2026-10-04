package io.tezra.fermix.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.Sender
import io.tezra.fermix.instance.Link
import io.tezra.fermix.transport.Candidate
import java.time.LocalDate
import java.util.Locale

// The chat's attachments and voice notes at the twelve windows of @FermixPreviews, each from fixed state: the
// attach sheet with three photos picked and a caption, with an item past the limit, and with a file and a paste in
// its own tray beside numbered photos; the tray with two thumbnails under a caption, and with a document and a voice
// note; the composer recording, locked hands-free, and its unsent draft; one, two, three (one still on its way in its
// dominant colour, or one let go) and five images from the agent; the owner's image going up with its ring, and a
// video and a voice note going up with their line; the duplicate line; an image the daemon let go; documents; voice
// notes, one playing with its transcript and one transcribing; the upload interrupted, and failed; the microphone
// off; the viewer. The embedded
// Photo Picker is the system's own surface, which no test draws: the sheet's grid here is a stand-in of the same
// shape, numbered as the picker numbers what is picked, and the tile that stands where it cannot draw. The
// microphone's rationale is a dialog's window, which no preview draws; VoiceDeviceTest shows it on a device.

/** The indicator's clock in every preview. */
private const val CLOCK = 100_000L

private val CONNECTED = Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true)

private val TODAY: LocalDate = MORNING.atZone(UTC).toLocalDate()

private val DAY = ChatItem.Day("day:$TODAY", TODAY)

/** The canon's three colours, each painting starting from another. */
private val PAINT = listOf(Color(0xFF5C8BA3), Color(0xFF8C7093), Color(0xFFAD7F6B))

/** A painting of [width] × [height]: the canon's colours on the diagonal, from the [shift]th. */
private fun painting(
    width: Int,
    height: Int,
    shift: Int,
): ImageBitmap {
    val image = ImageBitmap(width, height)
    val colors = List(PAINT.size) { PAINT[(it + shift) % PAINT.size] }
    val paint =
        Paint().apply {
            shader =
                LinearGradientShader(Offset.Zero, Offset(width.toFloat(), height.toFloat()), colors)
        }
    Canvas(image).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    return image
}

/** Each image's size by its ref, its paint's start its place; a ref not here is one the daemon let go. */
private val SIZES =
    mapOf(
        "nas-1" to (400 to 300),
        "nas-2" to (300 to 400),
        "nas-3" to (300 to 300),
        "nas-4" to (400 to 300),
        "nas-5" to (300 to 400),
        "nas-6" to (400 to 300),
        "nas-7" to (300 to 300),
        "nas-8" to (400 to 225),
        "rack" to (400 to 300),
    )

/** An image whose bytes are still on their way, drawn in its dominant colour until they are in. */
private const val STREAMING = "nas-9"

/** [media] as the chat would decode it: its painting, not yet, or gone from the daemon. */
private fun drawn(media: ShownMedia): MediaImage {
    val size = SIZES[media.ref]
    return when {
        media.ref == STREAMING -> MediaImage.Missing
        size == null -> MediaImage.Gone
        else -> MediaImage.Shown(painting(size.first, size.second, SIZES.keys.indexOf(media.ref)))
    }
}

/** Each voice note's length by its cache name, as the player would read it from its file. */
private val LENGTHS = mapOf("note-1" to 14_000L, "note-2" to 6_000L)

private val MEDIA_ACTIONS =
    MediaActions(
        image = { media, _ -> drawn(media) },
        length = { LENGTHS[it.cacheName] },
    )

/** The tray's thumbnails: each picked image's painting; a file and a voice note draw none, as on the phone. */
private val ATTACH_ACTIONS =
    AttachActions(
        thumbnail = { picked ->
            if (picked.kind == PickedKind.IMAGE) painting(168, 168, picked.id.last().digitToInt()) else null
        },
    )

private val ACTIONS =
    ChatScreenActions(
        onBack = {},
        onInstance = {},
        composer =
            ComposerActions(onField = {}, onSend = {}, onStop = {}, onPalette = {}, attach = ATTACH_ACTIONS),
        onPick = {},
        onClosePalette = {},
        onError = {},
        onOutbox = { _, _ -> },
        list = ListActions(onBottom = {}, onTop = {}, onListed = {}),
        info = { ShownInfo(null, null, null, null, null, null) },
        modelOf = { it.model },
        nowMono = { CLOCK },
        text = TextActions(copy = {}, share = {}),
        media = MEDIA_ACTIONS,
    )

/** The screen over [items], newest first, with [media] and [field] in the composer. */
private fun ui(
    items: List<ChatItem>,
    media: MediaUi = MediaUi(),
    field: String = "",
): ChatUi =
    ChatUi(
        state =
            ChatScreenState(
                header = ChatHeader(sample(), CONNECTED, thinking = false),
                banner = null,
                items = items,
                name = sample().title,
                turnRuns = false,
                commands = COMMANDS,
                newestSeq = items.size.toULong(),
                unseen = 0,
                older = false,
                chosenModel = null,
                zone = UTC,
                today = TODAY,
                arrived = emptyList(),
            ),
        field = TextFieldValue(field, TextRange(field.length)),
        palette = false,
        media = media,
    )

private fun said(
    key: String,
    sender: Sender,
    text: String,
    minutes: Long,
    media: List<ShownMedia> = emptyList(),
): ChatItem.Message {
    val delivery = if (sender == Sender.User) Delivery.DELIVERED else Delivery.NONE
    return ChatItem.Message(key, ShownMessage(sender, text, wallAt(minutes), delivery, media = media))
}

private fun image(
    ref: String,
    sent: Float? = null,
): ShownMedia = ShownMedia(ref, null, MediaShape.IMAGE, "image/jpeg", 1_843_200, "$ref.jpg", sent = sent)

private fun document(
    ref: String,
    name: String,
    mime: String,
    sizeBytes: Long,
): ShownMedia = ShownMedia(ref, null, MediaShape.DOCUMENT, mime, sizeBytes, name)

private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

private fun note(ref: String): ShownMedia = ShownMedia(ref, null, MediaShape.VOICE, "audio/ogg", 22_528, null)

private val ASKED = said("r1", Sender.User, "Send me the photos from last night's backup.", minutes = 30)

private val EARLIER =
    listOf(
        said("r0b", Sender.Agent, "Done. 2,184 photos are on the NAS.", minutes = 12),
        said("r0a", Sender.User, "Back up the photos folder before you clean anything up.", minutes = 10),
        DAY,
    )

/** The owner's picks, the sheet's and the tray's: photos from the phone's gallery. */
private fun picked(count: Int): List<Picked> =
    List(count) {
        Picked(
            "p$it",
            "content://media/picker/0/$it",
            PickedKind.IMAGE,
            "image/jpeg",
            "IMG_204$it.jpg",
            2_457_600,
            PickedFrom.PHOTOS,
        )
    }

/** The level of each of the 40 bars a take sampled: a voice rising and falling. */
private val BARS = List(40) { listOf(0.32f, 0.58f, 0.86f, 0.64f, 0.44f, 0.72f, 0.5f, 0.28f)[it % 8] }

/** The chat behind the sheet: the composer holds the caption the sheet writes into it. */
private const val CAPTION = "These from the NAS, please."

@FermixPreviews
@Composable
fun AttachSheetPreview() {
    val attach = AttachUi(picked = picked(3), sheet = true, sendable = 3)
    FermixPreviewTheme { SheetOverChat(attach) }
}

@FermixPreviews
@Composable
fun AttachTooBigPreview() {
    val tooBig = TooBig("site-walkthrough.mov", 27_472_691, 20_971_520)
    val attach = AttachUi(picked = picked(2), sheet = true, tooBig = tooBig, sendable = 2)
    FermixPreviewTheme { SheetOverChat(attach) }
}

/**
 * The chat under the scrim and, over it, the sheet at 60 % with its handle, at most the sheet's 640 dp and centred,
 * as ModalBottomSheet holds it; its grid is the picker's stand-in, or [grid] in its place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetOverChat(
    attach: AttachUi,
    grid: @Composable (Modifier) -> Unit = { modifier ->
        PickerStandIn(attach.picked.count { it.from == PickedFrom.PHOTOS }, modifier)
    },
) {
    val colors = LocalFermixColors.current
    val field = TextFieldValue(CAPTION, TextRange(CAPTION.length))
    Box(modifier = Modifier.fillMaxSize()) {
        // The route's sheet is a dialog's window, which no preview draws: this one stands in for it.
        ChatScreen(ui(listOf(ASKED) + EARLIER, MediaUi(attach), CAPTION), ACTIONS)
        Box(modifier = Modifier.fillMaxSize().background(BottomSheetDefaults.ScrimColor))
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                    .fillMaxWidth()
                    .clip(FermixShapes.sheet)
                    .background(colors.tonalSolid),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BottomSheetDefaults.DragHandle()
            AttachSheetContent(attach, field, ACTIONS.composer, grid)
        }
    }
}

/** The embedded Photo Picker's stand-in: the gallery four across, the first [picked] numbered in order. */
@Composable
private fun PickerStandIn(
    picked: Int,
    modifier: Modifier,
) {
    // The grid scrolls in the picker: its rows run on below the sheet's share, which clips them.
    Column(
        modifier = modifier.clipToBounds().verticalScroll(rememberScrollState(), enabled = false),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(6) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(4) { column ->
                    val index = row * 4 + column
                    PickerTile(index, if (index < picked) index + 1 else null, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PickerTile(
    index: Int,
    number: Int?,
    modifier: Modifier,
) {
    val colors = LocalFermixColors.current
    // The system picker's badge is a fixed mark: its number does not follow the font scale.
    val density = LocalDensity.current
    val type = with(density) { FermixType.labelSmall.copy(fontSize = 11.dp.toSp(), lineHeight = 16.dp.toSp()) }
    Box(modifier = modifier.aspectRatio(1f)) {
        Image(painting(96, 96, index), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        val badge = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
        if (number == null) {
            Box(modifier = badge.border(2.dp, Color.White, CircleShape))
        } else {
            Box(modifier = badge.background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                Text("$number", style = type, color = colors.onAccent)
            }
        }
    }
}

/** A file from Files and an image pasted from the clipboard, as the tray and the sheet hold them. */
private val OTHERS =
    listOf(
        Picked("f7", "content://docs/7", PickedKind.FILE, DOCX, "wiring-plan.docx", 48_640, PickedFrom.FILES),
        Picked("c8", "file:///cache/c8", PickedKind.IMAGE, "image/png", "Screenshot.png", 311_296, PickedFrom.PASTE),
    )

@FermixPreviews
@Composable
fun AttachSheetMixedPreview() {
    // Two photos numbered in the grid; the file and the paste in the sheet's own tray, so "Send 4" counts what shows.
    val attach = AttachUi(picked = picked(2) + OTHERS, sheet = true, sendable = 4)
    FermixPreviewTheme { SheetOverChat(attach) }
}

@FermixPreviews
@Composable
fun TrayFilesPreview() {
    // A photo, a document whose extension keeps its tile at any font scale, and a voice note back from Edit.
    val voice =
        Picked("v9", "file:///cache/v9", PickedKind.VOICE, "audio/ogg", "voice-note.ogg", 22_528, PickedFrom.OUTBOX)
    val media = MediaUi(AttachUi(picked = picked(1) + OTHERS.first() + voice, sendable = 3))
    FermixPreviewTheme { ChatScreen(ui(listOf(ASKED) + EARLIER, media, CAPTION), ACTIONS) }
}

@FermixPreviews
@Composable
fun AttachPhotosTilePreview() {
    // Where the embedded Photo Picker cannot draw: the one tile that opens the system's.
    val attach = AttachUi(picked = picked(1), sheet = true, sendable = 1)
    FermixPreviewTheme { SheetOverChat(attach) { PhotosTile(attach, ATTACH_ACTIONS, it) } }
}

@FermixPreviews
@Composable
fun TrayPreview() {
    val media = MediaUi(AttachUi(picked = picked(2), sendable = 2))
    FermixPreviewTheme { ChatScreen(ui(listOf(ASKED) + EARLIER, media, CAPTION), ACTIONS) }
}

@FermixPreviews
@Composable
fun RecordingPreview() {
    val media = MediaUi(voice = VoiceUi.Recording(elapsedMs = 12_400, bars = BARS, locked = false, paused = false))
    FermixPreviewTheme { ChatScreen(ui(listOf(ASKED) + EARLIER, media), ACTIONS) }
}

@FermixPreviews
@Composable
fun RecordingLockedPreview() {
    val media = MediaUi(voice = VoiceUi.Recording(elapsedMs = 48_200, bars = BARS, locked = true, paused = false))
    FermixPreviewTheme { ChatScreen(ui(listOf(ASKED) + EARLIER, media), ACTIONS) }
}

@FermixPreviews
@Composable
fun VoiceDraftPreview() {
    val media = MediaUi(voice = VoiceUi.Draft(durationMs = 31_000, bars = BARS))
    FermixPreviewTheme { ChatScreen(ui(listOf(ASKED) + EARLIER, media), ACTIONS) }
}

@FermixPreviews
@Composable
fun MicOffPreview() {
    // The owner refused the microphone: the line over the composer, with "Open settings".
    FermixPreviewTheme { ChatScreen(ui(listOf(ASKED) + EARLIER, MediaUi(micOff = true)), ACTIONS) }
}

@FermixPreviews
@Composable
fun ImageOnePreview() {
    // One image at its own aspect, its caption inside the card.
    val one = said("r2", Sender.Agent, "The rack, as the camera saw it at 02:14.", 31, listOf(image("rack")))
    FermixPreviewTheme { ChatScreen(ui(listOf(one, ASKED) + EARLIER), ACTIONS) }
}

@FermixPreviews
@Composable
fun ImagesTwoPreview() {
    val two = said("r2", Sender.Agent, "", 31, listOf(image("nas-1"), image("nas-2")))
    FermixPreviewTheme { ChatScreen(ui(listOf(two, ASKED) + EARLIER), ACTIONS) }
}

@FermixPreviews
@Composable
fun ImagesThreePreview() {
    // One large and two stacked, the last still on its way in its dominant colour.
    val three = said("r2", Sender.Agent, "", 31, listOf(image("nas-3"), image("nas-4"), image(STREAMING)))
    val media = MediaUi(colours = mapOf(STREAMING to 0xFF6F7F8C.toInt()))
    FermixPreviewTheme { ChatScreen(ui(listOf(three, ASKED) + EARLIER, media), ACTIONS) }
}

@FermixPreviews
@Composable
fun ImagesThreeGonePreview() {
    // One large and two stacked, the last let go by the daemon: its glyph over its two lines at most.
    val three = said("r2", Sender.Agent, "", 31, listOf(image("nas-3"), image("nas-4"), image("chart")))
    FermixPreviewTheme { ChatScreen(ui(listOf(three, ASKED) + EARLIER), ACTIONS) }
}

@FermixPreviews
@Composable
fun ImagesFivePreview() {
    // The 2 × 2 at 4:3, its last cell "+1".
    val five = said("r2", Sender.Agent, "", 31, (4..8).map { image("nas-$it") })
    FermixPreviewTheme { ChatScreen(ui(listOf(five, ASKED) + EARLIER), ACTIONS) }
}

/** The owner's image in the outbox, which has no time yet, sent [sent] of the way, with [line] under it. */
private fun outgoing(
    sent: Float?,
    line: UploadLine?,
    delivery: Delivery,
): ChatItem.Message =
    ChatItem.Message(
        "out:m4",
        ShownMessage(
            Sender.User,
            "The rack's wiring, for the ticket.",
            wallMs = null,
            delivery = delivery,
            media = listOf(image("rack", sent)),
            uploadLine = line,
        ),
    )

private val REPLY = said("r2", Sender.Agent, "Which picture of the rack do you mean?", minutes = 31)

@FermixPreviews
@Composable
fun UploadRingPreview() {
    val items = listOf(outgoing(0.42f, null, Delivery.SENDING), REPLY, ASKED) + EARLIER
    FermixPreviewTheme { ChatScreen(ui(items), ACTIONS) }
}

@FermixPreviews
@Composable
fun DuplicatePreview() {
    val items = listOf(outgoing(null, UploadLine.DUPLICATE, Delivery.SENDING), REPLY, ASKED) + EARLIER
    FermixPreviewTheme { ChatScreen(ui(items), ACTIONS) }
}

@FermixPreviews
@Composable
fun UploadInterruptedPreview() {
    val items = listOf(outgoing(0.42f, UploadLine.INTERRUPTED, Delivery.PENDING), REPLY, ASKED) + EARLIER
    FermixPreviewTheme { ChatScreen(ui(items), ACTIONS) }
}

@FermixPreviews
@Composable
fun UploadFailedPreview() {
    // Past its restarts, or refused: "Not sent. Tap to retry sending."
    val items = listOf(outgoing(null, null, Delivery.FAILED), REPLY, ASKED) + EARLIER
    FermixPreviewTheme { ChatScreen(ui(items), ACTIONS) }
}

@FermixPreviews
@Composable
fun MediaGonePreview() {
    val gone = said("r2", Sender.Agent, "Last week's chart of the export's run times.", 31, listOf(image("chart")))
    FermixPreviewTheme { ChatScreen(ui(listOf(gone, ASKED) + EARLIER), ACTIONS) }
}

@FermixPreviews
@Composable
fun DocumentsPreview() {
    // The agent's report, a recording and a four-letter extension as rows with Save; the owner's video, its long
    // name cut in the middle.
    val report = document("doc-1", "export-report-2026-09-27.pdf", "application/pdf", 1_258_291)
    val audio = document("doc-2", "standup.m4a", "audio/mp4", 3_984_588)
    val plan = document("doc-4", "wiring-plan.docx", DOCX, 48_640)
    val video =
        document("doc-3", "site-walkthrough-north-wing-before-the-move-final.mp4", "video/mp4", 18_874_368)
    val sent = said("r2", Sender.User, "", 31, listOf(video))
    val words = "Here's the report, the standup it came from, and the plan."
    val reply = said("r3", Sender.Agent, words, 32, listOf(report, audio, plan))
    FermixPreviewTheme { ChatScreen(ui(listOf(reply, sent, ASKED) + EARLIER), ACTIONS) }
}

@FermixPreviews
@Composable
fun DocumentUploadingPreview() {
    // The owner's video going up, its share under its size · origin; a voice note going up, its share under its wave.
    val video =
        document("doc-3", "site-walkthrough-north-wing-before-the-move-final.mp4", "video/mp4", 18_874_368)
    val sending =
        ChatItem.Message(
            "out:m6",
            ShownMessage(Sender.User, "", null, Delivery.SENDING, media = listOf(video.copy(sent = 0.36f))),
        )
    val voice =
        ChatItem.Message(
            "out:m7",
            ShownMessage(Sender.User, "", null, Delivery.SENDING, media = listOf(note("note-2").copy(sent = 0.7f))),
        )
    val media = MediaUi(bars = mapOf("note-2" to BARS), lengths = LENGTHS)
    FermixPreviewTheme { ChatScreen(ui(listOf(voice, sending, ASKED) + EARLIER, media), ACTIONS) }
}

@FermixPreviews
@Composable
fun VoiceNotesPreview() {
    // The first note playing at 1.5× with its transcript; the second, just sent, waiting for its words.
    val first =
        said(
            "r2",
            Sender.User,
            "Check whether the export finished, then tell me how long it took.",
            31,
            listOf(note("note-1")),
        )
    val second =
        ChatItem.Message(
            "out:m5",
            ShownMessage(
                Sender.User,
                "",
                wallAt(33),
                Delivery.DELIVERED,
                media = listOf(note("note-2")),
                transcribing = true,
            ),
        )
    val reply = said("r3", Sender.Agent, "It finished at 02:16, after 84 s.", minutes = 32)
    val playing = Playing("note-1", positionMs = 5_600, durationMs = 14_000, speed = 1.5f, running = true)
    val media =
        MediaUi(playing = playing, bars = mapOf("note-1" to BARS, "note-2" to BARS.reversed()), lengths = LENGTHS)
    FermixPreviewTheme { ChatScreen(ui(listOf(second, reply, first, ASKED) + EARLIER, media), ACTIONS) }
}

@FermixPreviews
@Composable
fun ViewerPreview() {
    val items = listOf(said("r3", Sender.Agent, "", 32, listOf(image("nas-1"), image("nas-2"))), ASKED)
    val images = viewerImages(items)
    val context =
        TimelineContext(
            zone = UTC,
            locale = Locale.ROOT,
            today = TODAY,
            host = HOST,
            model = "Claude Opus 5.5",
            nowMono = { CLOCK },
            text = TextActions(copy = {}, share = {}),
            selected = emptySet(),
            menuFor = null,
            onTap = {},
            onLongPress = {},
            onError = {},
            onOutbox = { _, _ -> },
            linkUp = true,
            media = TimelineMedia(actions = MEDIA_ACTIONS),
        )
    FermixPreviewTheme { MediaViewer(images, images.first().key, context, ViewerActions(), visibility = null) }
}
