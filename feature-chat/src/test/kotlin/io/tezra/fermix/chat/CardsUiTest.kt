package io.tezra.fermix.chat

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.Sender
import io.tezra.fermix.instance.Link
import io.tezra.fermix.instance.tintColor
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.transport.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

private const val NOW = 100_000L

/** A waiting sandbox card at [left] seconds of its minute. */
internal fun waitingCard(
    left: Int,
    answering: Boolean = false,
) = ShownApproval(
    "ap-1",
    CardKind.SANDBOX,
    "sandbox",
    "Allow reading ~/Documents?",
    "~/Documents/**",
    60,
    NOW + left * 1_000L,
    answering,
    null,
)

/** A node TalkBack would stop on: one it can press, or one with words, that is not hidden from it. */
private val TALKBACK_STOP =
    SemanticsMatcher("a TalkBack stop") { node ->
        val config = node.config
        val speaks = SemanticsProperties.Text in config || SemanticsProperties.ContentDescription in config
        (SemanticsActions.OnClick in config || speaks) && SemanticsProperties.HideFromAccessibility !in config
    }

private val HEXDOCS =
    LinkPreviewCard(
        "https://hexdocs.pm/elixir/Task.html",
        "hexdocs.pm",
        "Task — Elixir",
        "Conveniences for spawning and awaiting tasks.",
    )

/**
 * The cards and controls as they draw and answer (design sections 8.6, 13.5, 13.7, 13.8 and 13.10): the approval
 * card one TalkBack group and stop whose custom actions answer it, read at 30 s; a card past its time drawn
 * expired at once with nothing to press; a preview's tap opening its own address, in a Custom Tab in the
 * instance's tint; the reaction chip growing down at 200 % type; a late preview that never moves the row the
 * owner reads; Ctrl+F opening search, and its field's caret after the query. JUnit 4, in Roborazzi's activity,
 * on the compact window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class CardsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val answers = mutableListOf<Pair<String, Boolean>>()
    private val links = mutableListOf<String>()

    private val context =
        TimelineContext(
            zone = UTC,
            locale = Locale.US,
            today = MORNING.atZone(UTC).toLocalDate(),
            host = HOST,
            model = "",
            nowMono = { NOW },
            text = TextActions({}, {}),
            selected = emptySet(),
            menuFor = null,
            onTap = {},
            onLongPress = {},
            onError = {},
            onOutbox = { _, _ -> },
            cards = CardActions(onAnswer = { id, approve -> answers += id to approve }, onLink = { links += it }),
        )

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = rule.activity.getString(id, *args)

    @Test
    fun `the card is one TalkBack group whose custom actions approve and deny, read once at 30 s`() {
        rule.setContent { FermixTheme { ApprovalItem(waitingCard(left = 30), context) } }
        val group = rule.onNodeWithText("Allow reading ~/Documents?", useUnmergedTree = false)
        val actions =
            group
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.CustomActions)
                .orEmpty()
        assertEquals(listOf(string(R.string.chat_approve), string(R.string.chat_deny)), actions.map { it.label })
        actions.first().action()
        actions.last().action()
        assertEquals(listOf("ap-1" to true, "ap-1" to false), answers)
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        rule
            .onNode(
                hasContentDescription(string(R.string.chat_approval_expires, 30)),
                useUnmergedTree = true,
            ).assert(polite)
        rule.onAllNodesWithText("opaque-token", substring = true).assertCountEquals(0)
    }

    @Test
    fun `TalkBack stops on the card once, Approve and Deny a touch's alone, and on the countdown's live region`() {
        rule.setContent { FermixTheme { ApprovalItem(waitingCard(left = 30), context) } }
        val stops = rule.onAllNodes(TALKBACK_STOP).fetchSemanticsNodes()
        val card = rule.onNodeWithText("Allow reading ~/Documents?").fetchSemanticsNode()
        val announcement = string(R.string.chat_approval_expires, 30)
        val region = rule.onNode(hasContentDescription(announcement), useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(listOf(card.id, region.id), stops.map { it.id })
        // A touch still answers.
        rule.onNodeWithText(string(R.string.chat_deny)).performClick()
        assertEquals(listOf("ap-1" to false), answers)
    }

    /**
     * Where the approval card changes height as it counts down: each type size, at once and at twice its size,
     * linearly, as the screenshots scale it, and width it has, from 240 dp to 412 dp, by the heights it takes at
     * seconds from its minute's start to its last, when it takes more than one.
     */
    private fun movingHeights(): Map<Pair<Float, Int>, Set<Float>> {
        var left by mutableStateOf(60)
        var width by mutableStateOf(412)
        var scale by mutableStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                FermixTheme {
                    Box(Modifier.width(width.dp)) { ApprovalItem(waitingCard(left), context, Modifier.testTag("card")) }
                }
            }
        }
        val sizes = listOf(1f, 2f).flatMap { type -> (240..412 step 4).map { type to it } }
        return sizes
            .associateWith { (type, dp) ->
                scale = type
                width = dp
                listOf(60, 42, 11, 10, 9, 1)
                    .map { seconds ->
                        left = seconds
                        rule.waitForIdle()
                        rule
                            .onNodeWithTag("card")
                            .fetchSemanticsNode()
                            .boundsInRoot.height
                    }.toSet()
            }.filterValues { it.size > 1 }
    }

    // Native graphics, as the screenshots draw: the legacy mode measures no real text.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the card keeps its height as it counts down, so the bottom-anchored list never moves its buttons`() {
        assertEquals(emptyMap<Pair<Float, Int>, Set<Float>>(), movingHeights())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `at twice the type size in the compact window Approve and Deny stay on one line each`() {
        rule.setContent {
            val scaled = Density(LocalDensity.current.density, 2f)
            CompositionLocalProvider(LocalDensity provides scaled) {
                FermixTheme {
                    Box(Modifier.padding(horizontal = TIMELINE_GUTTER)) { ApprovalItem(waitingCard(42), context) }
                }
            }
        }
        listOf(string(R.string.chat_approve), string(R.string.chat_deny)).forEach { label ->
            val layout = mutableListOf<TextLayoutResult>()
            rule
                .onNodeWithText(label)
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action
                ?.invoke(layout)
            assertEquals(label, 1, layout.single().lineCount)
        }
    }

    @Test
    fun `a card whose time ran out is its receipt at once, with nothing to press`() {
        val expired = waitingCard(left = 0).copy(expiresAtMono = NOW - 1)
        rule.setContent { FermixTheme { ApprovalItem(expired, context) } }
        rule.onNodeWithText(string(R.string.chat_approval_expired, 60)).assertExists()
        rule.onAllNodesWithText(string(R.string.chat_approve)).assertCountEquals(0)
        rule.onAllNodesWithText(string(R.string.chat_deny)).assertCountEquals(0)
        assertTrue(answers.isEmpty())
    }

    @Test
    fun `a preview's tap opens its own address, and a reaction is read as one`() {
        val message =
            ShownMessage(
                Sender.User,
                "Is there a guide?",
                wallAt(1),
                Delivery.DELIVERED,
                reaction = "👀",
                previews = listOf(HEXDOCS),
            )
        rule.setContent { FermixTheme { MessageItem(ChatItem.Message("m1", message), context) } }
        rule.onNodeWithContentDescription(string(R.string.chat_reaction, "👀")).assertExists()
        rule.onNodeWithText("Task — Elixir").performClick()
        assertEquals(listOf(HEXDOCS.url), links)
    }

    // Native graphics, as the screenshots draw: the legacy mode measures no real text.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `at twice the type size the reaction chip grows down, its top below the words' last baseline`() {
        // Words short enough that the time floats on their line, so nothing but the padding is under them.
        val words = "Two links."
        val message = ShownMessage(Sender.User, words, wallAt(1), Delivery.DELIVERED, reaction = "👍")
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                FermixTheme {
                    Box(Modifier.padding(horizontal = TIMELINE_GUTTER)) {
                        MessageItem(ChatItem.Message("m1", message), context)
                    }
                }
            }
        }
        val chip = rule.onNodeWithContentDescription(string(R.string.chat_reaction, "👍")).fetchSemanticsNode()
        val text = rule.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        val laid = layout.single()
        val baseline = text.boundsInRoot.top + laid.getLineBaseline(laid.lineCount - 1)
        val top = chip.boundsInRoot.top
        assertTrue("the chip's top $top is over the words, whose baseline is $baseline", top >= baseline)
        val pill = with(rule.density) { 24.dp.toPx() }
        assertTrue("the chip did not grow with the type", chip.boundsInRoot.height > pill)
    }

    @Test
    fun `a preview opens in a Custom Tab at its own address, its toolbar in the instance's tint`() {
        var open: ((String) -> Unit)? = null
        rule.setContent { FermixTheme { open = rememberLinkOpener("Slate") } }
        rule.runOnIdle { checkNotNull(open)(HEXDOCS.url) }
        val started = shadowOf(rule.activity).nextStartedActivity
        assertEquals(HEXDOCS.url.toUri(), started.data)
        val colors = CustomTabsIntent.getColorSchemeParams(started, CustomTabsIntent.COLOR_SCHEME_LIGHT)
        assertEquals(tintColor("Slate").toArgb(), colors.toolbarColor)
    }

    @Test
    fun `the search field shows its query with the caret after it`() {
        rule.setContent { FermixTheme { SearchBar(SearchUi(query = "timeout"), SearchActions()) } }
        rule
            .onNode(hasContentDescription(string(R.string.chat_search)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(7)))
    }

    /**
     * Previews land on a row above the one read and on one below the viewport. One on the row read, or between it
     * and the list's anchor, grows upward and moves it: the deviation README records for the owner, which this
     * test leaves out of its scope.
     */
    @Test
    fun `a late preview above or below the row the owner reads never moves it`() {
        val rows =
            (1..30).map { seq ->
                ChatItem.Message(
                    "r$seq",
                    ShownMessage(Sender.Agent, "row $seq", wallAt(1), Delivery.NONE, seq = seq.toULong()),
                )
            }
        var items by mutableStateOf(rows.reversed())
        val list = LazyListState(firstVisibleItemIndex = 10)
        rule.setContent { FermixTheme { Box(Modifier.height(600.dp)) { Timeline(items, context, list) } } }
        rule.waitForIdle()
        val read = rule.onNodeWithText("row 18").fetchSemanticsNode().boundsInRoot
        items =
            items.map { item -> if (item.message.seq == 16uL || item.message.seq == 25uL) item.withPreview() else item }
        rule.waitForIdle()
        assertEquals(read, rule.onNodeWithText("row 18").fetchSemanticsNode().boundsInRoot)
        assertEquals(10, list.firstVisibleItemIndex)
    }

    @Test
    fun `Ctrl+F opens search from the composer`() {
        var opened = 0
        val actions =
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
                nowMono = { NOW },
                text = TextActions({}, {}),
                search = SearchActions(onOpen = { opened++ }),
            )
        val header =
            ChatHeader(sample(), Link.Up(Candidate.Scope.TAILNET, latencyMs = 38, caughtUp = true), thinking = false)
        val state =
            ChatScreenState(
                header,
                null,
                emptyList(),
                "suj-mbp",
                false,
                COMMANDS,
                null,
                0,
                false,
                null,
                UTC,
                MORNING.atZone(UTC).toLocalDate(),
                emptyList(),
            )
        rule.setContent { FermixTheme { ChatScreen(ChatUi(state, TextFieldValue(""), palette = false), actions) } }
        val field = rule.onNode(hasContentDescription(string(R.string.chat_placeholder, "suj-mbp")))
        field.performClick()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
        assertEquals(1, opened)
    }

    private fun ChatItem.Message.withPreview() = copy(message = message.copy(previews = listOf(HEXDOCS)))
}
