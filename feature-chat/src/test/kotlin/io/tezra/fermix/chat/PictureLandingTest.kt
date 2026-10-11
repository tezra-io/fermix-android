package io.tezra.fermix.chat

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.Sender
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset.UTC
import java.util.Locale
import kotlin.math.abs

private const val FRAME = "frame"
private const val ASK = "Can you sketch a boat"
private const val TURN = "turn-m9"

/** The heading of the card that opens below the picture, which nothing else in the list says. */
private const val NEXT_HEADING = "Writing it up"

/** The picture: tall, as a generated one may be, and red, which nothing else in the list is. */
private const val PICTURE_WIDTH = 600
private const val PICTURE_HEIGHT = 800
private const val RED = 0xFFFF0000.toInt()

/** How much redder than green and blue a pixel is, at least, to be the picture's, faded or whole. */
private const val REDDER = 40

/** How far from the canvas a pixel is at least, its three channels' distances summed, to be something drawn. */
private const val DRAWN_DISTANCE = 60

/** How many pixels drawn, at least, are something of the card or the picture: a few glyphs' worth. */
private const val DRAWN_AT_LEAST = 200

/**
 * How many rows, at most, lie blank between the request's foot and what is drawn below it: the gap between groups
 * and the card's own margin, 48 dp at xhdpi, never a band the picture has yet to grow into.
 */
private const val BLANK_AT_MOST = 96

/** Each channel of a pixel of the card's heading in its ink, at least half faded in, is under this. */
private const val INK_LEVEL = 160

/** The list's foot: its 12 dp content padding at xhdpi, where the card below a picture waits to be scrolled to. */
private const val LIST_FOOT_PX = 24

/** The clock's step through the morph, a frame's. */
private const val STEP_MILLIS = 16L

/** When the card that opens below a picture is to be seen fading in, well inside the morph's 300 ms. */
private const val SEEN_BY_MILLIS = 160L

/**
 * A turn's picture taking its card's place (the owner, 2026-10-10; Task 23's reviews): the list morphs the card's
 * bounds into the picture's, and the picture grows inside them, never drawn over the owner's request above it at any
 * frame of the morph, nor left below it across a blank band; the picture fades in as the card's words fade out, so
 * something of one or the other is drawn on every frame and the list never goes blank; and the card that opens below
 * the picture fades in with the morph, never after it. Held frame by frame on the test's clock, on the light canvas,
 * the list in a SharedTransitionLayout as the chat's screen lays it out (ChatScreen), whose lookahead places its rows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class PictureLandingTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val canvas = FermixColors.Light.canvas
    private var items by mutableStateOf(listOf(card("card:$TURN:1"), asked()))
    private lateinit var list: LazyListState

    @Test
    fun `a picture grows in its card's bounds, under the request, the card or the picture drawn on every frame`() {
        show()
        items = listOf(nextCard("card:$TURN:1.1"), picture("card:$TURN:1"), asked())
        Snapshot.sendApplyNotifications()
        val morph = FermixMotion.THINKING_TO_ANSWER_MILLIS.toLong()
        var at = 0L
        while (at <= morph + STEP_MILLIS) {
            rule.mainClock.advanceTimeBy(STEP_MILLIS)
            at += STEP_MILLIS
            val frame = frame()
            val askBottom = boundsOf(ASK).bottom.toInt()
            val top = redTop(frame)
            val under = top < 0 || top >= askBottom
            assertTrue("at $at ms the picture's top $top is over the request, whose foot is $askBottom", under)
            val drawn = drawnIn(frame, askBottom, frame.height - LIST_FOOT_PX)
            assertTrue("at $at ms only $drawn pixels of the card or the picture are drawn", drawn >= DRAWN_AT_LEAST)
            val blank = blankBelow(frame, askBottom)
            assertTrue("at $at ms $blank rows lie blank under the request", blank <= BLANK_AT_MOST)
        }
        assertTrue("the picture shows once the morph is done", redTop(frame()) >= 0)
    }

    @Test
    fun `the card that opens below a landing picture fades in with the morph, not after it`() {
        show()
        items = listOf(nextCard("card:$TURN:1.1"), picture("card:$TURN:1"), asked())
        list.requestScrollToItem(0)
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(SEEN_BY_MILLIS)
        val inked = inkIn(frame(), boundsOf(NEXT_HEADING))
        assertTrue("$SEEN_BY_MILLIS ms into the landing the card below shows $inked pixels of ink", inked > 0)
    }

    @Test
    fun `a card shown again at a picture's slot, with nothing landing, fades in at the list's own pace`() {
        items = listOf(picture("card:$TURN:1"), asked())
        show()
        items = listOf(nextCard("card:$TURN:1.1"), picture("card:$TURN:1"), asked())
        list.requestScrollToItem(0)
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(SEEN_BY_MILLIS)
        val inked = inkIn(frame(), boundsOf(NEXT_HEADING))
        assertTrue("$SEEN_BY_MILLIS ms after it came back the card shows $inked pixels of ink", inked > 0)
    }

    /**
     * The list of [items] drawn, in a SharedTransitionLayout as the chat's screen draws it, animations at their normal
     * pace, its clock the test's, held for half a second.
     */
    @OptIn(ExperimentalSharedTransitionApi::class)
    private fun show() {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FermixTheme {
                SharedTransitionLayout(Modifier.testTag(FRAME).fillMaxSize().background(canvas)) {
                    list = rememberLazyListState()
                    Timeline(items, context { rule.mainClock.currentTime }, list)
                }
            }
        }
        rule.mainClock.advanceTimeBy(500L)
    }

    private fun frame(): Bitmap = rule.onNodeWithTag(FRAME).captureToImage().asAndroidBitmap()

    private fun boundsOf(text: String): Rect = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    /** How many pixels of [image]'s rows [from] to [until] stand off the canvas: something drawn there. */
    private fun drawnIn(
        image: Bitmap,
        from: Int,
        until: Int,
    ): Int {
        val row = IntArray(image.width)
        val plain = canvas.toArgb()
        var drawn = 0
        for (y in from.coerceAtLeast(0) until until.coerceAtMost(image.height)) {
            image.getPixels(row, 0, image.width, 0, y, image.width, 1)
            drawn += row.count { distance(it, plain) >= DRAWN_DISTANCE }
        }
        return drawn
    }

    /** How many rows of [image] from [from] down lie blank, nothing drawn on them, before the first that has some. */
    private fun blankBelow(
        image: Bitmap,
        from: Int,
    ): Int {
        val first = (from.coerceAtLeast(0) until image.height).firstOrNull { drawnIn(image, it, it + 1) > 0 }
        return (first ?: image.height) - from
    }

    /** The list's context, its monotonic clock [nowMono], its images [redPicture]. */
    private fun context(nowMono: () -> Long) =
        TimelineContext(
            zone = UTC,
            locale = Locale.US,
            today = MORNING.atZone(UTC).toLocalDate(),
            host = HOST,
            model = "",
            nowMono = nowMono,
            text = TextActions({}, {}),
            selected = emptySet(),
            menuFor = null,
            onTap = {},
            onLongPress = {},
            onError = {},
            onOutbox = { _, _ -> },
            linkUp = true,
            media = TimelineMedia(actions = MediaActions(image = { _, _ -> MediaImage.Shown(redPicture()) })),
        )
}

/** The first row of [image], from the top, with a pixel of the picture's red in it; -1 for none. */
private fun redTop(image: Bitmap): Int {
    val row = IntArray(image.width)
    for (y in 0 until image.height) {
        image.getPixels(row, 0, image.width, 0, y, image.width, 1)
        if (row.any(::isRed)) return y
    }
    return -1
}

private fun isRed(pixel: Int): Boolean {
    val red = pixel shr 16 and 0xFF
    return red - (pixel shr 8 and 0xFF) > REDDER && red - (pixel and 0xFF) > REDDER
}

/** How many pixels of [image] within [bounds] are ink: each of their channels dark. */
private fun inkIn(
    image: Bitmap,
    bounds: Rect,
): Int {
    val left = bounds.left.toInt().coerceAtLeast(0)
    val width = bounds.right.toInt().coerceAtMost(image.width) - left
    if (width <= 0) return 0
    val row = IntArray(width)
    var inked = 0
    for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(image.height)) {
        image.getPixels(row, 0, width, left, y, width, 1)
        inked += row.count(::isInk)
    }
    return inked
}

/** Whether each of [pixel]'s channels is dark, as the card's ink is. */
private fun isInk(pixel: Int): Boolean =
    (pixel shr 16 and 0xFF) < INK_LEVEL && (pixel shr 8 and 0xFF) < INK_LEVEL && (pixel and 0xFF) < INK_LEVEL

/** How far [pixel] is from [other]: their red, green and blue's differences, summed. */
private fun distance(
    pixel: Int,
    other: Int,
): Int =
    abs((pixel shr 16 and 0xFF) - (other shr 16 and 0xFF)) +
        abs((pixel shr 8 and 0xFF) - (other shr 8 and 0xFF)) +
        abs((pixel and 0xFF) - (other and 0xFF))

private fun redPicture() =
    Bitmap
        .createBitmap(PICTURE_WIDTH, PICTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(RED) }
        .asImageBitmap()

private fun asked() = ChatItem.Message("out:m9", ShownMessage(Sender.User, ASK, wallAt(1), Delivery.DELIVERED))

private fun card(key: String) =
    ChatItem.Thinking(
        key = key,
        turnId = TURN,
        card =
            LiveCard(
                shownMono = 90_000L,
                headings = listOf("Drawing what you asked"),
                chips = listOf(LiveChip("generate_image", running = true, status = null)),
                speaking = true,
            ),
        startedMono = 90_000L,
        seed = 7L,
    )

private fun nextCard(key: String) =
    ChatItem.Thinking(
        key = key,
        turnId = TURN,
        card = LiveCard(shownMono = 90_500L, headings = listOf(NEXT_HEADING), chips = emptyList(), speaking = true),
        startedMono = 90_000L,
        seed = 7L,
    )

private fun picture(key: String) =
    ChatItem.Message(
        key,
        ShownMessage(
            Sender.Agent,
            "",
            wallAt(1),
            Delivery.NONE,
            turnId = TURN,
            media = listOf(ShownMedia("ab".repeat(32), "ab".repeat(32), MediaShape.IMAGE, "image/png", 10L, null)),
        ),
    )
