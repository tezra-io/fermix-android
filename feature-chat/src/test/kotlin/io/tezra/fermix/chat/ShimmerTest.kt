package io.tezra.fermix.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.Sender
import io.tezra.fermix.instance.Link
import io.tezra.fermix.transport.Candidate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** How far a glyph's darkest pixel may sit from the gradient's colour at its place, in one channel's 255. */
private const val SLACK = 40

/**
 * The thinking card's line as it draws (design section 13.5, the canon's `.think .hd span`): still under
 * reduce-motion, and in the sweep's first frame, which a screenshot holds, its glyphs reach the ink over the
 * middle of the line and are back to the third ink at its end, on the card in the chat's timeline. JUnit 4,
 * in Roborazzi's activity, on the compact window of @FermixPreviews, drawn natively.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShimmerTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val actions =
        ChatScreenActions(
            onBack = {},
            onInstance = {},
            composer = ComposerActions({}, {}, {}, {}),
            onPick = {},
            onClosePalette = {},
            onError = {},
            onOutbox = { _, _ -> },
            list = ListActions({}, {}, {}),
            info = { ShownInfo(null, null, null, null, null, null) },
            modelOf = { it.model },
            nowMono = { 1_000L },
            text = TextActions({}, {}),
        )

    @Test
    fun `the still shimmer peaks in the ink over the middle of the line and ends in the third ink`() {
        assertPeaksOverTheWords(reduced = true)
    }

    @Test
    fun `the sweep's first frame, which a screenshot holds, is the still one`() {
        rule.mainClock.autoAdvance = false
        assertPeaksOverTheWords(reduced = false)
    }

    /** The card's line, under reduce-motion or not, reaches the ink in its middle and the third ink at its end. */
    private fun assertPeaksOverTheWords(reduced: Boolean) {
        val card = LiveCard(shownMono = 0L, headings = emptyList(), chips = emptyList(), speaking = false)
        val question = ShownMessage(Sender.User, "Why did the export fail?", 0L, Delivery.DELIVERED)
        val asked = ChatItem.Message("r1", question)
        val thinking = ChatItem.Thinking("card:t1", "t1", card, startedMono = 0L, seed = 7L)
        val state =
            ChatScreenState(
                header = ChatHeader(sample(), Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true), true),
                banner = null,
                items = listOf(thinking, asked),
                name = sample().title,
                turnRuns = true,
                commands = COMMANDS,
                newestSeq = 1uL,
                unseen = 0,
                older = false,
                chosenModel = null,
                zone = UTC,
                today = MORNING.atZone(UTC).toLocalDate(),
                arrived = emptyList(),
            )
        lateinit var colors: FermixColors
        rule.setContent {
            FermixTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    colors = LocalFermixColors.current
                    ChatScreen(ChatUi(state, TextFieldValue(""), palette = false), actions)
                }
            }
        }
        val opening = rule.activity.getString(R.string.chat_indicator_opening)
        val line = rule.onNodeWithText(opening).captureToImage().toPixelMap()

        fun darkest(
            from: Float,
            to: Float,
        ): Int {
            val columns = (line.width * from).toInt() until (line.width * to).toInt()
            return columns.minOf { x -> (0 until line.height).minOf { y -> red(line[x, y]) } }
        }
        val middle = darkest(0.40f, 0.50f)
        val end = darkest(0.92f, 1f)
        val ink = red(colors.ink)
        assertTrue("the middle's darkest is $middle, not near the ink's $ink", middle <= ink + SLACK)
        assertTrue("the end's darkest is $end, darker than the third ink's", end >= red(colors.inkTertiary) - SLACK / 4)
    }

    /** A pixel's red over white, as the canvas under the card shows it: a clear pixel is white. */
    private fun red(color: Color): Int = ((color.red * color.alpha + (1f - color.alpha)) * 255).roundToInt()
}
