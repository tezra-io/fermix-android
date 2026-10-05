package io.tezra.fermix.chat

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The card's minute, and how much of it is gone when the window changes: 42 s left. */
private const val TTL_S = 60
private const val GONE_MS = 18_000L
private const val LEFT_S = 42

/** How many nodes of the window's accessibility tree a search reads at most: the chat holds far fewer. */
private const val MAX_NODES = 2_000

/**
 * The approval card on a device (design sections 13.5, 13.8 and 13.11): a rotation and a fold keep its countdown
 * where the monotonic clock puts it and its buttons answering; TalkBack finds the card as one group, with no
 * other stop inside it, whose custom actions answer it, and hears the countdown from one polite live region at
 * 30 s and at 10 s only.
 */
class ApprovalDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = rule.activity.getString(id, *args)

    /** The card shown with its minute, then [GONE_MS] of it gone on the monotonic clock. */
    private fun cardWaiting() {
        rule.activity.rig.approval(TTL_S)
        val countdown = string(R.string.chat_approval_expires, TTL_S)
        rule.waitUntil("the card shows", STEP_MILLIS) {
            rule.onAllNodesWithText(countdown).fetchSemanticsNodes().isNotEmpty()
        }
        rule.activity.rig.clock.mono += GONE_MS
    }

    /**
     * The card drawn again counts from the clock, its buttons there, once the window made again has laid them out
     * (awaitDisplayed), and Approve answers it.
     */
    private fun assertCountingAndAnswering() {
        val expires = string(R.string.chat_approval_expires, LEFT_S)
        rule.awaitDisplayed(expires, string(R.string.chat_deny), string(R.string.chat_approve))
        rule.onNodeWithText(expires).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.chat_deny)).assertIsDisplayed().assertIsEnabled()
        rule
            .onNodeWithText(string(R.string.chat_approve))
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        val session = rule.activity.rig.session
        rule.waitUntil("the answer went", STEP_MILLIS) { session.answers.value.isNotEmpty() }
        assertEquals(listOf(APPROVAL.approvalId to true), session.answers.value)
    }

    @Test
    fun a_rotation_keeps_the_countdown_and_the_buttons() {
        cardWaiting()
        rule.rotated { assertCountingAndAnswering() }
    }

    @Test
    @FoldingPhone
    fun a_fold_keeps_the_countdown_and_the_buttons() {
        cardWaiting()
        rule.folded { assertCountingAndAnswering() }
    }

    @Test
    fun talkback_finds_one_group_whose_custom_actions_answer_the_card() {
        cardWaiting()
        val approve = string(R.string.chat_approve)
        val deny = string(R.string.chat_deny)
        val group = accessibilityNode { node -> node.actionList.any { it.label?.toString() == approve } }
        val labels = group.actionList.mapNotNull { it.label?.toString() }
        assertEquals(listOf(approve, deny), labels.filter { it == approve || it == deny })
        assertEquals("TalkBack stops inside the card", emptyList<String>(), stopsUnder(group))
        val denyAction = group.actionList.first { it.label?.toString() == deny }
        assertEquals("the action ran", true, group.performAction(denyAction.id))
        val session = rule.activity.rig.session
        rule.waitUntil("the answer went", STEP_MILLIS) { session.answers.value.isNotEmpty() }
        assertEquals(listOf(APPROVAL.approvalId to false), session.answers.value)
    }

    @Test
    fun talkback_hears_the_countdown_at_30_s_and_at_10_s_only() {
        val rig = rule.activity.rig
        rig.approval(ttlS = 35)
        rule.waitUntil("the card shows", STEP_MILLIS) { countdownShows(35) }
        assertEquals(emptyList<String>(), announced())
        rig.approval(ttlS = 30)
        rule.waitUntil("the card counts 30 s", STEP_MILLIS) { countdownShows(30) }
        assertEquals(listOf(string(R.string.chat_approval_expires, 30)), announced())
        rig.approval(ttlS = 20)
        rule.waitUntil("the card counts 20 s", STEP_MILLIS) { countdownShows(20) }
        assertEquals("nothing new to say at 20 s", listOf(string(R.string.chat_approval_expires, 30)), announced())
        rig.approval(ttlS = 10)
        rule.waitUntil("the card counts 10 s", STEP_MILLIS) { countdownShows(10) }
        assertEquals(listOf(string(R.string.chat_approval_expires, 10)), announced())
    }

    private fun countdownShows(seconds: Int): Boolean =
        rule.onAllNodesWithText(string(R.string.chat_approval_expires, seconds)).fetchSemanticsNodes().isNotEmpty()

    /**
     * The words of each polite live region TalkBack finds in the window, but the countdown's before it said
     * anything: the chat has no other till a turn.
     */
    private fun announced(): List<String> {
        rule.waitForIdle()
        return accessibilityNodes { it.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE }
            .mapNotNull { it.contentDescription?.toString() }
    }

    /**
     * The nodes under [group] TalkBack can stop on, by their words or class: none, as the card is one stop whose
     * buttons are a touch's alone (design section 13.8). [MAX_NODES] are read at most.
     */
    private fun stopsUnder(group: AccessibilityNodeInfo): List<String> {
        val queue = ArrayDeque((0 until group.childCount).mapNotNull { group.getChild(it) })
        val stops = mutableListOf<String>()
        var read = 0
        while (queue.isNotEmpty() && read < MAX_NODES) {
            val node = queue.removeFirst()
            read++
            val focusable = node.isClickable || node.isFocusable || node.isScreenReaderFocusable
            if (node.isImportantForAccessibility && focusable) stops += (node.text ?: node.className).toString()
            (0 until node.childCount).mapNotNullTo(queue) { node.getChild(it) }
        }
        check(queue.isEmpty()) { "the card holds more than $MAX_NODES nodes" }
        return stops
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
     * Every node of the active window's accessibility tree that [matches], read breadth first, [MAX_NODES] at
     * most. Each is refreshed past the accessibility cache, which learns of a change only from its event, sent
     * up to 100 ms later: what is read is the state TalkBack's next read gets, whatever the events' timing.
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
