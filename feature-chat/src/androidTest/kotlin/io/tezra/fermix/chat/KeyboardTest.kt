package io.tezra.fermix.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import io.tezra.fermix.protocol.ClientEvent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The query the owner types on the keyboard: one row of the host's thread holds both words. */
private const val QUERY = "row 38"

/**
 * A hardware keyboard in the chat (design sections 13.6 and 13.7): Shift+Enter puts a newline in the field and
 * sends nothing; Enter sends the field's words, both lines, and empties it. Ctrl+F, pressed through the
 * system's input as a keyboard's keys come, opens search with its field taking the keys typed next, and the
 * query finds its row; without the daemon's `caps.search` the phone's own index answers, under the pinned
 * line, and nothing is asked of the session.
 */
class KeyboardTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun shift_enter_puts_a_newline_in_the_field_and_enter_sends_it() {
        val field = rule.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput("check the disk")
        field.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        field.assertTextEquals("check the disk\n", includeEditableText = true)
        assertEquals(emptyList<ClientEvent>(), rule.activity.rig.session.sent.value)
        field.performTextInput("then the logs")
        field.performKeyInput { pressKey(Key.Enter) }
        val session = rule.activity.rig.session
        rule.waitUntil("the message went", STEP_MILLIS) { session.sent.value.isNotEmpty() }
        val sent = session.sent.value.single() as ClientEvent.Msg
        assertEquals("check the disk\nthen the logs", sent.text)
        rule.waitUntil("the field emptied", STEP_MILLIS) {
            rule.activity.model.composer.field.value.text
                .isEmpty()
        }
    }

    @Test
    fun ctrl_f_on_a_hardware_keyboard_opens_search_and_the_typed_query_finds_its_row() {
        rule.onNode(hasSetTextAction()).performClick()
        rule.awaitAppFocus(rule.activity)
        shell("input keycombination KEYCODE_CTRL_LEFT KEYCODE_F")
        val searchField = hasContentDescription(rule.activity.getString(R.string.chat_search)) and hasSetTextAction()
        rule.waitUntil("search opened", STEP_MILLIS) { rule.onAllNodes(searchField).fetchSemanticsNodes().size == 1 }
        rule.onNode(searchField).assertIsFocused()
        shell("input text ${QUERY.replace(" ", "%s")}")
        val hit = hasText(QUERY, substring = true) and !hasSetTextAction()
        rule.waitUntil("the hit listed", STEP_MILLIS) { rule.onAllNodes(hit).fetchSemanticsNodes().size == 1 }
        rule.onNode(searchField).assertTextEquals(QUERY, includeEditableText = true)
        rule.onNodeWithText(rule.activity.getString(R.string.chat_search_cached_only)).assertIsDisplayed()
        assertEquals(emptyList<Pair<String, ULong?>>(), rule.activity.rig.session.searches.value)
    }
}
