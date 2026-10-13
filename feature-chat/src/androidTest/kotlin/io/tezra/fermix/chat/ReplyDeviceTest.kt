package io.tezra.fermix.chat

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.protocol.ClientEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The moves a swipe is made of, so the drag's slop is crossed early and its travel followed. */
private const val SWIPE_STEPS = 12

/** How many nodes of the window's accessibility tree a search reads at most: the chat holds far fewer. */
private const val MAX_NODES = 2_000

/** The question the owner asks over the quote. */
private const val QUESTION = "why?"

/**
 * A reply on a device (the owner, 2026-10-11): a swipe toward the end edge past the threshold on the agent's answer
 * holds its quote over the composer, "Replying to" the Fermix, the field taking the focus; Send carries one `msg`, the
 * quote, a blank line and the question, and the owner's bubble draws the quote above the question; and TalkBack finds
 * Reply as the message's own custom action, which starts the same reply.
 */
class ReplyDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    private val newest = "row $THREAD_ROWS"

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = rule.activity.getString(id, *args)

    /** "Replying to" the record's Fermix, as the strip says it for the agent's message. */
    private fun replyingTo(): String {
        val title =
            checkNotNull(rule.activity.model.state.value) { "the chat shows" }
                .header.record.title
        return string(R.string.chat_replying_to, title)
    }

    /** Waits, bounded, for the newest row, the agent's answer, to be drawn. */
    private fun threadShown() {
        rule.waitUntil("the thread shows", STEP_MILLIS) {
            rule.onAllNodesWithText(newest).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The strip shows the reply and the field has the focus, as a reply starts. */
    private fun assertReplying() {
        rule.awaitDisplayed(replyingTo())
        rule.onNodeWithContentDescription(string(R.string.chat_reply_drop)).assertIsDisplayed()
        rule.waitUntil("the field has the focus", STEP_MILLIS) {
            rule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1
        }
    }

    @Test
    fun a_swipe_on_the_agents_answer_quotes_it_and_send_carries_the_quote_ahead_of_the_question() {
        threadShown()
        val travel = with(rule.density) { REPLY_SWIPE.toPx() } * 2
        rule.onNodeWithText(newest).performTouchInput {
            down(Offset(width * 0.25f, centerY))
            repeat(SWIPE_STEPS) { moveBy(Offset(travel / SWIPE_STEPS, 0f)) }
            up()
        }
        assertReplying()
        rule.onNode(hasSetTextAction()).performTextInput(QUESTION)
        rule.onNodeWithContentDescription(string(R.string.chat_send)).performClick()
        val session = rule.activity.rig.session
        rule.waitUntil("the reply went", STEP_MILLIS) { session.sent.value.isNotEmpty() }
        val sent = session.sent.value.single() as ClientEvent.Msg
        assertTrue(sent.text, sent.text.startsWith("> ") && sent.text.endsWith(QUESTION))
        assertEquals("> $newest\n\n$QUESTION", sent.text)
        rule.waitUntil("the strip went", STEP_MILLIS) {
            rule.onAllNodesWithText(replyingTo()).fetchSemanticsNodes().isEmpty()
        }
        rule.awaitDisplayed(string(R.string.chat_quoted, newest))
        rule.onNodeWithText(QUESTION).assertIsDisplayed()
    }

    @Test
    fun talkback_finds_reply_as_the_answers_custom_action_and_it_starts_the_reply() {
        threadShown()
        val reply = string(R.string.chat_reply)
        val answer =
            accessibilityNode { node ->
                node.actionList.any { it.label?.toString() == reply } && newest in wordsUnder(node)
            }
        val action = answer.actionList.single { it.label?.toString() == reply }
        assertTrue("the action ran", answer.performAction(action.id))
        assertReplying()
        assertEquals(emptyList<ClientEvent>(), rule.activity.rig.session.sent.value)
    }

    /**
     * The words of [group] and the nodes under it, which TalkBack reads as the group's label: a bubble's words lie on
     * its text's node, under the node that holds its actions. [MAX_NODES] are read at most.
     */
    private fun wordsUnder(group: AccessibilityNodeInfo): List<String> {
        val queue = ArrayDeque(listOf(group))
        val words = mutableListOf<String>()
        var read = 0
        while (queue.isNotEmpty() && read < MAX_NODES) {
            val node = queue.removeFirst()
            read++
            node.text?.let { words += it.toString() }
            (0 until node.childCount).mapNotNullTo(queue) { node.getChild(it) }
        }
        check(queue.isEmpty()) { "the group holds more than $MAX_NODES nodes" }
        return words
    }

    /** The one node of the window's accessibility tree that [matches]: what TalkBack reads, not Compose's tree. */
    private fun accessibilityNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        rule.waitUntil("TalkBack finds the node", STEP_MILLIS) {
            found = accessibilityNodes(matches).singleOrNull()
            found != null
        }
        return checkNotNull(found)
    }

    /**
     * Every node of the active window's accessibility tree that [matches], read breadth first, [MAX_NODES] at most,
     * each refreshed past the accessibility cache, as ApprovalDeviceTest reads it.
     */
    private fun accessibilityNodes(matches: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque(listOf(root))
        val seen = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty() && seen.size < MAX_NODES) {
            val node = queue.removeFirst()
            if (!node.refresh()) continue
            seen += node
            (0 until node.childCount).mapNotNullTo(queue) { node.getChild(it) }
        }
        check(queue.isEmpty()) { "the window holds more than $MAX_NODES nodes" }
        return seen.filter(matches)
    }
}
