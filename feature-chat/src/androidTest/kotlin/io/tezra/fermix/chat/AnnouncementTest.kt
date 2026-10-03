package io.tezra.fermix.chat

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** TalkBack's words for the card (design section 13.8). */
private const val ANNOUNCEMENT = "Fermix is thinking"

/** What TalkBack reads of the answer the rig completes: its plain words, no markdown. */
private const val ANSWER_WORDS = "The worker restarts at 02:14."

/** The card's phrases change every 8 s past the opening 5 s: three of them, the card held on screen. */
private val PHRASE_TIMES = listOf(6_000L, 14_000L, 22_000L)

/**
 * The chat's announcements on a device (design section 13.8): as the card appears, TalkBack has one polite live
 * region to say, "Fermix is thinking"; as the phrase changes it has nothing new to say, since the region is the
 * same node with the same words and the phrase is hidden from it. An answer that arrives whole is said once, in
 * its plain words, from one polite live region.
 */
class AnnouncementTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    private val liveRegions = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    @Test
    fun fermix_is_thinking_is_announced_once_and_never_as_the_phrase_changes() {
        rule.onAllNodes(liveRegions).assertCountEquals(0)
        val seen =
            PHRASE_TIMES.map { elapsed ->
                rule.activity.rig.thinking(elapsed)
                // The card ticks each second: the next tick reads the moved clock.
                rule.waitUntil("the card ticked", STEP_MILLIS) { phraseShown(elapsed) }
                rule.onAllNodes(liveRegions).assertCountEquals(1)
                rule.onAllNodesWithContentDescription(ANNOUNCEMENT).assertCountEquals(1)
                val region = rule.onNodeWithContentDescription(ANNOUNCEMENT).fetchSemanticsNode()
                assertEquals(LiveRegionMode.Polite, region.config.getOrNull(SemanticsProperties.LiveRegion))
                region.id to region.config.getOrNull(SemanticsProperties.ContentDescription)
            }
        assertEquals("the same node, with the same words, for each phrase", 1, seen.distinct().size)
    }

    @Test
    fun the_final_answer_is_announced_once_in_its_plain_words_as_it_arrives() {
        rule.onAllNodes(liveRegions).assertCountEquals(0)
        rule.activity.rig.answered("The **worker** restarts at `02:14`.")
        rule.waitUntil("the answer arrived", STEP_MILLIS) {
            rule.onAllNodesWithContentDescription(ANSWER_WORDS).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodes(liveRegions).assertCountEquals(1)
        val region = rule.onNodeWithContentDescription(ANSWER_WORDS).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, region.config.getOrNull(SemanticsProperties.LiveRegion))
        // The same answer again, as a recomposition brings it, says nothing new: the same node, the same words.
        rule.activity.rig.answered("The **worker** restarts at `02:14`.")
        rule.waitForIdle()
        rule.onAllNodes(liveRegions).assertCountEquals(1)
        assertEquals(region.id, rule.onNodeWithContentDescription(ANSWER_WORDS).fetchSemanticsNode().id)
    }

    /** Whether the card's line is the phrase [elapsed] in, hidden from TalkBack. */
    private fun phraseShown(elapsed: Long): Boolean {
        val phrase = rule.activity.cardPhrase(elapsed) ?: return false
        val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
        return rule.onAllNodes(hasText(phrase) and hidden).fetchSemanticsNodes().size == 1
    }
}
