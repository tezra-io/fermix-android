package io.tezra.fermix.chats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A row's long-press on a device: section 13.4's menu, Move to top · Rename · Details · Unpair…, in that order. */
class LongPressTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatsTestActivity>()

    @Test
    fun a_rows_long_press_opens_move_to_top_rename_details_and_unpair_in_that_order() {
        rule.onNodeWithText("Dev").performTouchInput { longClick() }
        val labels = listOf("Move to top", "Rename", "Details", "Unpair…")
        for (label in labels) rule.onNodeWithText(label).assertIsDisplayed()
        val tops =
            labels.map {
                rule
                    .onNodeWithText(it)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        val ordered = tops.zipWithNext().all { (above, below) -> above < below }
        assertTrue("the menu reads $labels from the top: $tops", ordered)
    }
}
