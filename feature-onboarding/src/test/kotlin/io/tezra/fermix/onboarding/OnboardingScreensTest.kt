package io.tezra.fermix.onboarding

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.session.PairingState
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The preview's code as TalkBack reads it, digit by digit (design section 13.8). */
private const val SPOKEN_SAS = "6 6 9, 9 7 9"

/** What Robolectric's view says it played before it played any haptic. */
private const val NO_HAPTIC = -1

/** The nickname field, as the Name screen labels it. */
private const val NAME_FIELD = "Name this Fermix"

/** A three-button navigation bar's height. */
private val NAVIGATION_BAR = 48.dp

private val ANY_NODE = SemanticsMatcher("any node") { true }

/** The key under which Compose's accessibility gives its own tests the id of the node read after a node. */
private const val READ_BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"

/**
 * The screens as TalkBack and a rotation meet them, on Robolectric: every action is labelled in the
 * semantics, and a screen drawn again after a rotation, its ViewModel kept, shows the same state with the
 * countdown running on. JUnit 4, in Roborazzi's activity, as the design module's layout test, on the
 * compact window of @FermixPreviews, where every screen's content fits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class OnboardingScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val main = StandardTestDispatcher()

    /** What the test shows; the rule takes its content once, so later screens replace this. */
    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)

    @Before
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @After
    fun mainBack() = Dispatchers.resetMain()

    /** The view the screens are drawn in, which plays their haptics and serves TalkBack: the ComposeView's own. */
    private fun composeView(): View {
        val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
        return (content.getChildAt(0) as ViewGroup).getChildAt(0)
    }

    /** A screen reader on, as TalkBack is, so that Compose works out the order it reads the screen in. */
    private fun talkBackOn() {
        val accessibility = shadowOf(rule.activity.getSystemService(AccessibilityManager::class.java))
        accessibility.setEnabled(true)
        accessibility.setTouchExplorationEnabled(true)
        accessibility.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    /**
     * The labels TalkBack reads, in its order: Compose's accessibility links each node to the one read after
     * it (AccessibilityNodeInfo's traversal-before) and, for tests, writes that node's id into the node's
     * extras ([READ_BEFORE]); this follows the links from the one node nothing precedes.
     */
    private fun talkBackOrder(): List<String> {
        val provider = checkNotNull(composeView().accessibilityNodeProvider) { "Compose serves the accessibility" }
        val ids = rule.onAllNodes(ANY_NODE, useUnmergedTree = true).fetchSemanticsNodes().map { it.id }
        val next =
            ids
                .mapNotNull { id ->
                    val extras = provider.createAccessibilityNodeInfo(id)?.extras ?: return@mapNotNull null
                    if (extras.containsKey(READ_BEFORE)) id to extras.getInt(READ_BEFORE) else null
                }.toMap()
        val first = next.keys.single { it !in next.values }
        val order = generateSequence(first) { next[it] }.take(ids.size).toList()
        val labels = rule.onAllNodes(ANY_NODE).fetchSemanticsNodes().associate { it.id to labelOf(it.config) }
        return order.mapNotNull { labels[it] }.filter { it.isNotBlank() }
    }

    private fun labelOf(config: SemanticsConfiguration): String {
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        val described = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        return listOfNotNull(described, text).joinToString(" ")
    }

    private fun show(content: @Composable () -> Unit) {
        val first = screen == null
        screen = content
        if (first) rule.setContent { FermixTheme { screen?.invoke() } }
        rule.waitForIdle()
    }

    /** Every node that acts on a click carries a label, its text or its content description: [expected]. */
    private fun assertActions(expected: List<String>) {
        val labels =
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node ->
                val label = labelOf(node.config)
                assertTrue("an action without a label: ${node.config}", label.isNotBlank())
                label
            }
        assertEquals(expected.sorted(), labels.sorted())
    }

    @Test
    fun `Pair's back, copy, rename, scan and paste are labelled`() {
        show { PairScreen(PHONE, PairActions({}, {}, {}, {}, {})) }
        assertActions(listOf("Back", "Copy", "Rename", "Scan the code", "Paste a pairing link"))
    }

    @Test
    fun `the pencil opens the rename sheet, which offers only a name pair_request can carry`() {
        var renamed: String? = null
        show { PairScreen(PHONE, PairActions({}, {}, { renamed = it }, {}, {})) }
        rule.onNodeWithContentDescription("Rename").performClick()
        val field = rule.onNode(hasSetTextAction())
        field.assertTextContains(PHONE)
        val rename = rule.onNode(hasClickAction() and hasText("Rename") and !hasSetTextAction())
        field.performTextReplacement("bell\u0007")
        rename.assertIsNotEnabled()
        field.performTextReplacement("  Suj's phone  ")
        rename.assertIsEnabled().performClick()
        assertEquals("Suj's phone", renamed)
    }

    @Test
    fun `Scan's back, torch and paste are labelled, and the torch waits for a camera`() {
        show { ScanScreen(refused = true, torchOn = false, actions = ScanActions({}, {}, {})) }
        assertActions(listOf("Back", "Torch", "Paste a pairing link"))
        rule.onNodeWithText("That's not a Fermix pairing code.").assertIsDisplayed()
        show { ScanScreen(refused = false, torchOn = null, actions = ScanActions({}, {}, {})) }
        assertActions(listOf("Back", "Paste a pairing link"))
    }

    @Test
    fun `the torch is a toggle that says whether it is on`() {
        var asked: Boolean? = null
        var on by mutableStateOf(false)
        show { ScanScreen(refused = false, torchOn = on, actions = ScanActions({}, { asked = it }, {})) }
        rule.onNodeWithContentDescription("Torch").assertIsOff().performClick()
        assertEquals(true, asked)
        on = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Torch").assertIsOn().performClick()
        assertEquals(false, asked)
    }

    @Test
    fun `TalkBack reaches Scan's paste first, then the bar and the hint`() {
        talkBackOn()
        show { ScanScreen(refused = true, torchOn = false, actions = ScanActions({}, {}, {})) }
        assertEquals(
            listOf("Paste a pairing link", "Back", "Torch", "That's not a Fermix pairing code."),
            talkBackOrder(),
        )
    }

    @Test
    fun `a link the scan refuses plays REJECT, and is read out`() {
        var refused by mutableStateOf(false)
        show { ScanScreen(refused = refused, torchOn = null, actions = ScanActions({}, {}, {})) }
        assertEquals(NO_HAPTIC, shadowOf(composeView()).lastHapticFeedbackPerformed())
        refused = true
        rule.waitForIdle()
        assertEquals(HapticFeedbackConstants.REJECT, shadowOf(composeView()).lastHapticFeedbackPerformed())
        val hint = rule.onNodeWithText("That's not a Fermix pairing code.").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, hint.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `a refusal's failure screen plays REJECT`() {
        show { FailureScreen(FailureCase.WRONG_MACHINE, HOST, onAction = {}) }
        assertEquals(HapticFeedbackConstants.REJECT, shadowOf(composeView()).lastHapticFeedbackPerformed())
    }

    @Test
    fun `a failure nothing refused plays no haptic`() {
        show { FailureScreen(FailureCase.CANT_REACH, HOST, onAction = {}) }
        assertEquals(NO_HAPTIC, shadowOf(composeView()).lastHapticFeedbackPerformed())
    }

    @Test
    fun `Name labels its field, its three suggestions and Continue`() {
        val paired = PairedFacts(record(gateway = 1, tint = "Ocean"), listOf(record(gateway = 5)), true, false)
        show { NameScreen(paired = paired, onContinue = {}) }
        assertActions(listOf("Dev", "Production", "Test", "Continue", NAME_FIELD))
        rule.onNode(hasSetTextAction()).assertContentDescriptionEquals("Name this Fermix")
    }

    @Test
    fun `on a window drawn edge to edge, Welcome's actions stand above the navigation bar`() {
        show { WelcomeScreen({}, {}) }
        val bar = with(rule.density) { NAVIGATION_BAR.roundToPx() }
        val insets =
            WindowInsetsCompat
                .Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bar))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
        ViewCompat.dispatchApplyWindowInsets(composeView(), insets)
        rule.waitForIdle()
        val safeBottom = rule.onRoot().getUnclippedBoundsInRoot().bottom - NAVIGATION_BAR
        for (action in listOf("Get started", "Don't have Fermix yet?")) {
            val bottom = rule.onNodeWithText(action).getUnclippedBoundsInRoot().bottom
            assertTrue("$action ends at $bottom, under the bar from $safeBottom", bottom <= safeBottom)
        }
    }

    @Test
    fun `Verify reads its code digit by digit, and offers Cancel alone`() {
        show { VerifyScreen(VerifyUi(PREVIEW_SAS, 102, PHONE), onCancel = {}) }
        assertActions(listOf("Cancel"))
        rule.onNodeWithContentDescription(SPOKEN_SAS).assertIsDisplayed()
        rule.onNodeWithText("1:42").assertIsDisplayed()
    }

    @Test
    fun `every failure screen's actions are labelled as the table says`() {
        for (case in FailureCase.entries) {
            show { FailureScreen(case, HOST, onAction = {}) }
            val expected = (listOf(case.primary) + case.secondaries).map { rule.activity.getString(it.label) }
            assertActions(expected)
        }
    }

    @Test
    fun `Welcome, Paired and Notifications label their actions`() {
        show { WelcomeScreen({}, {}) }
        assertActions(listOf("Get started", "Don't have Fermix yet?"))
        show { PairedScreen(HOST, onContinue = {}) }
        assertActions(listOf("Continue"))
        show { NotificationsScreen({}, {}) }
        assertActions(listOf("Allow notifications", "Not now"))
    }

    @Test
    fun `a rotation draws Verify again from the kept ViewModel, the countdown running on`() {
        val time = TestTimeSource()
        val starter = FakeStarter()
        val model = verifyingModel(starter)
        starter.control.state.value = PairingState.Verify(PREVIEW_SAS, time.markNow() + 120.seconds, PHONE)
        main.scheduler.advanceUntilIdle()
        assertEquals(OnboardingKey.Verify, model.stack.value.last())
        time += 18.seconds
        val entries = entryProvider<NavKey> { onboardingEntries(this, model) }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FermixTheme { entries(OnboardingKey.Verify).Content() } }
        rule.onNodeWithText("1:42").assertIsDisplayed()
        time += 10.seconds
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("1:32").assertIsDisplayed()
        rule.onNodeWithContentDescription(SPOKEN_SAS).assertIsDisplayed()
        assertEquals(OnboardingKey.Verify, model.stack.value.last())
        assertEquals(0, starter.control.cancels)
    }

    /** A ViewModel whose ceremony began over [starter]'s fake, on Connecting. */
    private fun verifyingModel(starter: FakeStarter): OnboardingViewModel {
        val model =
            OnboardingViewModel(
                OnboardingParts(
                    gate = { GateResult.Ok },
                    pairings = starter,
                    identity = PhoneIdentity(PHONE, "Google Pixel 9 Pro", "0.1.0"),
                    instances = instanceStore(folder.root, CoroutineScope(main)),
                    network = MutableStateFlow(NetworkFacts.NONE),
                    pairingDispatcher = main,
                    pairingWait = MutableStateFlow(null),
                ),
            )
        model.getStarted()
        model.ceremony.onLink(linkText())
        main.scheduler.advanceUntilIdle()
        return model
    }
}
