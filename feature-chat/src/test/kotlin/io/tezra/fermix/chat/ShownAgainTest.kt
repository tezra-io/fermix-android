package io.tezra.fermix.chat

import android.os.Looper
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** No haptic played yet, as Robolectric's view records it. */
private const val NO_HAPTIC = -1

/** Rows in the chat sent home: enough that its list scrolls. */
private const val ROWS = 40

/** How long a test waits for what the chat's state brings to be drawn. */
private const val DRAWN_WAIT_MILLIS = 5_000L

/**
 * A chat shown again once nothing has shown it for [STATE_GRACE_MS], its list no longer worked out and its state let
 * go, draws as it would had its list been worked out all along: what came meanwhile is there as it opens, so an
 * answer that came then arrives unplayed, and a screen that stayed composed (the app sent home and brought back)
 * keeps its place, never drawn without its state. JUnit 4, in Roborazzi's activity, on the compact window of
 * @FermixPreviews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ShownAgainTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun backgroundEnds() = background.cancel()

    /**
     * The grace run out on the main looper, where the chat's ViewModel waits it, from the end of the screen's
     * collection, which the composition's effects end once the test waits for them to be idle.
     */
    private fun graceRunsOut() {
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STATE_GRACE_MS + 1))
    }

    @Test
    fun `an answer that came while the chat was not shown is there as it opens again, and arrives unplayed`() {
        val store = FakeChatStore(rows = listOf(userRow(1, "Restart the worker", clientMsgId = "m9")))
        val live = MutableStateFlow(ChatLive())
        val parts = fakeParts(sample(), FakeChatSession(store), store, background).copy(live = live)
        val model = ChatViewModel(parts, SavedStateHandle())
        var open by mutableStateOf(true)
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            val real = LocalWindowInfo.current
            val window = remember(real) { FocusedWindow(real) { true } }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                FermixTheme {
                    if (open) ChatRoute(model, ChatNavigation(onBack = {}, onInstance = {}, showing = { true }))
                }
            }
        }
        rule.waitForIdle()
        open = false
        graceRunsOut()
        assertNull(model.state.value)
        val moment = Moment(0L, wallAt(1), Candidate.Scope.LAN, 1uL)
        live.value =
            listOf(
                TurnEffect.BubbleOpened("turn-m9", 1, fromCard = false),
                TurnEffect.BubbleText("turn-m9", 1, "The worker restarts"),
                TurnEffect.BubbleSealed("turn-m9", 1, 2uL),
                TurnEffect.TurnEnded("turn-m9", TurnOutcome.Completed),
            ).fold(ChatLive()) { folded, effect ->
                folded.after(SessionEvent.Turn(effect, daemonSpeaking = false), moment)
            }
        store.rows.update { listOf(agentRow(2, "The worker restarts", minutes = 1)) + it }
        open = true
        rule.waitUntil(DRAWN_WAIT_MILLIS) {
            rule.onAllNodesWithText("The worker restarts").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        assertEquals(NO_HAPTIC, shadowOf(view).lastHapticFeedbackPerformed())
    }

    @Test
    fun `a screen sent home and brought back past the grace keeps where its list was`() {
        val store = FakeChatStore(rows = (1..ROWS).map { agentRow(it, "row $it", minutes = it.toLong()) })
        val model = ChatViewModel(fakeParts(sample(), FakeChatSession(store), store, background), SavedStateHandle())
        val lifecycle = MovedLifecycle().apply { registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                FermixTheme { ChatRoute(model, ChatNavigation(onBack = {}, onInstance = {}, showing = { true })) }
            }
        }
        rule.waitForIdle()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("row 1"))
        rule.onNodeWithText("row 1").assertIsDisplayed()
        lifecycle.registry.currentState = Lifecycle.State.CREATED
        graceRunsOut()
        assertNull(model.state.value)
        store.rows.update { listOf(agentRow(ROWS + 1, "row ${ROWS + 1}", minutes = 60)) + it }
        lifecycle.registry.currentState = Lifecycle.State.RESUMED
        rule.waitUntil(DRAWN_WAIT_MILLIS) { model.state.value != null }
        rule.waitForIdle()
        rule.onNodeWithText("row 1").assertIsDisplayed()
    }
}
