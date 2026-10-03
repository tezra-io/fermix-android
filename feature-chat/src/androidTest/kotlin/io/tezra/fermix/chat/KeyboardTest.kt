package io.tezra.fermix.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import io.tezra.fermix.protocol.ClientEvent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * A hardware keyboard in the composer (design section 13.6): Shift+Enter puts a newline in the field and sends
 * nothing; Enter sends the field's words, both lines, and empties it.
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
}
