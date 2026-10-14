package io.tezra.fermix

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.data.ChatRef
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.MAIN_PROFILE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** How long the activity may take to read the records and the settings, which DataStore does off the main thread. */
private const val SETTLE_MILLIS = 10_000L

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The launcher starting the activity with no chat kept from before, as after the process was stopped (the owner,
 * 2026-10-13): with one Fermix paired the app opens in its chat, the Chats list beneath it, and draws nothing before
 * the chat; with two, on the list; with none, on Welcome.
 */
@RunWith(RobolectricTestRunner::class)
class ColdStartTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app: FermixApplication = ApplicationProvider.getApplicationContext()

    private fun start(vararg records: Instance): ActivityScenario<MainActivity> {
        runBlocking { records.forEach { app.services.instances.upsert(it) } }
        assertNull("no chat is kept from before", lastChat())
        return ActivityScenario.launch(Intent.makeMainActivity(ComponentName(app, MainActivity::class.java)))
    }

    private fun navigator(scenario: ActivityScenario<MainActivity>): AppNavigator {
        var navigator: AppNavigator? = null
        scenario.onActivity { navigator = ViewModelProvider(it)[AppNavigator::class.java] }
        return checkNotNull(navigator)
    }

    private fun lastChat(): ChatRef? =
        runBlocking {
            app.services.settings.settings
                .first()
                .lastChat
        }

    /** The chat kept, once the main thread has run what it was handed: the restore and its watch run there. */
    private fun lastChatOnceIdle(): ChatRef? {
        rule.waitForIdle()
        return lastChat()
    }

    private fun shows(text: String) =
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** Whether the Chats list is composed: its "+" in the bar. */
    private fun listComposed(): Boolean =
        rule.onAllNodesWithContentDescription("Add Fermix").fetchSemanticsNodes().isNotEmpty()

    private fun listShows() = rule.waitUntil(SETTLE_MILLIS) { listComposed() }

    @Test
    fun `a cold start with one Fermix paired opens its chat, with the list beneath`() {
        val record = pairedForShare(1)
        val greeting = "Say hello to ${record.label}."
        start(record).use { scenario ->
            // The Chat screen itself: its bar names the Fermix, and its empty chat greets it.
            shows(greeting)
            shows(record.label)
            assertEquals(listOf<NavKey>(ChatKey(record.id, MAIN_PROFILE)), navigator(scenario).above.value)
            // The system's Back: the Chats list, the Fermix a row of it.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(greeting).fetchSemanticsNodes().isEmpty() }
            listShows()
            shows(record.label)
            assertEquals(emptyList<NavKey>(), navigator(scenario).above.value)
        }
    }

    @Test
    fun `a cold start with two paired and no chat kept opens on the list`() {
        val first = pairedForShare(1)
        val second = pairedForShare(2)
        start(first, second).use { scenario ->
            listShows()
            // Only the restore's watch keeps the chat on top, so once the chat opened now is kept the restore has run;
            // then the chat is the one screen over the list, nothing restored under it or in its place.
            val opened = ChatKey(second.id, MAIN_PROFILE)
            scenario.onActivity { ViewModelProvider(it)[AppNavigator::class.java].open(opened) }
            rule.waitUntil(SETTLE_MILLIS) { lastChat() == ChatRef(second.id, MAIN_PROFILE) }
            assertEquals(listOf<NavKey>(opened), navigator(scenario).above.value)
        }
    }

    @Test
    fun `a cold start with one Fermix draws the chat as its first frame and never the list`() {
        val record = pairedForShare(1)
        val greeting = "Say hello to ${record.label}."
        // Frame by frame: the clock moves only as the test steps it, and each frame's composition is looked at.
        rule.mainClock.autoAdvance = false
        start(record).use { scenario ->
            val deadline = System.nanoTime() + SETTLE_MILLIS * NANOS_PER_MILLI
            var frame = 0
            while (rule.onAllNodesWithText(greeting).fetchSemanticsNodes().isEmpty()) {
                assertFalse("frame $frame composes the Chats list", listComposed())
                assertTrue("the chat is drawn within $SETTLE_MILLIS ms", System.nanoTime() < deadline)
                rule.mainClock.advanceTimeByFrame()
                frame++
            }
            assertFalse("the chat's frame composes the Chats list", listComposed())
            assertEquals(listOf<NavKey>(ChatKey(record.id, MAIN_PROFILE)), navigator(scenario).above.value)
        }
    }

    @Test
    fun `a cold start with nothing paired still draws Welcome`() {
        start().use { scenario ->
            shows("Get started")
            assertTrue(navigator(scenario).restored.value)
            assertEquals(emptyList<NavKey>(), navigator(scenario).above.value)
        }
    }

    @Test
    fun `a link that arrives while the restore reads still lands on its chat and the last chat is saved`() {
        val first = pairedForShare(1)
        val second = pairedForShare(2)
        val linked = ChatKey(second.id, MAIN_PROFILE)
        val callers = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            linkWhileReading(start(first, second), callers, linked)
        } finally {
            callers.cancel()
        }
    }

    /**
     * The restore's first call cancelled while it reads, [linked]'s link then, and where the activity lands. The test's
     * own call stands in for FollowIntents' first one, which a link landing as the restore reads cancels by restarting
     * the effect: the restart itself is not driven. The activity's effect has not called the restore yet, as no frame
     * has passed since the launch to tell it the records are known; when one does, the link is already pending, so it
     * runs once, with the link, and waits on the restore the test's call started (a field read by reflection said so
     * once, kept out of the test).
     */
    private fun linkWhileReading(
        started: ActivityScenario<MainActivity>,
        callers: CoroutineScope,
        linked: ChatKey,
    ) {
        started.use { scenario ->
            var activity: MainActivity? = null
            scenario.onActivity { activity = it }
            val navigator = navigator(scenario)
            // FollowIntents' first call, cancelled while it reads as the effect's restart would cancel it: the reads
            // answer on the main thread, which runs nothing until the test idles it.
            val caller = callers.launch { navigator.restore(link = null) }
            caller.cancel()
            assertFalse(navigator.restored.value)
            val link = chatIntent(app, linked.instanceId, linked.profileId)
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(checkNotNull(activity), link)
            shows("Say hello to suj-mbp.")
            assertEquals(listOf<NavKey>(linked), navigator.above.value)
            assertTrue(navigator.restored.value)
            assertTrue(caller.isCancelled)
            // Only the restore's watch keeps the chat on top.
            rule.waitUntil(SETTLE_MILLIS) { lastChatOnceIdle() == ChatRef(linked.instanceId, linked.profileId) }
        }
    }
}
