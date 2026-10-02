package io.tezra.fermix.onboarding

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

/** The scan's refusal, under its hint or the paste sheet's field. */
private const val NOT_FERMIX = "That's not a Fermix pairing code."

/** A three-button navigation bar's height. */
private val NAVIGATION_BAR = 48.dp

/** A camera the screens may use, which shows nothing and reads nothing. */
private val NO_CAMERA = ScanCamera(allowed = { true }, preview = { _, _ -> })

private val NO_SCAN_ACTIONS = ScanActions({}, {}, {}, {}, {})

/** The scan with its camera and the torch off, nothing refused. */
private val SCANNING = ScanUi(refused = false, torchOn = false)

/** The scan before the system's prompt, and after a no to it. */
private val RATIONALE = SCANNING.copy(access = CameraAccess.RATIONALE)
private val CAMERA_OFF = SCANNING.copy(access = CameraAccess.DENIED)

private val NO_PASTE_ACTIONS = PasteActions({}, {}, {}, {})

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

    /** The view the screens are drawn in, which plays their haptics and serves TalkBack. */
    private fun composeView(): View = composeViewOf(rule.activity)

    /** A screen reader on, as TalkBack is, so that Compose works out the order it reads the screen in. */
    private fun talkBackOn() {
        val accessibility = shadowOf(rule.activity.getSystemService(AccessibilityManager::class.java))
        accessibility.setEnabled(true)
        accessibility.setTouchExplorationEnabled(true)
        accessibility.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    /** The labels TalkBack reads, in its order. */
    private fun talkBackOrder(): List<String> = rule.talkBackOrder(composeView())

    private fun show(content: @Composable () -> Unit) {
        val first = screen == null
        screen = content
        if (first) rule.setContent { FermixTheme { screen?.invoke() } }
        rule.waitForIdle()
    }

    /** Every node that acts on a click carries a label, its text or its content description: [expected]. */
    private fun assertActions(expected: List<String>) = assertEquals(expected.sorted(), rule.actionLabels().sorted())

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
        show { ScanScreen(state = ScanUi(refused = true, torchOn = false), actions = NO_SCAN_ACTIONS) }
        assertActions(listOf("Back", "Torch", "Paste a pairing link"))
        rule.onNodeWithText(NOT_FERMIX).assertIsDisplayed()
        show { ScanScreen(state = ScanUi(refused = false, torchOn = null), actions = NO_SCAN_ACTIONS) }
        assertActions(listOf("Back", "Paste a pairing link"))
    }

    @Test
    fun `before the camera is allowed, the rationale offers Continue to the prompt, and the paste`() {
        var asked = 0
        val actions = NO_SCAN_ACTIONS.copy(onAllowCamera = { asked++ })
        show { ScanScreen(state = RATIONALE, actions = actions) }
        assertActions(listOf("Back", "Continue", "Paste a pairing link"))
        rule.onNodeWithText("Allow the camera to scan the code").assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `once the owner said no, the camera is off, with Open settings and the paste`() {
        var settings = 0
        var pasted = 0
        val actions = NO_SCAN_ACTIONS.copy(onPaste = { pasted++ }, onOpenSettings = { settings++ })
        show { ScanScreen(state = CAMERA_OFF, actions = actions) }
        rule.onNodeWithText("Camera is off for Fermix").assertIsDisplayed()
        assertActions(listOf("Back", "Open settings", "Paste a pairing link"))
        rule.onNodeWithText("Open settings").performClick()
        rule.onNodeWithText("Paste a pairing link").performClick()
        assertEquals(1 to 1, settings to pasted)
    }

    @Test
    fun `the frame is the camera's once allowed, the rationale's before the prompt, and off after a no`() {
        assertEquals(CameraAccess.ALLOWED, cameraAccess(granted = true, refusedPrompt = false))
        assertEquals(CameraAccess.ALLOWED, cameraAccess(granted = true, refusedPrompt = true))
        assertEquals(CameraAccess.RATIONALE, cameraAccess(granted = false, refusedPrompt = false))
        assertEquals(CameraAccess.DENIED, cameraAccess(granted = false, refusedPrompt = true))
    }

    @Test
    fun `the torch is a toggle that says whether it is on`() {
        var asked: Boolean? = null
        var on by mutableStateOf(false)
        val actions = NO_SCAN_ACTIONS.copy(onTorchChange = { asked = it })
        show { ScanScreen(state = SCANNING.copy(torchOn = on), actions = actions) }
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
        show { ScanScreen(state = ScanUi(refused = true, torchOn = false), actions = NO_SCAN_ACTIONS) }
        assertEquals(
            listOf("Paste a pairing link", "Back", "Torch", NOT_FERMIX),
            talkBackOrder(),
        )
    }

    @Test
    fun `TalkBack reaches the paste first on the camera's rationale and on its denial too`() {
        talkBackOn()
        show { ScanScreen(state = RATIONALE, actions = NO_SCAN_ACTIONS) }
        assertEquals("Paste a pairing link", talkBackOrder().first())
        show { ScanScreen(state = CAMERA_OFF, actions = NO_SCAN_ACTIONS) }
        assertEquals(
            listOf("Paste a pairing link", "Back", "Camera is off for Fermix", "Open settings"),
            talkBackOrder(),
        )
    }

    @Test
    fun `the paste sheet labels its field, its paste button and Continue, and shows a refusal under the field`() {
        var refused by mutableStateOf(false)
        show { PasteLinkSheet(PasteField("https://example.com", refused), NO_PASTE_ACTIONS) }
        // The scrim's "Close sheet" and the "Drag handle" are Material's own, labelled by the sheet.
        assertActions(listOf("Close sheet", "Drag handle", "Paste a pairing link", "Paste", "Continue"))
        refused = true
        rule.waitForIdle()
        rule.onNodeWithText(NOT_FERMIX).assertIsDisplayed()
    }

    @Test
    fun `the paste sheet's Continue waits for a text`() {
        show { PasteLinkSheet(PasteField("", refused = false), NO_PASTE_ACTIONS) }
        rule.onNode(hasClickAction() and hasText("Continue")).assertIsNotEnabled()
    }

    @Test
    fun `a rotation keeps the paste sheet open with its half-typed link`() {
        val model = pairingModel(FakeStarter())
        model.paste.open()
        model.paste.edit("fermix://pair?v=2&candid")
        val entries = entryProvider<NavKey> { onboardingEntries(this, model, NO_CAMERA, FakeClip(null)) }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { FermixTheme { entries(OnboardingKey.Pair).Content() } }
        rule.onNode(hasSetTextAction()).assertTextContains("fermix://pair?v=2&candid")
        restoration.emulateSavedInstanceStateRestore()
        rule.onNode(hasSetTextAction()).assertTextContains("fermix://pair?v=2&candid")
    }

    @Test
    fun `a link the scan refuses plays REJECT, and is read out`() {
        var refused by mutableStateOf(false)
        show { ScanScreen(state = ScanUi(refused = refused, torchOn = null), actions = NO_SCAN_ACTIONS) }
        assertEquals(NO_HAPTIC, shadowOf(composeView()).lastHapticFeedbackPerformed())
        refused = true
        rule.waitForIdle()
        assertEquals(HapticFeedbackConstants.REJECT, shadowOf(composeView()).lastHapticFeedbackPerformed())
        val hint = rule.onNodeWithText(NOT_FERMIX).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, hint.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `the paste sheet's refusal plays REJECT, and is read out`() {
        var refused by mutableStateOf(false)
        show { PasteLinkSheet(PasteField("https://example.com", refused), NO_PASTE_ACTIONS) }
        val sheet = sheetView()
        assertEquals(NO_HAPTIC, shadowOf(sheet).lastHapticFeedbackPerformed())
        refused = true
        rule.waitForIdle()
        assertEquals(HapticFeedbackConstants.REJECT, shadowOf(sheet).lastHapticFeedbackPerformed())
        val refusal = rule.onNodeWithText(NOT_FERMIX, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, refusal.config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `a Fermix code the camera reads plays CONFIRM, and the ceremony takes it`() {
        val model = scanningModel()
        showScanEntry(model, cameraReading(linkText()))
        assertEquals(HapticFeedbackConstants.CONFIRM, shadowOf(composeView()).lastHapticFeedbackPerformed())
        assertEquals(OnboardingKey.Connecting, model.stack.value.last())
    }

    @Test
    fun `a code the ceremony does not take plays no CONFIRM`() {
        val model = scanningModel()
        showScanEntry(model, cameraReading(linkText().replace("v=2", "v=1")))
        assertEquals(NO_HAPTIC, shadowOf(composeView()).lastHapticFeedbackPerformed())
        assertEquals(OnboardingKey.Failure(FailureCase.OLDER_FERMIX), model.stack.value.last())
    }

    @Test
    fun `Open settings, once the owner said no to the camera, opens this app's page in the system's settings`() {
        val prompts = PromptRegistry(onAnswer = {})
        prompts.answer = false
        showScanEntry(scanningModel(), ScanCamera(allowed = { false }, preview = { _, _ -> }), prompts)
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Camera is off for Fermix").assertIsDisplayed()
        rule.onNodeWithText("Open settings").performClick()
        val opened = shadowOf(rule.activity).nextStartedActivity
        assertEquals(listOf(Manifest.permission.CAMERA), prompts.asked)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${rule.activity.packageName}", opened.dataString)
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
        val entries = entryProvider<NavKey> { onboardingEntries(this, model, NO_CAMERA, FakeClip(null)) }
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

    /** A ViewModel on Pair, past the gate, its ceremonies [starter]'s fakes. */
    private fun pairingModel(starter: FakeStarter): OnboardingViewModel {
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
        return model
    }

    /** A ViewModel on Scan, past the gate, its ceremonies fakes. */
    private fun scanningModel(): OnboardingViewModel {
        val model = pairingModel(FakeStarter())
        model.scan()
        return model
    }

    /** A camera this app may use, whose preview reads [text] off a code as it shows. */
    private fun cameraReading(text: String): ScanCamera =
        ScanCamera(allowed = { true }, preview = { onRead, _ -> LaunchedEffect(Unit) { onRead(text) } })

    /** [model]'s Scan entry as the app draws it, over [camera], its prompts answered by [prompts]. */
    private fun showScanEntry(
        model: OnboardingViewModel,
        camera: ScanCamera,
        prompts: PromptRegistry = PromptRegistry(onAnswer = {}),
    ) {
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = prompts
            }
        val entries = entryProvider<NavKey> { onboardingEntries(this, model, camera, FakeClip(null)) }
        show {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                entries(OnboardingKey.Scan).Content()
            }
        }
    }

    /** The view the paste sheet is drawn in, its own window's, which plays the sheet's haptics. */
    private fun sheetView(): View {
        val node = rule.onNodeWithContentDescription("Paste").fetchSemanticsNode()
        return (checkNotNull(node.root) { "the sheet is drawn" } as ViewRootForTest).view
    }

    /** A ViewModel whose ceremony began over [starter]'s fake, on Connecting. */
    private fun verifyingModel(starter: FakeStarter): OnboardingViewModel {
        val model = pairingModel(starter)
        model.ceremony.onLink(readLink(linkText()))
        main.scheduler.advanceUntilIdle()
        return model
    }
}
