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
import io.tezra.fermix.data.ChatRef
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.MAIN_PROFILE
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** How long the activity may take to read the records and the settings, which DataStore does off the main thread. */
private const val SETTLE_MILLIS = 10_000L

/**
 * The launcher starting the activity with no chat kept from before, as after the process was stopped (the owner,
 * 2026-10-13): with one Fermix paired the app opens in its chat, the Chats list beneath it; with two, on the list.
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

    private fun shows(text: String) =
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** The Chats list, its "+" in the bar. */
    private fun listShows() =
        rule.waitUntil(SETTLE_MILLIS) {
            rule.onAllNodesWithContentDescription("Add Fermix").fetchSemanticsNodes().isNotEmpty()
        }

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
}
