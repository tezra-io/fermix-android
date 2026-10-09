package io.tezra.fermix.chat

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.GroupPosition
import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.LinkPreviewCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset.UTC
import java.util.Locale

private const val FRAME = "frame"

/** xhdpi: two pixels a dp. */
private const val PX_PER_DP = 2

/**
 * The focus ring in the timeline and the composer's controls (the M51 update's 1.3), read off the frame's pixels
 * with the window out of touch mode, as a d-pad leaves it: an agent's reply is ringed around its bubbles as they
 * grow, not the 88 % its parts may grow to, while a press anywhere in that 88 % still reaches it, and square where
 * a job's tag or the time lies in its corner; a focused message's ring is drawn over the message beside it in its
 * group and over its link preview, and a document's over the words under it, each 2 dp away; the scroll-to-latest
 * pill's ring is lined with onInk, as it floats over the owner's ink bubbles; the voice hint's "Open settings" is
 * ringed around its 48 dp target; and a focused link among a message's words, which takes no ring, is drawn onInk
 * on the ink.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TimelineRingTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val light = FermixColors.Light

    @Test
    fun `a short reply's ring follows its bubble, 2 to 4 dp past its end, not the width its parts may grow to`() {
        show(listOf(said("a1", Sender.Agent, "Ok.", GroupPosition.Single)))
        val bounds = focus("Ok.")
        val image = frame()
        val y = bounds.center.y.toInt()
        val bubbleEnd = (image.width - 1 downTo 0).first { image.getPixel(it, y) == light.agentBubble.toArgb() }
        assertEquals(light.canvas.toArgb(), image.at(bubbleEnd, 1.dp, y))
        assertEquals(light.ink.toArgb(), image.at(bubbleEnd, 3.dp, y))
        assertEquals(light.canvas.toArgb(), image.at(bubbleEnd, 6.dp, y))
    }

    @Test
    fun `a focused reply whose words grow keeps its ring 2 to 4 dp past its bubble's new end`() {
        var words by mutableStateOf("Ok.")
        rule.setContent {
            Keyboard {
                Box(Modifier.testTag(FRAME).fillMaxSize().background(light.canvas)) {
                    val reply = said("a1", Sender.Agent, words, GroupPosition.Single)
                    Timeline(listOf(reply), CONTEXT, rememberLazyListState())
                }
            }
        }
        rule.waitForIdle()
        val y = focus("Ok.").center.y.toInt()
        words = "Ok, the export finished."
        rule.waitForIdle()
        val image = frame()
        val bubbleEnd = (image.width - 1 downTo 0).first { image.getPixel(it, y) == light.agentBubble.toArgb() }
        assertEquals(light.ink.toArgb(), image.at(bubbleEnd, 3.dp, y))
        assertEquals(light.canvas.toArgb(), image.at(bubbleEnd, 6.dp, y))
    }

    @Test
    fun `a long-press beside a short reply, within the width its parts may grow to, reaches the message`() {
        var pressed = 0
        show(listOf(said("a1", Sender.Agent, "Ok.", GroupPosition.Single)), CONTEXT.copy(onLongPress = { pressed++ }))
        val message = rule.onNodeWithText("Ok.").fetchSemanticsNode().boundsInRoot
        val frame = rule.onNodeWithTag(FRAME).fetchSemanticsNode().boundsInRoot
        // What a tap, a long-press and TalkBack reach spans the 88 % of the row the message's parts may grow to.
        val row = frame.width - 2 * TIMELINE_GUTTER.value * PX_PER_DP
        assertEquals(row * FermixSpacing.AGENT_BUBBLE_MAX_WIDTH, message.width, 1f)
        rule.onNodeWithTag(FRAME).performTouchInput { longClick(Offset(frame.width * BESIDE, message.center.y)) }
        rule.waitForIdle()
        assertEquals(1, pressed)
    }

    @Test
    fun `a job's card is ringed square at the corners where its tag and its time lie, clear of their words`() {
        val job = "nightly-export"
        val card = said("a1", Sender.Agent, "```\nmix export --nightly\n```", GroupPosition.Single)
        show(listOf(card.copy(message = card.message.copy(job = job))))
        val bounds = focus(rule.activity.getString(R.string.chat_job, job))
        val image = frame()
        // A rounded ring's corner would pass well inside these two points; a square one passes through them.
        val out = 3 * PX_PER_DP
        val (left, top) = bounds.left.toInt() - out to bounds.top.toInt() - out
        val (right, bottom) = bounds.right.toInt() + out - 1 to bounds.bottom.toInt() + out - 1
        assertEquals(light.ink.toArgb(), image.getPixel(left, top))
        assertEquals(light.ink.toArgb(), image.getPixel(right, bottom))
    }

    @Test
    fun `a focused document's ring is drawn over its message's words 2 dp under it`() {
        val report = ShownMedia("doc-1", null, MediaShape.DOCUMENT, "application/pdf", 1_258_291, "report.pdf")
        val message = said("a1", Sender.Agent, "Here it is.", GroupPosition.Single)
        show(listOf(message.copy(message = message.message.copy(media = listOf(report)))))
        val bounds = focus("report.pdf")
        val x = (bounds.left + 30 * PX_PER_DP).toInt()
        assertEquals(light.ink.toArgb(), frame().getPixel(x, bounds.bottom.toInt() + 3 * PX_PER_DP))
    }

    @Test
    fun `the scroll-to-latest pill's ring is lined with onInk, so it reads over the owner's bubble`() {
        rule.setContent {
            Keyboard {
                Box(Modifier.testTag(FRAME).background(light.ink).padding(16.dp)) { ScrollPill(0, onClick = {}) }
            }
        }
        val words = rule.activity.getString(R.string.chat_to_bottom)
        rule.onNodeWithContentDescription(words).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val bounds = rule.onNodeWithContentDescription(words).fetchSemanticsNode().boundsInRoot
        assertEquals(light.onInk.toArgb(), frame().at(bounds.right.toInt(), 1.dp, bounds.center.y.toInt()))
    }

    @Test
    fun `the voice hint's Open settings is ringed around its 48 dp target, clear of its words`() {
        rule.setContent {
            Keyboard {
                Box(Modifier.testTag(FRAME).background(light.canvas).padding(16.dp)) {
                    VoiceHint(VoiceUi.Idle, micOff = true, onSettings = {})
                }
            }
        }
        val bounds = focus(rule.activity.getString(R.string.chat_open_settings))
        val image = frame()
        val x = bounds.center.x.toInt()
        val half = (FermixSpacing.minTarget.value / 2).toInt() * PX_PER_DP
        // Its words' line is 16 dp tall: a ring round the line would lie 2 to 4 dp over it, round the target 26 dp.
        assertEquals(light.canvas.toArgb(), image.getPixel(x, bounds.top.toInt() - 3 * PX_PER_DP))
        assertEquals(light.ink.toArgb(), image.getPixel(x, bounds.center.y.toInt() - half - 3 * PX_PER_DP))
    }

    @Test
    fun `a focused message's ring is drawn over the older message 2 dp above it in its group`() {
        show(
            listOf(
                said("a3", Sender.Agent, "Third agent line here", GroupPosition.Last),
                said("a2", Sender.Agent, "Second agent line here", GroupPosition.Middle),
                said("a1", Sender.Agent, "First agent line here", GroupPosition.First),
            ),
        )
        val bounds = focus("Second agent line here")
        val x = (bounds.left + 30 * PX_PER_DP).toInt()
        val top = bounds.top.toInt()
        // The older bubble ends 2 dp above; the ring's stroke lies over it.
        assertEquals(light.ink.toArgb(), frame().getPixel(x, top - 3 * PX_PER_DP))
    }

    @Test
    fun `a focused message's ring is drawn over its link preview 2 dp under it`() {
        val card = LinkPreviewCard("https://hexdocs.pm/elixir/Task.html", "hexdocs.pm", "Task", "Tasks.", null)
        val message = said("a1", Sender.Agent, "See the guide", GroupPosition.Single)
        show(listOf(message.copy(message = message.message.copy(previews = listOf(card)))))
        val bounds = focus("See the guide")
        val x = (bounds.left + 30 * PX_PER_DP).toInt()
        assertEquals(light.ink.toArgb(), frame().getPixel(x, bounds.bottom.toInt() + 3 * PX_PER_DP))
    }

    @Test
    fun `a focused link among a message's words is drawn onInk on the ink`() {
        rule.setContent {
            Keyboard {
                Box(Modifier.testTag(FRAME).background(light.agentBubble)) {
                    Prose(LINKED, streaming = false, resets = 0, TextActions({}, {}))
                }
            }
        }
        rule.waitForIdle()
        val before = frame().count(light.ink.toArgb())
        rule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus), useUnmergedTree = true)
            .onFirst()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val after = frame().count(light.ink.toArgb())
        // The link's words, about 70 by 24 dp, now lie on a block of the ink, their glyphs in onInk.
        assertTrue("the ink covered $before px before the focus and $after px with it", after > before + LINK_BLOCK_PX)
    }

    private fun show(
        items: List<ChatItem>,
        context: TimelineContext = CONTEXT,
    ) {
        rule.setContent {
            Keyboard {
                Box(Modifier.testTag(FRAME).fillMaxSize().background(light.canvas)) {
                    Timeline(items, context, rememberLazyListState())
                }
            }
        }
        rule.waitForIdle()
    }

    /** Focuses the control whose words are [words], as a d-pad would, and gives its bounds in the frame. */
    private fun focus(words: String): Rect {
        rule.onNodeWithText(words).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        return rule.onNodeWithText(words).fetchSemanticsNode().boundsInRoot
    }

    private fun frame(): Bitmap = rule.onNodeWithTag(FRAME).captureToImage().asAndroidBitmap()
}

/** Where beside a short reply a press lands: past its bubble, inside the 88 % of the row. */
private const val BESIDE = 0.6f

/** A link's words among others, which the renderer makes a focusable of their own. */
private const val LINKED = "Read [the guide](https://hexdocs.pm/elixir/Task.html) first."

/** What a block of the ink behind a link's words adds at the least, past its glyphs and underline (2 × 2 px a dp). */
private const val LINK_BLOCK_PX = 40 * 10 * PX_PER_DP * PX_PER_DP

private val CONTEXT =
    TimelineContext(
        zone = UTC,
        locale = Locale.US,
        today = MORNING.atZone(UTC).toLocalDate(),
        host = HOST,
        model = "",
        nowMono = { 100_000L },
        text = TextActions({}, {}),
        selected = emptySet(),
        menuFor = null,
        onTap = {},
        onLongPress = {},
        onError = {},
        onOutbox = { _, _ -> },
    )

private fun said(
    key: String,
    sender: Sender,
    text: String,
    position: GroupPosition,
): ChatItem.Message {
    val delivery = if (sender == Sender.User) Delivery.DELIVERED else Delivery.NONE
    return ChatItem.Message(key, ShownMessage(sender, text, wallAt(1), delivery, position))
}

/** [content] in the light theme with the window out of touch mode, as a d-pad or a keyboard leaves it. */
@Composable
private fun Keyboard(content: @Composable () -> Unit) {
    FermixTheme(darkTheme = false) {
        CompositionLocalProvider(LocalInputModeManager provides KeyboardMode, content = content)
    }
}

/** The window's input mode, held out of touch mode. */
private object KeyboardMode : InputModeManager {
    override val inputMode: InputMode = InputMode.Keyboard

    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
}

/** The pixel [out] past [x] on the row [y]. */
private fun Bitmap.at(
    x: Int,
    out: Dp,
    y: Int,
): Int = getPixel(x + (out.value * PX_PER_DP).toInt(), y)

/** How many of the image's pixels are [argb]. */
private fun Bitmap.count(argb: Int): Int {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return pixels.count { it == argb }
}
