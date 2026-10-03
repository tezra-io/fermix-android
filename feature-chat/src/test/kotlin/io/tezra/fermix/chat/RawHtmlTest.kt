package io.tezra.fermix.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.instance.Link
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TimelineRow
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import io.tezra.fermix.transport.Candidate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Raw HTML never renders (design section 8.3): an answer whose `text_done` carries a script block, an inline
 * tag and images shows each as the text it is, in the bubble the screen draws, sealed and once its row has
 * landed. JUnit 4, in Roborazzi's activity, on the compact window of @FermixPreviews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class RawHtmlTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val turn = "turn-m1"

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
            nowMono = { 0L },
            text = TextActions({}, {}),
        )

    /** The answer the daemon sealed: what `text_done` carried, its row's content once it lands. */
    private val answer =
        "<script>alert(\"x\")</script>\n\n" +
            "Tap <b>raw</b> to see it. <img src=\"https://example.com/x.png\"> " +
            "![chart](https://example.com/chart.png)"

    private fun sealedLive(): ChatLive {
        val moment = Moment(0L, wallAt(1), Candidate.Scope.LAN, 1uL)
        return listOf(
            TurnEffect.BubbleOpened(turn, 1, fromCard = false),
            TurnEffect.BubbleText(turn, 1, answer),
            TurnEffect.BubbleSealed(turn, 1, 2uL),
            TurnEffect.TurnEnded(turn, TurnOutcome.Completed),
        ).fold(ChatLive()) { live, effect -> live.after(SessionEvent.Turn(effect, daemonSpeaking = false), moment) }
    }

    private fun show(inputs: ChatInputs) {
        val header = ChatHeader(sample(), Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true), false)
        val state =
            chatScreenState(ChatFacts(header, null), inputs, seenUpTo = null, limit = 60, loadingOlder = false)
        rule.setContent { FermixTheme { ChatScreen(ChatUi(state, TextFieldValue(""), palette = false), actions) } }
    }

    private fun inputs(
        rows: List<TimelineRow>,
        live: ChatLive,
    ) = ChatInputs(
        rows = rows,
        outbox = emptyList(),
        bridged = emptyList(),
        live = live,
        connected = true,
        unreadAt = null,
        requests = emptyMap(),
        profileId = PROFILE,
        nowWall = wallAt(2),
        zone = UTC,
    )

    private fun assertLiteral() {
        rule.onNodeWithText("<script>alert(\"x\")</script>", substring = true).assertExists()
        rule.onNodeWithText("Tap <b>raw</b> to see it.", substring = true).assertExists()
        rule.onNodeWithText("<img src=\"https://example.com/x.png\">", substring = true).assertExists()
        rule.onNodeWithText("![chart](https://example.com/chart.png)", substring = true).assertExists()
    }

    @Test
    fun `a sealed answer's script, tags and images show as the text they are`() {
        show(inputs(rows = listOf(userRow(1, "show me", minutes = 0)), live = sealedLive()))
        assertLiteral()
    }

    @Test
    fun `the landed row's script, tags and images show as the text they are`() {
        show(inputs(rows = listOf(agentRow(2, answer, minutes = 1), userRow(1, "show me", minutes = 0)), ChatLive()))
        assertLiteral()
    }
}
