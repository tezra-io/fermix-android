package io.tezra.fermix

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.chat.ChatViewModel
import io.tezra.fermix.chat.Shared
import io.tezra.fermix.data.ChatRef
import io.tezra.fermix.data.Instance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** How long the activity may take to read the records and the settings, which DataStore does off the main thread. */
private const val SETTLE_MILLIS = 10_000L

private const val WORDS = "look at this"

/**
 * A Direct Share that starts the activity, as after the process was killed in the background (design section 13.6):
 * the share is handed over before the activity is made, and the chat it names opens, the list beneath it, over the
 * chat the app kept from before, which is another Fermix's.
 */
@RunWith(RobolectricTestRunner::class)
class ShareColdStartTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app: FermixApplication = ApplicationProvider.getApplicationContext()

    private fun above(scenario: ActivityScenario<MainActivity>): List<NavKey> {
        var keys: List<NavKey> = emptyList()
        scenario.onActivity { keys = ViewModelProvider(it)[AppNavigator::class.java].above.value }
        return keys
    }

    private fun draft(
        scenario: ActivityScenario<MainActivity>,
        record: Instance,
    ): String {
        var text = ""
        scenario.onActivity {
            text =
                ViewModelProvider(it)["chat:${record.id}:main", ChatViewModel::class.java]
                    .composer.field.value.text
        }
        return text
    }

    private fun shareState(scenario: ActivityScenario<MainActivity>): ShareState? {
        var state: ShareState? = null
        scenario.onActivity { state = ViewModelProvider(it)[ShareModel::class.java].state.value }
        return state
    }

    private fun lastChat(): ChatRef? =
        runBlocking {
            app.services.settings.settings
                .first()
                .lastChat
        }

    @Test
    fun `a Direct Share that starts the activity opens the chat it names, not the chat kept from before`() {
        val kept = pairedForShare(1)
        val named = pairedForShare(2)
        runBlocking {
            app.services.instances.upsert(kept)
            app.services.instances.upsert(named)
            app.services.settings.setLastChat(ChatRef(kept.id, "main"))
        }
        val shared = Shared(emptyList(), WORDS, emptyList(), past = 0)
        app.services.shares.value = Share(shared, conversationId(named.id, "main"))
        val start = Intent.makeMainActivity(ComponentName(app, MainActivity::class.java))
        ActivityScenario.launch<MainActivity>(start).use { scenario ->
            // Taken from the hand and landed: its chat's model, made as it landed, holds its words.
            rule.waitUntil(SETTLE_MILLIS) {
                app.services.shares.value == null && shareState(scenario) == ShareState.None
            }
            assertEquals(WORDS, draft(scenario, named))
            // The restore of the chat kept has run once the chat on top is kept again: the share's, or the old one.
            val keptChat = ChatKey(kept.id, "main")
            rule.waitUntil(SETTLE_MILLIS) { lastChat()?.instanceId == named.id || above(scenario) == listOf(keptChat) }
            rule.waitForIdle()
            assertEquals(listOf(ChatKey(named.id, "main")), above(scenario))
        }
    }
}
