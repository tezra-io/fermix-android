package io.tezra.fermix.instance

import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.transport.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.time.ZoneOffset

/**
 * The Instance screen's page (the owner, 2026-10-10): the header's one status line, read once; Rename and Test
 * connection as buttons side by side, stacked at twice the type size with nothing cut; "Advanced" closed until
 * opened, its state said to TalkBack and kept through a recreation, and holding the connection, this phone,
 * storage, diagnostics and the name's reset; the way out a button; and no underlined words anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class InstanceScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val calls = mutableListOf<String>()

    private val actions =
        InstanceActions(
            onBack = { calls += "back" },
            onRename = { calls += "rename:$it" },
            onTest = { calls += "test" },
            onNotifications = { calls += "notifications:$it" },
            onPreviews = { calls += "previews:$it" },
            onClearCache = { calls += "clear" },
            onUnpair = { calls += "unpair" },
            onTint = { calls += "tint:$it" },
            onEyes = { calls += "eyes:$it" },
            onChoosePhoto = { calls += "choose" },
            onRemovePhoto = { calls += "remove" },
        )

    private fun string(
        @StringRes id: Int,
        vararg args: Any,
    ): String = rule.activity.getString(id, *args)

    private fun ui(nickname: String? = "Studio"): InstanceUi =
        InstanceUi(
            record = sample(nickname = nickname),
            others = emptyList(),
            link = Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true),
            diagnostics = emptyList(),
            reachable = setOf(LAN),
            previews = true,
            cacheBytes = 4_096,
            test = TestState.Idle,
            releaseBuild = true,
        )

    private fun show(
        ui: InstanceUi = ui(),
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            FermixTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    InstanceScreen(ui, actions, zone = ZoneOffset.UTC)
                }
            }
        }
    }

    private fun open() {
        rule.onNodeWithText(string(R.string.instance_advanced)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private val card = FermixColors.Light.agentBubble.toArgb()

    /** The colour 4 dp before the start of [words] on the page, which a card's row lies 16 dp into its card. */
    private fun behind(words: String): Int {
        val bounds =
            rule
                .onNodeWithText(words)
                .performScrollTo()
                .fetchSemanticsNode()
                .boundsInRoot
        val page =
            rule
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
        val before = bounds.left - 4 * rule.activity.resources.displayMetrics.density
        return page.getPixel(before.toInt(), bounds.center.y.toInt())
    }

    private fun stateOfAdvanced(): String? =
        rule
            .onNodeWithText(string(R.string.instance_advanced))
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun `the status line reads once, Connected and the path, and the name is no button`() {
        show()
        val path = string(R.string.instance_path_lan, 9)
        val status = string(R.string.instance_status_line, string(R.string.instance_state_connected), path)
        rule.onAllNodesWithText(status).assertCountEquals(1)
        rule.onAllNodesWithText(path, substring = true).assertCountEquals(1)
        open()
        rule.onAllNodesWithText(status).assertCountEquals(1)
        val name = rule.onNodeWithText("Studio").fetchSemanticsNode()
        assertFalse("the name is no button", SemanticsActions.OnClick in name.config)
    }

    @Test
    fun `Advanced lays each section's rows in a card of the agent bubble's grey, Path first while the link is up`() {
        show()
        open()
        rule.onNodeWithText(string(R.string.instance_path)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(R.string.instance_path_lan, 9)).assertIsDisplayed()
        val rows =
            listOf(R.string.instance_path, R.string.instance_protocol, R.string.instance_key, R.string.instance_cache)
        for (row in rows) assertEquals(string(row), card, behind(string(row)))
        assertNotEquals("a section's header lies on the page", card, behind(string(R.string.instance_section_phone)))
    }

    @Test
    fun `with the link not up Advanced has no Path`() {
        show(ui().copy(link = Link.Connecting))
        open()
        rule.onNodeWithText(string(R.string.instance_protocol)).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(string(R.string.instance_path)).assertCountEquals(0)
    }

    @Test
    fun `over the route the path is From anywhere, and its row after the hosts names its relays and never its id`() {
        val record = sample(nickname = "Studio").copy(route = ROUTE)
        val remote = Link.Up(Candidate.Scope.REMOTE, latencyMs = 41, caughtUp = true)
        show(ui().copy(record = record, link = remote, reachable = setOf(ROUTE)))
        val path = string(R.string.instance_path_remote, 41)
        rule.onAllNodesWithText(path, substring = true).assertCountEquals(1)
        open()
        rule.onNodeWithText(path).performScrollTo().assertIsDisplayed()
        val route = rule.onNodeWithText(string(R.string.instance_route)).performScrollTo().fetchSemanticsNode()
        rule.onNodeWithText("us1.relay.fermix.ai, eu1.relay.fermix.ai").assertIsDisplayed()
        rule.onAllNodesWithText(ROUTE.nodeId.take(8), substring = true).assertCountEquals(0)
        val host = rule.onNodeWithText(TAILNET.host, substring = true).fetchSemanticsNode()
        assertTrue("the route's row is under the hosts", route.boundsInRoot.top >= host.boundsInRoot.bottom)
    }

    @Test
    fun `the test's outcome under the buttons is said to TalkBack as it comes, politely`() {
        val reached = TestState.Done(TestOutcome.Reached(TAILNET, millis = 911))
        show(ui().copy(test = reached))
        val outcome = rule.onNodeWithText(string(R.string.instance_path_tailnet, 911)).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, outcome.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `Advanced is closed until opened, says so to TalkBack, and stays open through a recreation`() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FermixTheme { InstanceScreen(ui(), actions, zone = ZoneOffset.UTC) } }
        rule.onAllNodesWithText(string(R.string.instance_protocol)).assertCountEquals(0)
        rule.onAllNodesWithText(string(R.string.instance_section_diagnostics)).assertCountEquals(0)
        assertEquals(string(R.string.instance_advanced_collapsed), stateOfAdvanced())
        val advanced = rule.onNodeWithText(string(R.string.instance_advanced)).fetchSemanticsNode()
        assertTrue("Advanced is a button", SemanticsActions.OnClick in advanced.config)
        open()
        assertEquals(string(R.string.instance_advanced_expanded), stateOfAdvanced())
        rule.onNodeWithText(string(R.string.instance_protocol)).performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(string(R.string.instance_protocol)).performScrollTo().assertIsDisplayed()
        assertEquals(string(R.string.instance_advanced_expanded), stateOfAdvanced())
    }

    @Test
    fun `the buttons do what they say`() {
        show()
        rule.onNodeWithText(string(R.string.instance_test_connection)).performClick()
        assertEquals(listOf("test"), calls)
        // The rename dialog's field never lets Robolectric's looper settle, so Compose's test never finds it idle: the
        // frames are stepped by hand from the tap on, and the dialog is found as the platform shows it.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText(string(R.string.instance_rename)).performClick()
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        assertTrue("the rename dialog shows", ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test
    fun `inside Advanced, Clear media cache and Reset to gateway name are buttons, and so is the way out`() {
        show()
        open()
        rule.onNodeWithText(string(R.string.instance_clear_cache)).performScrollTo().performClick()
        rule.onNodeWithText(string(R.string.instance_reset_name)).performScrollTo().performClick()
        assertEquals(listOf("clear", "rename:null"), calls)
        rule.onNodeWithText(string(R.string.instance_unpair_row, HOST)).performScrollTo().performClick()
        rule.onNodeWithText(string(R.string.instance_unpair_title, HOST)).assertIsDisplayed()
    }

    @Test
    fun `a Fermix with no nickname has no reset`() {
        show(ui(nickname = null))
        open()
        rule.onAllNodesWithText(string(R.string.instance_reset_name)).assertCountEquals(0)
    }

    @Test
    fun `no words on the page are underlined, Advanced open`() {
        show()
        open()
        val laidOut = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        val texts = rule.onAllNodes(laidOut, useUnmergedTree = true)
        val underlined =
            texts.fetchSemanticsNodes().flatMap { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                layouts.filter { layout ->
                    val input = layout.layoutInput
                    val spans = input.text.spanStyles.mapNotNull { it.item.textDecoration }
                    (spans + listOfNotNull(input.style.textDecoration)).any { it.contains(TextDecoration.Underline) }
                }
            }
        assertEquals(emptyList<String>(), underlined.map { it.layoutInput.text.text })
    }

    @Test
    fun `at twice the type size Rename and Test connection stack, each whole`() {
        show(fontScale = 2f)
        val rename = rule.onNodeWithText(string(R.string.instance_rename), useUnmergedTree = true).fetchSemanticsNode()
        val test =
            rule.onNodeWithText(string(R.string.instance_test_connection), useUnmergedTree = true).fetchSemanticsNode()
        val stacked = test.boundsInRoot.top >= rename.boundsInRoot.bottom
        assertTrue("stacked: ${rename.boundsInRoot} over ${test.boundsInRoot}", stacked)
        for (node in listOf(rename, test)) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            // Centred words lay their paragraph out across the room they were offered, wider than the words: the
            // line's own width is what must fit, on one line, its height whole.
            val line = layout.getLineRight(0) - layout.getLineLeft(0)
            val words = layout.layoutInput.text
            assertEquals("$words on one line", 1, layout.lineCount)
            assertTrue("$words: a line $line wide in ${layout.size}", line <= layout.size.width + 1f)
            assertFalse("$words is cut at its foot", layout.didOverflowHeight)
        }
    }

    @Test
    fun `at the usual type size Rename and Test connection stand side by side`() {
        show()
        val rename = rule.onNodeWithText(string(R.string.instance_rename)).fetchSemanticsNode().boundsInRoot
        val test =
            rule
                .onNodeWithText(string(R.string.instance_test_connection))
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(rename.top, test.top, 1f)
        assertTrue("$rename then $test", test.left > rename.right)
        rule.onAllNodes(hasText(string(R.string.instance_rename))).assertCountEquals(1)
    }
}
