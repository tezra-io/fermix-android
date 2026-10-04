package io.tezra.fermix.chat

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.instance.tintColor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Collections

/** The web address in the agent's answer, which the Custom Tab opens. */
private const val WEB = "https://hexdocs.pm/elixir/Task.html"

/**
 * The answer's links, each its own paragraph, by the words a tap lands on and as written; the web address's last.
 * The front office's address is a reference's, looked up as the renderer draws it, and its words, which open
 * nothing, are drawn with no link on them.
 */
private val LINKS =
    listOf(
        "the desk" to "[the desk](tel:+15551234)",
        "the pairing" to "[the pairing](fermix://pair?v=2&host=suj-mbp)",
        "the copy" to "[the copy](content://io.tezra.fermix.chat.test.chat.files/shared/0123456789abcdef/x.pdf)",
        "the records" to "[the records](file:///data/user/0/io.tezra.fermix.chat.test/no_backup/instances.json)",
        "the intent" to "[the intent](intent://pair#Intent;scheme=fermix;package=io.tezra.fermix;end)",
        "the front office" to "[the front office][office]",
        "the guide" to "[the guide]($WEB)",
    )

/** The agent's answer: [LINKS], then the reference's definition, which draws nothing. */
private val ANSWER = LINKS.joinToString("\n\n") { it.second } + "\n\nThe end.\n\n[office]: tel:+15551234"

/** The first words of [BESIDE], on the line above its web address. */
private const val ABOVE = "Ring the front desk on the ground floor"

/** An answer whose web address has a line of plain words above it and one below, held there by hard breaks. */
private const val BESIDE = "$ABOVE\\\n$WEB\\\nor walk over to the front desk itself"

/**
 * Every activity the process starts, kept and never started: Instrumentation hands each start to this monitor
 * first, and its result stands in for the start.
 */
private class Starts : Instrumentation.ActivityMonitor() {
    val intents: MutableList<Intent> = Collections.synchronizedList(mutableListOf())

    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
        intents += Intent(intent)
        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
    }
}

/**
 * A link in an agent's answer on a device, tapped where its words are drawn (design sections 8.1, 8.3 and 13.5):
 * a `tel:`, a `fermix://pair?…`, the chat's own provider's `content:`, a `file:` and an `intent:` link start no
 * activity, nor does a reference's `tel:`, as the words of a message are the wire's, while a web address starts
 * the Custom Tab's intent at that address, in the instance's tint, as a link preview's does. The words
 * on the lines beside a link are not the link: a tap on them starts nothing, and a long-press opens the message's
 * menu (design section 13.7).
 */
class LinkDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val starts = Starts()

    @Before
    fun watch() = instrumentation.addMonitor(starts)

    @After
    fun unwatch() = instrumentation.removeMonitor(starts)

    /** The intents of the activities the process started, as their strings say. */
    private fun started(): List<String> = starts.intents.map { it.toString() }

    /** How [node]'s text is laid out. */
    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val layout = mutableListOf<TextLayoutResult>()
        node
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layout)
        return layout.single()
    }

    /** A tap on the middle of [words], where the answer draws them. */
    private fun tap(words: String) {
        val node = rule.onNode(hasText(words, substring = true), useUnmergedTree = true)
        val laid = layoutOf(node)
        val drawn = laid.layoutInput.text.text
        val middle = drawn.indexOf(words) + words.length / 2
        node.performTouchInput { click(laid.getBoundingBox(middle).center) }
        rule.waitForIdle()
    }

    /**
     * A tap, or a [long] press, on a word of [BESIDE] on the line [lines] from its web address's, over the address,
     * a quarter of a line from the address's own: where the words are, and inside the 48 dp a link's touch target
     * would grow to.
     */
    private fun pressBeside(
        lines: Int,
        long: Boolean,
    ) {
        val node = rule.onNode(hasText(ABOVE, substring = true), useUnmergedTree = true)
        val laid = layoutOf(node)
        val drawn = laid.layoutInput.text.text
        val start = drawn.indexOf(WEB)
        val left = laid.getBoundingBox(start).left
        val right = laid.getBoundingBox(start + WEB.length - 1).right
        val line = laid.getLineForOffset(start) + lines
        val word =
            (laid.getLineStart(line) until laid.getLineEnd(line)).firstOrNull { at ->
                drawn[at].isLetter() && laid.getBoundingBox(at).center.x in left..right
            }
        val over = checkNotNull(word) { "no word on line $line of '$drawn' over the address" }
        val quarter = (laid.getLineBottom(line) - laid.getLineTop(line)) / 4
        val y = if (lines < 0) laid.getLineBottom(line) - quarter else laid.getLineTop(line) + quarter
        val point = Offset(laid.getBoundingBox(over).center.x, y)
        node.performTouchInput { if (long) longClick(point) else click(point) }
        rule.waitForIdle()
    }

    @Test
    fun only_a_web_address_in_an_agents_answer_starts_an_activity_the_custom_tab() {
        rule.activity.rig.answered(ANSWER)
        rule.waitUntil("the answer arrived", STEP_MILLIS) {
            rule.onAllNodes(hasText("the guide"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        LINKS.dropLast(1).forEach { (words, written) ->
            tap(words)
            assertEquals("a tap on $written started", emptyList<String>(), started())
        }
        tap("the guide")
        val tab = starts.intents.single()
        assertEquals(Intent.ACTION_VIEW, tab.action)
        assertEquals(WEB, tab.dataString)
        assertTrue("a Custom Tab's intent: ${tab.extras?.keySet()}", tab.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        val colors = CustomTabsIntent.getColorSchemeParams(tab, CustomTabsIntent.COLOR_SCHEME_LIGHT)
        assertEquals(tintColor(withModel().tint).toArgb(), colors.toolbarColor)
    }

    @Test
    fun the_words_beside_a_link_start_nothing_and_their_long_press_opens_the_menu() {
        rule.activity.rig.answered(BESIDE)
        rule.waitUntil("the answer arrived", STEP_MILLIS) {
            rule.onAllNodes(hasText(ABOVE, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        pressBeside(-1, long = false)
        assertEquals("a tap on the words above the link started", emptyList<String>(), started())
        pressBeside(1, long = false)
        assertEquals("a tap on the words below the link started", emptyList<String>(), started())
        pressBeside(-1, long = true)
        assertEquals("a long-press on the words above the link started", emptyList<String>(), started())
        val select = rule.activity.getString(R.string.chat_select_text)
        rule.waitUntil("the message's menu opened", STEP_MILLIS) {
            rule.onAllNodes(hasText(select)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
