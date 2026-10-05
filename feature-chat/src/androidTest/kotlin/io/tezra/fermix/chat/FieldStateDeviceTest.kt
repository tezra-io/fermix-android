package io.tezra.fermix.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.Test

/**
 * A paste's lines: 300,000 characters, which a saved state carries as some 1.2 MB, past the binder's 1 MB for a
 * stopped activity's state (TransactionTooLargeException, a stopped app).
 */
private const val PASTED_LINES = 10_000

/**
 * Words the owner pastes into the field, past what the binder carries in a stopped activity's saved state: the field
 * keeps them out of that state, as the chat holds them (ChatComposer, and its draft), so the app outlives going to the
 * background with them and keeps every word, as it is stopped and as it is made again.
 */
class FieldStateDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    @Test
    fun a_paste_past_what_the_binder_carries_outlives_the_app_stopping_and_being_made_again() {
        val words = "a pasted line of a long log\n".repeat(PASTED_LINES)
        rule.onNode(hasSetTextAction()).performTextInput(words)
        awaitField("the field holds the paste", words)
        val scenario = rule.activityRule.scenario
        // Stopped as going home stops it: its saved state goes to the system then, through the binder.
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitField("the field kept the paste once the app was back", words)
        scenario.recreate()
        awaitField("the field holds the paste once the activity was made again", words)
    }

    /** Waits, bounded, for the chat's words and the field's own to be [words]. */
    private fun awaitField(
        what: String,
        words: String,
    ) {
        rule.waitUntil(what, STEP_MILLIS) {
            val shown =
                rule
                    .onNode(hasSetTextAction())
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.EditableText)
                    ?.text
            rule.activity.model.composer.field.value.text == words && shown == words
        }
    }
}
