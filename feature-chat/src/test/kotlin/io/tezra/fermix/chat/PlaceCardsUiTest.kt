package io.tezra.fermix.chat

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.Sender
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.Locale

/** A browser on the phone, which opens web addresses and nothing else. */
private val BROWSER = ComponentName("org.example.browser", "org.example.browser.Browser")

/** A link preview under the reply whose page is no place's. */
private val GUIDE = LinkPreviewCard("https://hexdocs.pm/elixir/Task.html", "hexdocs.pm", "Task — Elixir")

/** A node TalkBack would stop on: one it can press, or one with words, that is not hidden from it. */
private val TALKBACK_STOP =
    SemanticsMatcher("a TalkBack stop") { node ->
        val config = node.config
        val speaks = SemanticsProperties.Text in config || SemanticsProperties.ContentDescription in config
        (SemanticsActions.OnClick in config || speaks) && SemanticsProperties.HideFromAccessibility !in config
    }

/**
 * The place cards under a reply (M72 section 14): a sideways lane of one card for each place, in the reply's order,
 * a card about 72 % of the bubble's greatest width and a card alone that width; a tap opens the Maps app at the
 * place's `geo:` address, started directly, and with no app for it the place's Google Maps page in the chat's
 * Custom Tab; the long-press menu's Open in Maps, Open website and Copy address, the last only for a place with
 * an address; and TalkBack's one stop for each card, saying it in one sentence, its tap "Open in Maps" and the
 * menu's other two its custom actions. JUnit 4, in Roborazzi's activity, on the compact window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class PlaceCardsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val links = mutableListOf<String>()
    private val places = mutableListOf<Place>()
    private val copies = mutableListOf<String>()

    private val context =
        TimelineContext(
            zone = UTC,
            locale = Locale.US,
            today = MORNING.atZone(UTC).toLocalDate(),
            host = HOST,
            model = "",
            nowMono = { 0L },
            text = TextActions(copy = { copies += it }, share = {}),
            selected = emptySet(),
            menuFor = null,
            onTap = {},
            onLongPress = {},
            onError = {},
            onOutbox = { _, _ -> },
            cards = CardActions(onLink = { links += it }, onPlace = { places += it }),
        )

    /** The agent's reply with [shown] under it, and a link preview beneath. */
    private fun reply(shown: List<Place>) =
        ChatItem.Message(
            "r1",
            ShownMessage(Sender.Agent, "Three quiet places.", wallAt(1), Delivery.NONE, previews = listOf(GUIDE))
                .copy(places = shown),
        )

    private fun show(
        shown: List<Place>,
        cards: CardActions? = null,
    ) {
        rule.setContent {
            FermixTheme {
                val opener = rememberPlaceOpener("Slate")
                MessageItem(reply(shown), context.copy(cards = cards ?: context.cards.copy(onPlace = opener)))
            }
        }
    }

    private fun spoken(place: Place): String = spokenPlace(place, Locale.US, rule.activity.resources)

    private fun card(place: Place): SemanticsNodeInteraction =
        rule.onNode(hasContentDescription(spoken(place)), useUnmergedTree = true)

    private fun lane(): SemanticsNodeInteraction = rule.onNode(hasScrollToIndexAction())

    private fun string(id: Int): String = rule.activity.getString(id)

    /** The agent's bubble at its greatest, in pixels: its share of the row the message fills. */
    private fun bubbleWidth(): Float {
        val row = rule.onRoot().fetchSemanticsNode().boundsInRoot
        return row.width * FermixSpacing.AGENT_BUBBLE_MAX_WIDTH
    }

    /** Every activity started since the test began, oldest first. */
    private fun started(): List<Intent> {
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        return generateSequence { shadow.nextStartedActivity }.take(PLACES.size + 1).toList()
    }

    @Test
    fun `the lane draws a card for each place in the reply's order, then the reply's link preview`() {
        show(PLACES, CardActions())
        val first = card(EXAMPLE_COFFEE).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = card(QUIET_CORNER).fetchSemanticsNode().boundsInRoot
        assertTrue("the second card is not after the first", second.left > first.right)
        val preview = rule.onNodeWithText(GUIDE.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the link preview is not under the cards", preview.top > first.bottom)
        lane().performScrollToIndex(PLACES.lastIndex)
        card(PAPER_LANTERN).assertIsDisplayed()
        val range = lane().fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals("the lane goes on past its last card", range.maxValue(), range.value(), 0.5f)
    }

    @Test
    fun `a reply with no places draws no lane`() {
        show(emptyList(), CardActions())
        rule.onAllNodes(hasScrollToIndexAction()).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription(EXAMPLE_COFFEE.name, substring = true)).assertCountEquals(0)
        rule.onNodeWithText(GUIDE.title, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a card is about 72 percent of the bubble's greatest width, every card one height`() {
        show(PLACES, CardActions())
        val bubble = bubbleWidth()
        val first = card(EXAMPLE_COFFEE).fetchSemanticsNode().boundsInRoot
        assertEquals("a card's width of a ${bubble.toInt()} px bubble", bubble * PLACE_CARD_SHARE, first.width, 2f)
        lane().performScrollToIndex(PLACES.lastIndex)
        val bare = card(PAPER_LANTERN).fetchSemanticsNode().boundsInRoot
        assertEquals("a card with no facts is not as tall", first.height, bare.height, 0.5f)
    }

    @Test
    fun `a card alone is the bubble's greatest width`() {
        show(listOf(PAPER_LANTERN), CardActions())
        val bubble = bubbleWidth()
        assertEquals(bubble, card(PAPER_LANTERN).fetchSemanticsNode().boundsInRoot.width, 2f)
    }

    @Test
    fun `a tap opens the Maps app at the place's geo address, started directly`() {
        show(PLACES)
        card(EXAMPLE_COFFEE).performClick()
        val maps = started().single()
        assertEquals(Intent.ACTION_VIEW, maps.action)
        assertEquals("geo:40.7233,-74.003?q=40.7233,-74.003(Example%20Coffee)", maps.dataString)
        assertNull("a place opens with no chooser", maps.component ?: maps.`package`)
    }

    @Test
    fun `with no app for a geo address, the place opens as a Google Maps page in the chat's Custom Tab`() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).checkActivities(true)
        val web =
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            }
        shadowOf(application.packageManager).apply {
            addActivityIfNotPresent(BROWSER)
            addIntentFilterForActivity(BROWSER, web)
        }
        show(PLACES)
        card(EXAMPLE_COFFEE).performClick()
        val tab = started().single()
        assertEquals(Intent.ACTION_VIEW, tab.action)
        assertEquals("https://www.google.com/maps/search/?api=1&query=40.7233,-74.003", tab.dataString)
        assertTrue("a Custom Tab's intent", tab.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        val logged = ShadowLog.getLogsForTag(LINK_TAG).map { it.msg }
        assertTrue("$logged", logged.any { "geo:" in it && EXAMPLE_COFFEE.name !in it })
    }

    @Test
    fun `TalkBack stops once on a card, hears it in one sentence, and finds the menu's entries as its actions`() {
        show(PLACES, CardActions(onLink = { links += it }, onPlace = { places += it }))
        val node = card(EXAMPLE_COFFEE).fetchSemanticsNode()
        val sentence =
            "Example Coffee, 4.6 of 5 from 321 ratings, $$, Coffee shop, Thursday 07:00-19:00, 0.4 km"
        assertEquals(listOf(sentence), node.config[SemanticsProperties.ContentDescription])
        assertEquals(string(R.string.chat_place_open_in_maps), node.config[SemanticsActions.OnClick].label)
        val actions = node.config[SemanticsActions.CustomActions]
        val labels = listOf(string(R.string.chat_place_open_website), string(R.string.chat_place_copy_address))
        assertEquals(labels, actions.map { it.label })
        // The tree TalkBack reads leaves out what the card's sentence replaces: no stop inside the card, no words.
        rule.onAllNodesWithText(EXAMPLE_COFFEE.name, substring = true).assertCountEquals(0)
        rule.onAllNodes(TALKBACK_STOP and hasAnyAncestor(hasContentDescription(sentence))).assertCountEquals(0)
        rule.runOnIdle {
            actions.forEach { it.action() }
            node.config[SemanticsActions.OnClick].action?.invoke()
        }
        assertEquals(listOf("https://example.coffee/"), links)
        assertEquals(listOf("12 Example St, New York"), copies)
        assertEquals(listOf(EXAMPLE_COFFEE), places)
        val bare = card(QUIET_CORNER).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(string(R.string.chat_place_open_website)), bare.map { it.label })
    }

    @Test
    fun `a long-press opens the card's menu, Copy address only for a place with one`() {
        show(PLACES, CardActions(onLink = { links += it }, onPlace = { places += it }))
        card(EXAMPLE_COFFEE).performTouchInput { longClick() }
        rule.onNodeWithText(string(R.string.chat_place_open_in_maps)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.chat_place_open_website)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.chat_place_copy_address)).performClick()
        assertEquals(listOf("12 Example St, New York"), copies)
        rule.onAllNodesWithText(string(R.string.chat_place_copy_address)).assertCountEquals(0)
        card(QUIET_CORNER).performTouchInput { longClick() }
        rule.onNodeWithText(string(R.string.chat_place_open_website)).performClick()
        assertEquals(listOf("https://quietcorner.example/"), links)
        rule.onAllNodesWithText(string(R.string.chat_place_copy_address)).assertCountEquals(0)
        card(QUIET_CORNER).performTouchInput { longClick() }
        rule.onNodeWithText(string(R.string.chat_place_open_in_maps)).performClick()
        assertEquals(listOf(QUIET_CORNER), places)
    }
}
