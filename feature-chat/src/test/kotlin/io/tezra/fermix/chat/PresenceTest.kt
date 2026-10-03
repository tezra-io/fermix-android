package io.tezra.fermix.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A screen's lifecycle the test moves, from its own thread. */
private class MovedLifecycle : LifecycleOwner {
    val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)

    override val lifecycle: Lifecycle get() = registry
}

/** The window as [real] says, but whether it has the focus, which [focused] says. */
private class FocusedWindow(
    real: WindowInfo,
    private val focused: () -> Boolean,
) : WindowInfo by real {
    override val isWindowFocused: Boolean get() = focused()
}

/**
 * The screen's side of PUSH-2 (design section 10, onboarding §5): the Chat screen over its ViewModel reports
 * to its presence the newest row its composed state holds, while it is resumed with its window focused, and none
 * otherwise, so the announcer answers ON_SCREEN only for a row the list holds. JUnit 4, in Roborazzi's activity,
 * on the compact window of @FermixPreviews, its lifecycle and window focus the test's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class PresenceTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun backgroundEnds() = background.cancel()

    @Test
    fun `the screen reports the newest row its state holds, only while resumed with its window focused`() {
        val store = FakeChatStore(rows = listOf(agentRow(2, "b", minutes = 1), userRow(1, "a")))
        val presence = FakePresence()
        val parts = fakeParts(sample(), FakeChatSession(store), store, background)
        val model = ChatViewModel(parts.copy(presence = presence))
        val lifecycle = MovedLifecycle().apply { registry.currentState = Lifecycle.State.RESUMED }
        var focused by mutableStateOf(true)
        rule.setContent {
            val real = LocalWindowInfo.current
            val window = remember(real) { FocusedWindow(real) { focused } }
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalWindowInfo provides window) {
                FermixTheme { ChatRoute(model, ChatNavigation(onBack = {}, onInstance = {}, showing = { true })) }
            }
        }
        val held = mutableSetOf<ULong>()

        fun settled(): ULong? {
            rule.waitForIdle()
            model.state.value
                ?.newestSeq
                ?.let(held::add)
            return presence.reports.value.lastOrNull()
        }
        assertEquals(2uL, settled())
        assertEquals(2uL, model.state.value?.newestSeq)
        store.rows.update { listOf(agentRow(3, "c", minutes = 2)) + it }
        assertEquals(3uL, settled())

        lifecycle.registry.currentState = Lifecycle.State.STARTED
        assertNull(settled())
        lifecycle.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(3uL, settled())
        focused = false
        assertNull(settled())
        // A row that lands while the screen is not on screen is reported only once it is again.
        store.rows.update { listOf(agentRow(4, "d", minutes = 3)) + it }
        assertNull(settled())
        focused = true
        assertEquals(4uL, settled())

        val reports = presence.reports.value
        assertTrue("every report names a row the state held: $reports", reports.all { it == null || it in held })
    }
}
