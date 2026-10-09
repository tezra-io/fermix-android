package io.tezra.fermix.chats

import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.Arrival
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.instance.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Chats list as onboarding leaves for it (the M51 update's 7.4): the new Fermix's row rises 12 dp in after 250 ms,
 * over 300 ms, the others standing, and the list says it has taken it as the rise starts; a rotation mid-rise stands
 * the row in its place; under Remove animations it stands from the first frame. On a test clock that moves only when
 * told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChatsArrivalTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val old = sample(1)
    private val new = sample(2, host = LINUX_HOST)
    private var arrived = 0

    /** The Fermix onboarding names, until the list says it has taken it, as the ViewModel holds it. */
    private var arriving by mutableStateOf<String?>(null)
    private var restorations by mutableIntStateOf(0)
    private var composed by mutableStateOf(true)
    private var registry: SaveableStateRegistry? = null
    private var saved: Map<String, List<Any?>>? = null

    private fun row(record: io.tezra.fermix.data.Instance) =
        ChatRow(record, "main", agentName = null, dev = false, Link.Connecting, RowLine.Empty, time = null, unread = 0)

    /**
     * The list, its saved state in a registry of the test's own, so that it can be restored as a rotation does, first
     * composed with the new Fermix named, as onboarding's end names it, or, not [named], with none.
     */
    private fun show(
        scale: Float = 1f,
        named: Boolean = true,
    ) {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        rule.mainClock.autoAdvance = false
        arriving = if (named) new.id else null
        val actions = ChatsActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        rule.setContent {
            val activity = checkNotNull(LocalSaveableStateRegistry.current)
            val saving = remember(restorations) { SaveableStateRegistry(saved, activity::canBeSaved) }
            registry = saving
            CompositionLocalProvider(LocalSaveableStateRegistry provides saving) {
                FermixTheme(darkTheme = false) {
                    if (composed) {
                        val ui = ChatsUi(listOf(row(old), row(new)), emptyList())
                        ChatsScreen(ui, actions, arriving = arriving) {
                            arrived++
                            arriving = null
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    /** The list's state saved, the list disposed, and composed anew from what it saved, as a rotation does. */
    private fun restore() {
        saved = checkNotNull(registry).performSave()
        composed = false
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        restorations++
        composed = true
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun top(host: String): Dp = rule.onNodeWithText(host).getUnclippedBoundsInRoot().top

    @Test
    fun `the new Fermix's row rises 12 dp in after 250 ms, over 300 ms, the list saying it has taken it at once`() {
        show()
        val standing = top(HOST)
        val low = top(LINUX_HOST)
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1, arrived)
        rule.mainClock.advanceTimeBy(Arrival.DELAY_MILLIS - 32L)
        assertEquals(low, top(LINUX_HOST))
        rule.mainClock.advanceTimeBy(Arrival.MILLIS / 2L)
        val rising = top(LINUX_HOST)
        assertTrue("the row at $rising, from $low", rising < low && rising > low - Arrival.rise)
        rule.mainClock.advanceTimeBy(Arrival.MILLIS.toLong())
        assertEquals(low - Arrival.rise, top(LINUX_HOST))
        assertEquals(standing, top(HOST))
        assertEquals(1, arrived)
        assertEquals(12.dp, Arrival.rise)
    }

    @Test
    fun `a rotation mid-rise stands the new row in its place, and it does not rise again`() {
        show()
        val low = top(LINUX_HOST)
        rule.mainClock.advanceTimeBy(Arrival.DELAY_MILLIS + Arrival.MILLIS / 2L)
        val rising = top(LINUX_HOST)
        assertTrue("the row at $rising, from $low", rising < low && rising > low - Arrival.rise)
        restore()
        val placed = top(LINUX_HOST)
        assertEquals(low - Arrival.rise, placed)
        repeat(30) {
            assertEquals(placed, top(LINUX_HOST))
            rule.mainClock.advanceTimeByFrame()
        }
        assertEquals(1, arrived)
    }

    @Test
    fun `a Fermix named once the list is drawn, as a back swipe out of onboarding draws it, stands, taken at once`() {
        // The swipe draws the list, the new row in it, before it is let go; let go, back names the new Fermix.
        show(named = false)
        rule.mainClock.advanceTimeBy(Arrival.DELAY_MILLIS + Arrival.MILLIS + 16L)
        val placed = top(LINUX_HOST)
        assertEquals(0, arrived)
        arriving = new.id
        rule.waitForIdle()
        // A rise would drop the row 12 dp as it hides it, and bring it up as it fades it in: the row holds still.
        repeat(Arrival.DELAY_MILLIS / 16 + Arrival.MILLIS / 16 + 4) {
            assertEquals(placed, top(LINUX_HOST))
            rule.mainClock.advanceTimeByFrame()
        }
        assertEquals(1, arrived)
        assertEquals(null, arriving)
    }

    @Test
    fun `under Remove animations the new row stands in its place from the first frame`() {
        show(scale = 0f)
        val stands = top(LINUX_HOST)
        rule.mainClock.advanceTimeBy(Arrival.DELAY_MILLIS + Arrival.MILLIS + 16L)
        assertEquals(stands, top(LINUX_HOST))
        assertEquals(1, arrived)
    }
}
