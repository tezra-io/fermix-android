package io.tezra.fermix.chat

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.protocol.Place
import io.tezra.fermix.protocol.ReplyBlock
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The reply after the rig's thread, linking [PLACES]: its row carries their block. */
private val PLACES_REPLY =
    TimelineRow.Reply(
        (THREAD_ROWS + 1).toULong(),
        "turn-m41",
        "Three quiet ones nearby.",
        truncated = false,
        route = null,
        blocks = listOf(ReplyBlock.Places(PLACES)),
    )

/**
 * The `geo:` address the third card opens, as an intent filter reads it: its scheme, then the rest decoded. Written
 * out here, not built, so the test holds the address to the design (M72 section 14) and not to itself.
 */
private const val THIRD_GEO = "40.7262,-74.0008?q=40.7262,-74.0008(Paper Lantern Books (Café))"

/** How many swipes the lane is given to bring its third card into view. */
private const val MAX_SWIPES = 4

/** The lane: the list whose own items are cards, the second card among them wherever the lane stands. */
private val LANE = hasScrollToIndexAction() and hasAnyChild(hasContentDescription(QUIET_CORNER.name, substring = true))

/**
 * Place cards on a device (M72 section 14): a reply's row with three places draws its lane; a swipe brings the third
 * card into view, and its tap starts the Maps app's `geo:` address for it, the place's coordinates and its name, and
 * no web page. Instrumentation's monitors catch each start and block it, so no app opens over the test.
 */
class PlaceDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val maps =
        Instrumentation.ActivityMonitor(
            IntentFilter(Intent.ACTION_VIEW).apply {
                addDataScheme("geo")
                addDataSchemeSpecificPart(THIRD_GEO, PatternMatcher.PATTERN_LITERAL)
            },
            null,
            true,
        )

    private val web =
        Instrumentation.ActivityMonitor(
            IntentFilter(Intent.ACTION_VIEW).apply {
                addDataScheme("https")
                addDataScheme("http")
            },
            null,
            true,
        )

    @Before
    fun watch() {
        instrumentation.addMonitor(maps)
        instrumentation.addMonitor(web)
    }

    @After
    fun unwatch() {
        instrumentation.removeMonitor(maps)
        instrumentation.removeMonitor(web)
    }

    /** [place]'s card, by the sentence TalkBack hears, which begins with its name. */
    private fun card(place: Place): SemanticsNodeInteraction =
        rule.onNode(hasContentDescription(place.name, substring = true))

    /** Whether [place]'s card is drawn, whole, within the lane. */
    private fun shown(place: Place): Boolean {
        val drawn = rule.onAllNodes(hasContentDescription(place.name, substring = true)).fetchSemanticsNodes()
        val lanes = rule.onAllNodes(LANE).fetchSemanticsNodes()
        val lane = lanes.singleOrNull()?.boundsInRoot
        val card = drawn.singleOrNull()?.boundsInRoot
        return lane != null && card != null && card.left >= lane.left - 1f && card.right <= lane.right + 1f
    }

    private fun lane(): SemanticsNodeInteraction = rule.onNode(LANE)

    @Test
    fun a_swipe_brings_the_third_card_whose_tap_starts_the_maps_apps_geo_address() {
        val rig = rule.activity.rig
        rig.store.frontier.value = PLACES_REPLY.serverSeq
        rig.store.rows.update { listOf(PLACES_REPLY) + it }
        rule.waitUntil("the lane is drawn", STEP_MILLIS) { shown(EXAMPLE_COFFEE) }
        card(EXAMPLE_COFFEE).assertIsDisplayed()
        repeat(MAX_SWIPES) {
            if (!shown(PAPER_LANTERN)) lane().performTouchInput { swipeLeft() }
            rule.waitForIdle()
        }
        card(PAPER_LANTERN).assertIsDisplayed().performClick()
        rule.waitUntil("the Maps app's start", STEP_MILLIS) { maps.hits == 1 }
        assertEquals("a web page started", 0, web.hits)
    }
}
