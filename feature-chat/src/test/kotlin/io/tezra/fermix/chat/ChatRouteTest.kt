package io.tezra.fermix.chat

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.TurnEffect
import io.tezra.fermix.session.TurnOutcome
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** No haptic played yet, as Robolectric's view records it. */
private const val NO_HAPTIC = -1

/**
 * The screen's own hand on what it sends and plays (design sections 13.1, 13.5 and 13.8): a failed turn runs
 * again only from the owner's tap, an answer that arrives plays `CLOCK_TICK` and is read once, and the banner
 * that taps is a 48 dp target. JUnit 4, in Roborazzi's activity, on the compact window of @FermixPreviews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChatRouteTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun backgroundEnds() = background.cancel()

    @Test
    fun `a failed turn runs again only when the owner taps Run again`() {
        val store = FakeChatStore(rows = listOf(userRow(1, "export it", clientMsgId = "m9")))
        val session = FakeChatSession(store)
        val failed =
            listOf(
                TurnEffect.CardShown("turn-m9"),
                TurnEffect.TurnEnded("turn-m9", TurnOutcome.Failed("turn_failed", "it broke")),
            ).fold(ChatLive()) { live, effect ->
                live.after(
                    SessionEvent.Turn(effect, daemonSpeaking = false),
                    Moment(0L, wallAt(1), Candidate.Scope.LAN, 1uL),
                )
            }
        val parts = fakeParts(sample(), session, store, background).copy(live = MutableStateFlow(failed))
        val model = ChatViewModel(parts)
        rule.setContent {
            FermixTheme { ChatRoute(model, ChatNavigation(onBack = {}, onInstance = {}, showing = { true })) }
        }
        val runAgain = rule.activity.getString(R.string.chat_run_again)
        rule.mainClock.advanceTimeBy(10 * 60_000L)
        rule.waitForIdle()
        rule.onNodeWithText(runAgain).assertExists()
        assertEquals(emptyList<Any>(), session.retried.value)
        assertEquals(emptyList<Any>(), session.sent.value)
        rule.onNodeWithText(runAgain).performClick()
        rule.waitForIdle()
        assertEquals(listOf(msg("m9", "export it") to "m1"), session.retried.value)
    }

    @Test
    fun `an answer that arrives on screen ticks once and is read once, and the ones the chat opened with do not`() {
        var arrived by mutableStateOf(listOf(Arrival("turn-1", "Held as the chat opened")))
        var shown by mutableStateOf(true)
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            FermixTheme { Arrivals(arrived, shown) }
        }
        rule.waitForIdle()
        assertEquals(NO_HAPTIC, shadowOf(view).lastHapticFeedbackPerformed())
        rule.onAllNodes(hasContentDescription("Held as the chat opened")).assertCountEquals(0)

        arrived = arrived + Arrival("turn-2", "The **worker** restarts")
        rule.waitForIdle()
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(view).lastHapticFeedbackPerformed())
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        rule.onNode(hasContentDescription("The worker restarts")).assert(polite)

        // Another haptic since; the same answers again, off screen and back, play nothing.
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(view).lastHapticFeedbackPerformed())

        // One that arrives while the chat is off screen is let go unplayed.
        shown = false
        arrived = arrived + Arrival("turn-3", "Off screen")
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(view).lastHapticFeedbackPerformed())
        rule.onAllNodes(hasContentDescription("Off screen")).assertCountEquals(0)
    }

    @Test
    fun `the unreachable banner's tap is a 48 dp target, and the offline line, which does not tap, stays thin`() {
        rule.setContent {
            FermixTheme {
                Column {
                    BannerLine(Banner.UNREACHABLE, "suj-mbp", onUnreachable = {})
                    BannerLine(Banner.OFFLINE, "suj-mbp", onUnreachable = {})
                }
            }
        }
        rule.onNode(hasClickAction()).assertHeightIsAtLeast(48.dp)
        rule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }
}
