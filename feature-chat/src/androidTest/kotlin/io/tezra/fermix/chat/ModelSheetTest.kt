package io.tezra.fermix.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.tezra.fermix.protocol.ClientEvent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The model switcher on a device (design section 8.6): the composer's chip opens the "Model" sheet, which pulls
 * the daemon's models; a pick sends `command{model}` with the model's route and closes the sheet.
 */
class ModelSheetTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = rule.activity.getString(id, *args)

    @Test
    fun the_chip_opens_the_sheet_and_a_pick_sends_the_model_command() {
        val session = rule.activity.rig.session
        rule.onNodeWithContentDescription(string(R.string.chat_model_chip, "GPT-6 Astra")).performClick()
        rule.awaitAppFocus(rule.activity)
        val title = string(R.string.chat_model_title)
        rule.waitUntil("the sheet opened", STEP_MILLIS) {
            rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(string(R.string.chat_model_default, "GPT-6 Astra")).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.chat_model_trait, "GPT-6 Luna", "fast, cheaper")).performClick()
        rule.waitUntil("the command went", STEP_MILLIS) { session.sent.value.isNotEmpty() }
        assertEquals(
            listOf<ClientEvent>(ClientEvent.Command("${MODEL_PICK_PREFIX}m1", PROFILE, "model", "codex/gpt-6-luna")),
            session.sent.value,
        )
        assertEquals(1, session.pulls.value)
        rule.waitUntil("the sheet closed", STEP_MILLIS) {
            rule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
    }
}
