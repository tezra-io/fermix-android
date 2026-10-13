package io.tezra.fermix.chat

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.GroupPosition
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.Sender
import io.tezra.fermix.instance.Link
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/** No haptic played yet, as Robolectric's view records it. */
private const val NO_HAPTIC = -1

/** The moves a swipe is made of, so the drag's slop is crossed early and its travel followed. */
private const val SWIPE_STEPS = 12

/** The agent's answer the swipes take, and the owner's question. */
private const val ANSWER_WORDS = "It timed out at 60 s."
private const val ASKED_WORDS = "Why did the export fail?"

private val TODAY: LocalDate = MORNING.atZone(UTC).toLocalDate()

private val ANSWER =
    ChatItem.Message("r2", ShownMessage(Sender.Agent, ANSWER_WORDS, wallAt(2), Delivery.NONE, seq = 2uL))

private val ASKED =
    ChatItem.Message("r1", ShownMessage(Sender.User, ASKED_WORDS, wallAt(1), Delivery.DELIVERED, seq = 1uL))

/** The screen over [items], newest first, nothing in the composer. */
private fun screen(items: List<ChatItem>): ChatUi =
    ChatUi(
        state =
            ChatScreenState(
                header = ChatHeader(sample(), Link.Up(Candidate.Scope.TAILNET, 38, caughtUp = true), false),
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
        field = TextFieldValue(""),
        palette = false,
    )

/**
 * The swipe that replies, and the reply's strip and chip (the owner, 2026-10-11): a horizontal drag toward the end
 * edge past [REPLY_SWIPE] on a bubble with words starts a reply, with its haptic, and a shorter one or one the other
 * way does not; a move recomposes nothing; a vertical drag still scrolls; TalkBack's Reply is the bubble's own custom
 * action; under reduce-motion nothing moves and the swipe still replies; the strip over the composer, its close and
 * the field's focus; the owner's reply drawn as a quote and its question. JUnit 4, in Roborazzi's activity, on the
 * compact window of @FermixPreviews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ReplySwipeTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val replied = mutableListOf<ShownMessage>()
    private lateinit var view: View

    @After
    fun backgroundEnds() = background.cancel()

    private val actions =
        ChatScreenActions(
            onBack = {},
            onInstance = {},
            composer =
                ComposerActions(onField = {}, onSend = {}, onStop = {}, onPalette = {}, onReply = { replied += it }),
            onPick = {},
            onClosePalette = {},
            onError = {},
            onOutbox = { _, _ -> },
            list = ListActions(onBottom = {}, onTop = {}, onListed = {}),
            info = { ShownInfo(null, null, null, null, null, null) },
            modelOf = { it.model },
            nowMono = { 0L },
            text = TextActions(copy = {}, share = {}),
        )

    private fun px(dp: Dp): Float = with(rule.density) { dp.toPx() }

    /** The screen over [items], under reduce-motion when [reduced]. */
    private fun showing(
        items: List<ChatItem>,
        reduced: Boolean = false,
        listState: LazyListState = LazyListState(),
    ) {
        rule.setContent {
            view = LocalView.current
            FermixTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    ChatScreen(screen(items), actions, listState)
                }
            }
        }
        rule.waitForIdle()
    }

    /** Where [words] draw now, their left edge in the window: the bubble's, as it moves under the finger. */
    private fun leftOf(words: String): Float =
        rule
            .onNodeWithText(words, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot.left

    /** A finger down on [bubble]'s start side and moved [dx] across in [SWIPE_STEPS] steps, still down. */
    private fun SemanticsNodeInteraction.dragging(dx: Float): SemanticsNodeInteraction =
        performTouchInput {
            down(Offset(width * 0.25f, centerY))
            repeat(SWIPE_STEPS) { moveBy(Offset(dx / SWIPE_STEPS, 0f)) }
        }

    private fun SemanticsNodeInteraction.lifted(): SemanticsNodeInteraction = performTouchInput { up() }

    @Test
    fun `the bubble follows the finger to the threshold, a quarter of it past, and no further than the overshoot`() {
        assertEquals(0f, replyOffset(-40f, 56f, 16f))
        assertEquals(30f, replyOffset(30f, 56f, 16f))
        assertEquals(56f, replyOffset(56f, 56f, 16f))
        assertEquals(61f, replyOffset(76f, 56f, 16f))
        assertEquals(72f, replyOffset(500f, 56f, 16f))
    }

    @Test
    fun `a swipe past the threshold on the agent's bubble replies, and a shorter one springs back and does not`() {
        showing(listOf(ANSWER, ASKED))
        val rest = leftOf(ANSWER_WORDS)
        val bubble = rule.onNodeWithText(ANSWER_WORDS)
        bubble.dragging(px(REPLY_SWIPE) / 2)
        rule.waitForIdle()
        assertTrue("the bubble follows the finger", leftOf(ANSWER_WORDS) > rest)
        bubble.lifted()
        rule.waitForIdle()
        assertEquals("it springs home", rest, leftOf(ANSWER_WORDS), 0.5f)
        assertEquals(emptyList<ShownMessage>(), replied)
        assertEquals("no threshold crossed", NO_HAPTIC, shadowOf(view).lastHapticFeedbackPerformed())

        bubble.dragging(px(REPLY_SWIPE) * 2)
        rule.waitForIdle()
        val held = leftOf(ANSWER_WORDS) - rest
        assertTrue("past the threshold, but never past its overshoot: $held", held > px(REPLY_SWIPE))
        assertTrue("past the threshold, but never past its overshoot: $held", held <= px(REPLY_SWIPE + REPLY_OVERSHOOT))
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, shadowOf(view).lastHapticFeedbackPerformed())
        bubble.lifted()
        rule.waitForIdle()
        assertEquals(listOf(ANSWER.message), replied)
        assertEquals("it springs home", rest, leftOf(ANSWER_WORDS), 0.5f)
    }

    @Test
    fun `a move recomposes nothing, as the offset is read in the bubble's layer and the row's draw alone`() {
        var compositions = 0
        rule.setContent {
            FermixTheme {
                val swipe = rememberReplySwipe(enabled = true) { replied += ANSWER.message }
                SideEffect { compositions++ }
                Box(modifier = Modifier.fillMaxWidth().then(swipe.row)) {
                    Text(ANSWER_WORDS, modifier = Modifier.padding(start = 16.dp).replySwipe(swipe))
                }
            }
        }
        rule.waitForIdle()
        val made = compositions
        val rest = leftOf(ANSWER_WORDS)
        val bubble = rule.onNodeWithText(ANSWER_WORDS)
        bubble.dragging(px(REPLY_SWIPE) * 2)
        rule.waitForIdle()
        assertTrue("the bubble follows the finger", leftOf(ANSWER_WORDS) > rest)
        assertEquals("compositions as the finger moves", made, compositions)
        bubble.lifted()
        rule.waitForIdle()
        assertEquals(listOf(ANSWER.message), replied)
        assertEquals("compositions through the spring home", made, compositions)
    }

    @Test
    fun `the owner's bubble takes the same swipe, and travel the other way moves nothing`() {
        showing(listOf(ANSWER, ASKED))
        val rest = leftOf(ASKED_WORDS)
        val bubble = rule.onNodeWithText(ASKED_WORDS)
        bubble.dragging(-px(REPLY_SWIPE) * 2)
        rule.waitForIdle()
        assertEquals("travel toward the start edge moves nothing", rest, leftOf(ASKED_WORDS), 0.5f)
        bubble.lifted()
        rule.waitForIdle()
        assertEquals(emptyList<ShownMessage>(), replied)
        bubble.dragging(px(REPLY_SWIPE) * 2).lifted()
        rule.waitForIdle()
        assertEquals(listOf(ASKED.message), replied)
    }

    @Test
    fun `a vertical drag on a bubble scrolls the list`() {
        val many =
            (1..30).map { seq ->
                val sender = if (seq % 2 == 0) Sender.Agent else Sender.User
                val delivery = if (sender == Sender.User) Delivery.DELIVERED else Delivery.NONE
                ChatItem.Message("m$seq", ShownMessage(sender, "message $seq", wallAt(seq.toLong()), delivery))
            }
        val list = LazyListState()
        showing(many.asReversed(), listState = list)
        rule.onNodeWithText("message 29").performTouchInput { swipeDown(startY = top + 4f, endY = bottom + 600f) }
        rule.waitForIdle()
        assertTrue("the list did not scroll", list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
        assertEquals(emptyList<ShownMessage>(), replied)
    }

    @Test
    fun `Reply is a custom action on a bubble with words, and none on a media-only or a streaming one`() {
        val image = ShownMedia("img1", null, MediaShape.IMAGE, "image/jpeg", 1_000, "img1.jpg")
        val pictured =
            ChatItem.Message("p1", ShownMessage(Sender.User, "", wallAt(3), Delivery.DELIVERED, media = listOf(image)))
        val streaming =
            ChatItem.Message("s1", ShownMessage(Sender.Agent, "Still writing", null, Delivery.NONE, streaming = true))
        showing(listOf(streaming, pictured, ANSWER, ASKED))
        val label = rule.activity.getString(R.string.chat_reply)
        val replies =
            SemanticsMatcher("has the Reply action") { node ->
                node.config
                    .getOrNull(SemanticsActions.CustomActions)
                    .orEmpty()
                    .any { it.label == label }
            }
        rule.onAllNodes(replies).assertCountEquals(2)
        rule.onNode(replies and hasText(ASKED_WORDS)).assertExists()
        val answer = rule.onNode(replies and hasText(ANSWER_WORDS)).fetchSemanticsNode()
        val reply = answer.config[SemanticsActions.CustomActions].single { it.label == label }
        rule.runOnIdle { assertTrue(reply.action()) }
        assertEquals(listOf(ANSWER.message), replied)
    }

    @Test
    fun `under reduce-motion the bubble does not move and the swipe still replies`() {
        showing(listOf(ANSWER, ASKED), reduced = true)
        val rest = leftOf(ANSWER_WORDS)
        val bubble = rule.onNodeWithText(ANSWER_WORDS)
        bubble.dragging(px(REPLY_SWIPE) * 2)
        rule.waitForIdle()
        assertEquals("the bubble stays", rest, leftOf(ANSWER_WORDS), 0.5f)
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, shadowOf(view).lastHapticFeedbackPerformed())
        bubble.lifted()
        rule.waitForIdle()
        assertEquals(listOf(ANSWER.message), replied)
    }

    @Test
    fun `the owner's bubble with a leading quote draws the chip and the question, and TalkBack hears Quoting first`() {
        val quote = ReplyQuote(listOf(ANSWER_WORDS, "Raised it to 120 s.", "It finished in 84 s.", "Done."), false)
        val asked = ASKED.copy(message = ASKED.message.copy(text = withQuote(quote, "Why 60?")))
        showing(listOf(asked, ANSWER))
        rule.onAllNodesWithText("> ", substring = true, useUnmergedTree = true).assertCountEquals(0)
        val quoting = rule.activity.getString(R.string.chat_quoted, shownLines(quote.lines).joinToString("\n"))
        val words =
            rule
                .onNode(hasText("Why 60?"))
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .map { it.text }
        assertTrue("TalkBack hears $words", words.indexOf(quoting) in 0 until words.indexOf("Why 60?"))
        assertTrue("$quoting ends the third line marked", quoting.endsWith("It finished in 84 s.$QUOTE_MARK"))
    }

    @Test
    fun `a captioned media row of the owner's draws its caption's quote`() {
        val image = ShownMedia("img1", null, MediaShape.IMAGE, "image/jpeg", 1_000, "img1.jpg")
        val quote = ReplyQuote(listOf(ANSWER_WORDS), false)
        val captioned =
            ChatItem.Message(
                "p1",
                ShownMessage(
                    Sender.User,
                    withQuote(quote, "this one?"),
                    wallAt(3),
                    Delivery.DELIVERED,
                    GroupPosition.Single,
                    media = listOf(image),
                ),
            )
        showing(listOf(captioned, ANSWER))
        rule.onAllNodesWithText("> ", substring = true, useUnmergedTree = true).assertCountEquals(0)
        val words =
            rule
                .onNode(hasText("this one?"))
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .map { it.text }
        val quoting = rule.activity.getString(R.string.chat_quoted, ANSWER_WORDS)
        assertTrue("TalkBack hears $words", words.indexOf(quoting) in 0 until words.indexOf("this one?"))
    }

    /** The route over a real ViewModel whose cache holds the answer and the question; a moved round makes it again. */
    private fun routed(): Pair<ChatViewModel, MutableIntState> {
        val store = FakeChatStore(rows = listOf(userRow(1, ASKED_WORDS, 1), agentRow(2, ANSWER_WORDS, 2)))
        val model =
            ChatViewModel(fakeParts(sample(), FakeChatSession(store), store, background), SavedStateHandle())
        val round = mutableIntStateOf(0)
        rule.setContent {
            view = LocalView.current
            FermixTheme {
                key(round.intValue) {
                    ChatRoute(model, ChatNavigation(onBack = {}, onInstance = {}, showing = { true }))
                }
            }
        }
        rule.waitForIdle()
        return model to round
    }

    @Test
    fun `the strip reads the name and the quote's first line, its close drops the reply, and the field takes focus`() {
        val (model, _) = routed()
        rule.onNodeWithText(ANSWER_WORDS).dragging(px(REPLY_SWIPE) * 2).lifted()
        rule.waitForIdle()
        assertEquals(
            listOf(ANSWER_WORDS),
            model.composer.reply.held.value
                ?.quote
                ?.lines,
        )
        val name = rule.activity.getString(R.string.chat_replying_to, sample().title)
        rule.onNode(hasText(name) and hasText(ANSWER_WORDS)).assertExists()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_reply_drop)).performClick()
        rule.waitForIdle()
        assertNull(model.composer.reply.held.value)
        rule.onAllNodesWithText(name).assertCountEquals(0)
    }

    @Test
    fun `the owner's own message is replied to as theirs`() {
        val (model, _) = routed()
        rule.onNodeWithText(ASKED_WORDS).dragging(px(REPLY_SWIPE) * 2).lifted()
        rule.waitForIdle()
        assertEquals(
            Sender.User,
            model.composer.reply.held.value
                ?.sender,
        )
        val yours = rule.activity.getString(R.string.chat_replying_to_you)
        rule.onNode(hasText(yours) and hasText(ASKED_WORDS)).assertExists()
    }

    @Test
    fun `a rotation keeps the reply, as a second route over the same ViewModel draws its strip`() {
        val (model, round) = routed()
        rule.onNodeWithText(ANSWER_WORDS).dragging(px(REPLY_SWIPE) * 2).lifted()
        rule.waitForIdle()
        val held = model.composer.reply.held.value
        round.intValue += 1
        rule.waitForIdle()
        assertEquals(held, model.composer.reply.held.value)
        val name = rule.activity.getString(R.string.chat_replying_to, sample().title)
        rule.onNode(hasText(name) and hasText(ANSWER_WORDS)).assertExists()
    }
}
