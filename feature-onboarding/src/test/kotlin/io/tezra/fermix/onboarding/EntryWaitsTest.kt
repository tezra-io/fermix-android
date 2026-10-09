package io.tezra.fermix.onboarding

import android.view.HapticFeedbackConstants
import androidx.activity.BackEventCompat
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.design.BellSwing
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.ReticleMotion
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The scan's refusal, under its hint. */
private const val NOT_FERMIX = "That's not a Fermix pairing code."

/** The scan's hint while it searches. */
private const val HINT = "Point at the code on your computer"

/** The haptic a found code plays: `CONFIRM`. */
private const val CONFIRM = HapticFeedbackConstants.CONFIRM

/**
 * The waits onboarding holds between a moment and the screen after it (the M51 update's 7.4), and what may come in
 * them: the scan's 250 ms between a Fermix code and the ceremony, held by the ViewModel, so that back in it starts no
 * ceremony and zeroes the link's secret, a back swipe begun in it holds it until the swipe ends, a rotation in it keeps
 * the code, and the refusal a code before it drew goes as it locks on; and Notifications' check after a grant, the
 * grant answered as it comes, back in the wait leaving sooner with the grant given, and "Not now" not heard. The
 * ViewModel's coroutines run on [main]'s scheduler, the screens' on the rule's clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class EntryWaitsTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val main = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val prompts = PromptRegistry(onAnswer = {})
    private val owner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = prompts
        }

    /** Every answer the records were given about notifications, in order. */
    private val notified = mutableListOf<Boolean>()

    @Before
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @After
    fun mainBack() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    /** A ViewModel on Pair, past the gate, its ceremonies [starter]'s fakes, kept in [viewModels]. */
    private fun pairingModel(starter: FakeStarter): OnboardingViewModel {
        val store = instanceStore(folder.root, CoroutineScope(main))
        val parts =
            OnboardingParts(
                gate = { GateResult.Ok },
                pairings = starter,
                identity = PhoneIdentity(PHONE, "Google Pixel 9 Pro", "0.1.0"),
                instances = store,
                network = MutableStateFlow(NetworkFacts.NONE),
                pairingDispatcher = main,
                pairingWait = MutableStateFlow(null),
                handover = FakeHandover(store.instances),
                notifications = { _, granted -> notified += granted },
            )
        val model =
            ViewModelProvider
                .create(viewModels, viewModelFactory { initializer { OnboardingViewModel(parts) } })
                .get(OnboardingViewModel::class)
        model.getStarted()
        return model
    }

    /** A ViewModel on Scan, its ceremonies [starter]'s fakes. */
    private fun scanningModel(starter: FakeStarter): OnboardingViewModel {
        val model = pairingModel(starter)
        model.scan()
        return model
    }

    private fun fermixLink(): LinkOutcome.Link = readLink(linkText()) as LinkOutcome.Link

    /** [ms] on the ViewModel's clock, and what falls due at its end. */
    private fun waitOnModel(ms: Long) {
        main.scheduler.advanceTimeBy(ms)
        main.scheduler.runCurrent()
    }

    @Test
    fun `a Fermix code goes to the ceremony 250 ms after it is found, its refusal gone at once`() {
        val starter = FakeStarter()
        val model = scanningModel(starter)
        model.ceremony.onLink(readLink("https://example.com/not-a-pairing-link"))
        assertTrue(model.ui.value.scanRefusal != null)
        model.found(fermixLink())
        assertTrue(model.ui.value.scanFound)
        assertNull(model.ui.value.scanRefusal)
        waitOnModel(ReticleMotion.LEAVE_AFTER_MILLIS - 1L)
        assertEquals(OnboardingKey.Scan, model.stack.value.last())
        assertTrue(starter.started.isEmpty())
        waitOnModel(1L)
        assertEquals(OnboardingKey.Connecting, model.stack.value.last())
        assertEquals(1, starter.started.size)
    }

    @Test
    fun `back in the 250 ms leaves for Pair, starts no ceremony, and zeroes the link's secret`() {
        val starter = FakeStarter()
        val model = scanningModel(starter)
        val link = fermixLink()
        model.found(link)
        waitOnModel(100L)
        model.back()
        waitOnModel(1_000L)
        assertEquals(listOf(OnboardingKey.Pair), model.stack.value)
        assertTrue(starter.started.isEmpty())
        assertTrue(link.link.secret.all { it == 0.toByte() })
    }

    @Test
    fun `the ViewModel cleared in the 250 ms zeroes the link's secret`() {
        val starter = FakeStarter()
        val model = scanningModel(starter)
        val link = fermixLink()
        model.found(link)
        waitOnModel(100L)
        viewModels.clear()
        waitOnModel(1_000L)
        assertTrue(starter.started.isEmpty())
        assertTrue(link.link.secret.all { it == 0.toByte() })
    }

    @Test
    fun `the scan holds one code at a time, and searches anew each time it comes back to the top`() {
        val model = scanningModel(FakeStarter())
        assertEquals(0, model.ui.value.scanVisit)
        model.found(fermixLink())
        assertThrows(IllegalStateException::class.java) { model.found(fermixLink()) }
        waitOnModel(ReticleMotion.LEAVE_AFTER_MILLIS.toLong())
        assertEquals(OnboardingKey.Connecting, model.stack.value.last())
        // Let go as the scan leaves the top, its next visit begun, so that a back swipe into it shows it searching.
        assertFalse(model.ui.value.scanFound)
        assertEquals(1, model.ui.value.scanVisit)
        model.back()
        assertEquals(OnboardingKey.Scan, model.stack.value.last())
        assertFalse(model.ui.value.scanFound)
        assertEquals(1, model.ui.value.scanVisit)
    }

    /** A camera this app may use, whose preview reads [text] off a code on each of its frames, the first [reads]. */
    private fun cameraReading(
        text: String,
        reads: Int = 1,
    ): ScanCamera =
        ScanCamera(allowed = { true }, preview = { onRead, _ ->
            LaunchedEffect(Unit) { repeat(reads) { withFrameMillis { onRead(text) } } }
        })

    /** [model]'s screens in NavDisplay as the app draws them, over Welcome, the scan's camera [camera]. */
    private fun showInNavDisplay(
        model: OnboardingViewModel,
        camera: ScanCamera,
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                FermixTheme(darkTheme = false) {
                    OnboardingTransitions { changes ->
                        val stack by model.stack.collectAsState()
                        NavDisplay(
                            backStack = listOf<NavKey>(OnboardingKey.Welcome) + stack,
                            onBack = model::back,
                            entryProvider =
                                entryProvider { onboardingEntries(this, model, camera, FakeClip(null), changes) },
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    /** One frame of the screens' clock, and as long on the ViewModel's. */
    private fun frameOnBoth() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        waitOnModel(16L)
    }

    /** A back swipe from the left edge, started, as a finger starts it. */
    private fun startSwipe() {
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(
                BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT),
            )
        }
    }

    /** The swipe moved on to [progress], a frame on both clocks. */
    private fun swipeTo(progress: Float) {
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackProgressed(
                BackEventCompat(400f * progress, 400f, progress, BackEventCompat.EDGE_LEFT),
            )
        }
        frameOnBoth()
    }

    /** A swipe begun a frame after [model] found [link], held on past the scan's 250 ms, half way across. */
    private fun swipeOverTheWait(
        model: OnboardingViewModel,
        link: LinkOutcome.Link,
    ) {
        model.found(link)
        frameOnBoth()
        startSwipe()
        for (step in 1..SWIPE_FRAMES) swipeTo(progress = 0.5f * step / SWIPE_FRAMES)
        assertEquals(listOf(OnboardingKey.Pair, OnboardingKey.Scan), model.stack.value)
    }

    @Test
    fun `a back swipe begun in the 250 ms holds the code until it ends, and let go, leaves for Pair and zeroes it`() {
        val starter = FakeStarter()
        val model = scanningModel(starter)
        showInNavDisplay(model, NO_CAMERA)
        val link = fermixLink()
        swipeOverTheWait(model, link)
        assertTrue(starter.started.isEmpty())
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        repeat(SWIPE_FRAMES) { frameOnBoth() }
        assertEquals(listOf(OnboardingKey.Pair), model.stack.value)
        assertTrue(starter.started.isEmpty())
        assertTrue(link.link.secret.all { it == 0.toByte() })
    }

    @Test
    fun `a back swipe begun in the 250 ms and cancelled hands the code on as it ends, to Connecting once`() {
        val starter = FakeStarter()
        val model = scanningModel(starter)
        showInNavDisplay(model, NO_CAMERA)
        swipeOverTheWait(model, fermixLink())
        assertTrue(starter.started.isEmpty())
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        repeat(SWIPE_FRAMES) { frameOnBoth() }
        assertEquals(listOf(OnboardingKey.Pair, OnboardingKey.Scan, OnboardingKey.Connecting), model.stack.value)
        assertEquals(1, starter.started.size)
    }

    @Test
    fun `back in NavDisplay in the 250 ms leaves the scan for Pair, and no ceremony starts`() {
        val starter = FakeStarter()
        val model = scanningModel(starter)
        val camera = cameraReading(linkText())
        showInNavDisplay(model, camera)
        rule.mainClock.advanceTimeBy(64L)
        assertTrue(model.ui.value.scanFound)
        waitOnModel(80L)
        model.back()
        // The scan leaves by fade through, drawn and composed for its 300 ms as it goes.
        repeat(40) {
            rule.mainClock.advanceTimeByFrame()
            waitOnModel(16L)
        }
        assertEquals(listOf(OnboardingKey.Pair), model.stack.value)
        assertTrue(starter.started.isEmpty())
    }

    @Test
    fun `a rotation in the 250 ms keeps the code, CONFIRM once, and Connecting 250 ms after it was found`() {
        val starter = FakeStarter()
        val model = scanningModel(starter)
        val camera = cameraReading(linkText(), reads = 30)
        val entries = entryProvider<NavKey> { onboardingEntries(this, model, camera, FakeClip(null), NO_CHANGES) }
        val rig = MotionRig(rule)
        rig.show {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                entries(OnboardingKey.Scan).Content()
            }
        }
        rig.frames(2)
        assertTrue(model.ui.value.scanFound)
        waitOnModel(100L)
        rig.restore()
        // The camera reads the code on after the rotation, unread.
        rig.advanceTo(rig.at + 200L)
        assertTrue(model.ui.value.scanFound)
        assertEquals(OnboardingKey.Scan, model.stack.value.last())
        waitOnModel(ReticleMotion.LEAVE_AFTER_MILLIS - 100L)
        assertEquals(OnboardingKey.Connecting, model.stack.value.last())
        assertEquals(1, starter.started.size)
        assertEquals(listOf(CONFIRM), rig.played().map { it.first })
    }

    @Test
    fun `a Fermix code after a refused one takes the refusal away as the reticle locks on`() {
        val model = scanningModel(FakeStarter())
        model.ceremony.onLink(readLink("https://example.com/not-a-pairing-link"))
        val camera = cameraReading(linkText())
        val entries = entryProvider<NavKey> { onboardingEntries(this, model, camera, FakeClip(null), NO_CHANGES) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                FermixTheme(darkTheme = false) { entries(OnboardingKey.Scan).Content() }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        assertTrue(model.ui.value.scanFound)
        assertNull(model.ui.value.scanRefusal)
        // The refusal cross-fades back to the hint over its 200 ms, the ViewModel still holding the code.
        rule.mainClock.advanceTimeBy(300L)
        assertEquals(0, rule.onAllNodesWithText(NOT_FERMIX).fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText(HINT).fetchSemanticsNodes().size)
        assertEquals(OnboardingKey.Scan, model.stack.value.last())
    }

    /** A ViewModel on Notifications after an approved pairing, its ceremonies [starter]'s fakes. */
    private fun notifyingModel(starter: FakeStarter): OnboardingViewModel {
        val model = pairingModel(starter)
        model.ceremony.onLink(readLink(linkText()))
        main.scheduler.advanceUntilIdle()
        starter.control.state.value = PairingState.Approved(facts(gateway = 1), idleSession(CoroutineScope(main)))
        main.scheduler.advanceUntilIdle()
        model.continueFromPaired()
        assertEquals(OnboardingKey.Notifications, model.stack.value.last())
        return model
    }

    /** [model]'s Notifications entry drawn on [rig] at the animator duration [scale], the system answering yes. */
    private fun showNotifications(
        model: OnboardingViewModel,
        rig: MotionRig,
        scale: Float = 1f,
    ) {
        val entries = entryProvider<NavKey> { onboardingEntries(this, model, NO_CAMERA, FakeClip(null), NO_CHANGES) }
        prompts.answer = true
        rig.show(scale) {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                entries(OnboardingKey.Notifications).Content()
            }
        }
    }

    /** "Allow notifications" tapped, the system's yes heard, and the answer's writes run. */
    private fun allow(rig: MotionRig) {
        rule.onNodeWithText("Allow notifications").performClick()
        rig.frameAfterWrite()
        main.scheduler.runCurrent()
    }

    @Test
    fun `a grant is the answer as it comes, and onboarding ends 600 ms after it, the check's 200 ms and then 400`() {
        val model = notifyingModel(FakeStarter())
        val rig = MotionRig(rule)
        showNotifications(model, rig)
        allow(rig)
        assertEquals(listOf(true), notified)
        assertEquals(OnboardingKey.Notifications, model.stack.value.last())
        waitOnModel(BellSwing.CHECK_MILLIS + BellSwing.END_AFTER_MILLIS - 1L)
        assertEquals(OnboardingKey.Notifications, model.stack.value.last())
        waitOnModel(1L)
        assertEquals(emptyList<OnboardingKey>(), model.stack.value)
        assertEquals(
            model.ui.value.paired
                ?.record
                ?.id,
            model.arrival.id.value,
        )
        assertEquals(listOf(true), notified)
    }

    @Test
    fun `back in the wait after a grant leaves at once, the grant given all the same`() {
        val model = notifyingModel(FakeStarter())
        val rig = MotionRig(rule)
        showNotifications(model, rig)
        allow(rig)
        waitOnModel(100L)
        model.back()
        assertEquals(emptyList<OnboardingKey>(), model.stack.value)
        waitOnModel(1_000L)
        assertEquals(listOf(true), notified)
        assertEquals(emptyList<OnboardingKey>(), model.stack.value)
        assertEquals(
            model.ui.value.paired
                ?.record
                ?.id,
            model.arrival.id.value,
        )
    }

    @Test
    fun `a rotation in the wait after a grant ends onboarding once, 600 ms after the grant all the same`() {
        val model = notifyingModel(FakeStarter())
        val rig = MotionRig(rule)
        showNotifications(model, rig)
        allow(rig)
        waitOnModel(300L)
        rig.restore()
        rig.frames(5)
        main.scheduler.runCurrent()
        assertEquals(listOf(true), notified)
        waitOnModel(BellSwing.CHECK_MILLIS + BellSwing.END_AFTER_MILLIS - 301L)
        assertEquals(OnboardingKey.Notifications, model.stack.value.last())
        waitOnModel(1L)
        assertEquals(emptyList<OnboardingKey>(), model.stack.value)
        assertEquals(listOf(true), notified)
    }

    @Test
    fun `under Remove animations a grant ends onboarding 400 ms after it`() {
        val model = notifyingModel(FakeStarter())
        val rig = MotionRig(rule)
        showNotifications(model, rig, scale = 0f)
        allow(rig)
        assertEquals(listOf(true), notified)
        waitOnModel(BellSwing.END_AFTER_MILLIS - 1L)
        assertEquals(OnboardingKey.Notifications, model.stack.value.last())
        waitOnModel(1L)
        assertEquals(emptyList<OnboardingKey>(), model.stack.value)
    }

    @Test
    fun `once the system has granted notifications, Not now is not heard, and onboarding ends with the grant`() {
        val model = notifyingModel(FakeStarter())
        val rig = MotionRig(rule)
        showNotifications(model, rig)
        allow(rig)
        rule.onNodeWithText("Not now").performClick()
        rig.frameAfterWrite()
        main.scheduler.advanceUntilIdle()
        assertEquals(listOf(true), notified)
        assertEquals(emptyList<OnboardingKey>(), model.stack.value)
    }
}

/** A back swipe's frames: about as many as a finger takes, and past the scan's 250 ms. */
private const val SWIPE_FRAMES = 24

/** The entries' screen changes, which these tests, drawing an entry on its own, never run. */
private val NO_CHANGES = ScreenChanges(reduced = { false }, axisShift = { 0 })

/** A camera the screens may use, which shows nothing and reads nothing. */
private val NO_CAMERA = ScanCamera(allowed = { true }, preview = { _, _ -> })
