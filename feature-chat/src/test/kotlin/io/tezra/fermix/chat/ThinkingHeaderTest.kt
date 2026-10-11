package io.tezra.fermix.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val POLITE = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

/**
 * The thinking card's header as TalkBack has it (the owner, 2026-10-10): the newest running tool's verb in the
 * phrase's place, read "{verb}, started" and never announced; "Fermix is thinking" the one live region; the tool no
 * chip while it is the header's, and a chip in its place once it stops, when the header is the phrase again; and the
 * card that opens again below a picture of its turn no live region, so the turn says "Fermix is thinking" once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ThinkingHeaderTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private var chips by mutableStateOf(
        listOf(LiveChip("file_read", running = false, status = "ok"), LiveChip("shell", running = true, status = null)),
    )

    private fun string(
        @StringRes id: Int,
        vararg args: Any,
    ): String = rule.activity.getString(id, *args)

    private fun show(key: String = "card:t1:1") {
        rule.setContent {
            FermixTheme {
                val card = LiveCard(0L, listOf("Reading the export job's config"), chips, speaking = true)
                ThinkingCard(ChatItem.Thinking(key, "t1", card, startedMono = 0L, seed = 7L), { 1_000L })
            }
        }
    }

    @Test
    fun `while a tool runs the header is its verb, read as started and never announced, and it is no chip`() {
        show()
        val shell = string(R.string.chat_verb_running_shell)
        val started = string(R.string.chat_chip_started, shell)
        rule.onNodeWithText(shell, useUnmergedTree = true).assert(hasContentDescription(started))
        rule.onAllNodesWithContentDescription(started, useUnmergedTree = true).assertCountEquals(1)
        val regions = rule.onAllNodes(POLITE, useUnmergedTree = true).fetchSemanticsNodes()
        val announced = regions.map { it.config[SemanticsProperties.ContentDescription].single() }
        assertEquals(listOf(string(R.string.chat_thinking_announcement)), announced)
        val files = string(R.string.chat_chip_finished, string(R.string.chat_verb_reading_files))
        rule.onAllNodesWithContentDescription(files, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `once the tool stops it is a chip in its place, and the header is the phrase again, hidden from TalkBack`() {
        show()
        chips = chips.map { it.copy(running = false, status = "ok") }
        rule.waitForIdle()
        val shell = string(R.string.chat_verb_running_shell)
        val finished = string(R.string.chat_chip_finished, shell)
        rule.onAllNodesWithContentDescription(finished, useUnmergedTree = true).assertCountEquals(1)
        // TalkBack has the chip's words as its description alone, and the header no longer names the tool.
        rule.onAllNodesWithText(shell).assertCountEquals(0)
        val started = string(R.string.chat_chip_started, shell)
        rule.onAllNodesWithContentDescription(started, useUnmergedTree = true).assertCountEquals(0)
        val phrase = rule.onNodeWithText(string(R.string.chat_indicator_opening), useUnmergedTree = true)
        val config = phrase.fetchSemanticsNode().config
        assertFalse(SemanticsProperties.ContentDescription in config)
        assertTrue(SemanticsProperties.HideFromAccessibility in config)
        rule.onAllNodes(POLITE, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `a card opened again below its turn's picture says nothing as it comes, the turn's card having said it once`() {
        show(key = "card:t1:1.1")
        rule.onAllNodes(POLITE, useUnmergedTree = true).assertCountEquals(0)
        val thinking = string(R.string.chat_thinking_announcement)
        rule.onAllNodesWithContentDescription(thinking, useUnmergedTree = true).assertCountEquals(1)
    }
}
