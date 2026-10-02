package io.tezra.fermix.onboarding

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.test.platform.app.InstrumentationRegistry

// The screens as TalkBack meets them, read the same way on Robolectric and on a device.

/** The key under which Compose's accessibility gives its own tests the id of the node read after a node. */
private const val READ_BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"

private val ANY_NODE = SemanticsMatcher("any node") { true }

/** The view [activity]'s screens are drawn in, which plays their haptics and serves TalkBack: the ComposeView's own. */
internal fun composeViewOf(activity: Activity): View {
    val content = activity.findViewById<ViewGroup>(android.R.id.content)
    return (content.getChildAt(0) as ViewGroup).getChildAt(0)
}

/** What TalkBack says of a node: its content description, then its text. */
internal fun labelOf(config: SemanticsConfiguration): String {
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    val described = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    return listOfNotNull(described, text).joinToString(" ")
}

/**
 * The labels TalkBack reads in [view], in its order: Compose's accessibility links each node to the one read
 * after it (AccessibilityNodeInfo's traversal-before) and, for tests, writes that node's id into the node's
 * extras ([READ_BEFORE]); this follows the links from the one node nothing precedes. Compose works the order
 * out only while a screen reader is on, or while a test forces its accessibility on.
 */
internal fun SemanticsNodeInteractionsProvider.talkBackOrder(view: View): List<String> {
    val ids = onAllNodes(ANY_NODE, useUnmergedTree = true).fetchSemanticsNodes().map { it.id }
    var next = emptyMap<Int, Int>()
    // On the main thread, where the system asks for nodes too.
    InstrumentationRegistry.getInstrumentation().runOnMainSync { next = readBefore(view, ids) }
    val first = next.keys.single { it !in next.values }
    val order = generateSequence(first) { next[it] }.take(ids.size).toList()
    val labels = onAllNodes(ANY_NODE).fetchSemanticsNodes().associate { it.id to labelOf(it.config) }
    return order.mapNotNull { labels[it] }.filter { it.isNotBlank() }
}

/** Each of [ids] with the id of the node read after it, as [view]'s accessibility says. */
private fun readBefore(
    view: View,
    ids: List<Int>,
): Map<Int, Int> {
    val provider = checkNotNull(view.accessibilityNodeProvider) { "Compose serves the accessibility" }
    return ids
        .mapNotNull { id ->
            val extras = provider.createAccessibilityNodeInfo(id)?.extras ?: return@mapNotNull null
            if (extras.containsKey(READ_BEFORE)) id to extras.getInt(READ_BEFORE) else null
        }.toMap()
}

/** The label of every node that acts on a click, its text or its content description; one without fails. */
internal fun SemanticsNodeInteractionsProvider.actionLabels(): List<String> =
    onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node ->
        val label = labelOf(node.config)
        check(label.isNotBlank()) { "an action without a label: ${node.config}" }
        label
    }
