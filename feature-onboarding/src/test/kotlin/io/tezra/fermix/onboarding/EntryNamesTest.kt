package io.tezra.fermix.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.PairBuild
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** One frame of the test clock. */
private const val FRAME_MILLIS = 16L

/**
 * The names onboarding's entries go by in NavDisplay, each screen's saved state's key (EntryNames): a screen pushed is
 * named anew, and keeps its name while it stays on the stack and once popped, until it is pushed again; every entry but
 * the root's goes by it; so a pairing whose screens were popped behind the app lock, where nothing draws them and
 * NavDisplay forgets nothing, leaves nothing played for the next pairing, which builds Pair's diagram from the start
 * and tells TalkBack its 30 s. The lock is the app's, as FermixApp has it: the screens not drawn while it holds, their
 * state kept under one key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class EntryNamesTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val main = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val time = TestTimeSource()

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
                notifications = { _, _ -> },
            )
        val model =
            ViewModelProvider
                .create(viewModels, viewModelFactory { initializer { OnboardingViewModel(parts) } })
                .get(OnboardingViewModel::class)
        model.getStarted()
        return model
    }

    @Test
    fun `a screen pushed is named anew, and keeps its name while it stays and once popped, until pushed again`() {
        val model = pairingModel(FakeStarter())
        val names = model.entryNames
        val pair = names.of(OnboardingKey.Pair)
        model.scan()
        assertEquals(pair, names.of(OnboardingKey.Pair))
        val scan = names.of(OnboardingKey.Scan)
        assertNotEquals(pair, scan)
        model.back()
        assertEquals(scan, names.of(OnboardingKey.Scan))
        model.scan()
        assertNotEquals(scan, names.of(OnboardingKey.Scan))
        assertEquals(pair, names.of(OnboardingKey.Pair))
    }

    @Test
    fun `every onboarding entry but the root's goes by its screen's name`() {
        val model = pairingModel(FakeStarter())
        model.scan()
        val entries =
            entryProvider<NavKey> {
                onboardingEntries(this, model, NO_CAMERA, FakeClip(null), NO_CHANGES)
            }
        assertEquals(model.entryNames.of(OnboardingKey.Pair), entries(OnboardingKey.Pair).contentKey)
        assertEquals(model.entryNames.of(OnboardingKey.Scan), entries(OnboardingKey.Scan).contentKey)
        assertNotEquals(model.entryNames.of(OnboardingKey.Welcome), entries(OnboardingKey.Welcome).contentKey)
    }

    private var locked by mutableStateOf(false)
    private var seconds by mutableIntStateOf(PAIRING_COUNTDOWN_SECONDS)
    private var build: () -> Float = { -1f }
    private var ring: RingState? = null

    /**
     * [model]'s stack in NavDisplay as the app draws it, behind the app's lock; Pair and Verify stand-ins with their
     * moments, the build and the ring, each entry going by its screen's name.
     */
    private fun showBehindTheLock(model: OnboardingViewModel) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            FermixTheme(darkTheme = false) {
                val saved = rememberSaveableStateHolder()
                if (!locked) {
                    saved.SaveableStateProvider("screens") {
                        OnboardingTransitions { changes ->
                            val stack by model.stack.collectAsState()
                            NavDisplay(
                                backStack = listOf<NavKey>(OnboardingKey.Welcome) + stack,
                                onBack = model::back,
                                entryProvider = entryProvider { standIns(model, changes) },
                            )
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun EntryProviderScope<NavKey>.standIns(
        model: OnboardingViewModel,
        changes: ScreenChanges,
    ) {
        val named = model.entryNames::of
        val metadata = changes::metadataFor
        entry<OnboardingKey.Welcome>(metadata = metadata) { Box(Modifier.fillMaxSize()) }
        entry<OnboardingKey.Pair>(named, metadata) {
            build = rememberPairBuild()
            Box(Modifier.fillMaxSize())
        }
        entry<OnboardingKey.Scan>(named, metadata) { Box(Modifier.fillMaxSize()) }
        entry<OnboardingKey.Connecting>(named, metadata) { Box(Modifier.fillMaxSize()) }
        entry<OnboardingKey.Verify>(named, metadata) {
            ring = rememberRing(seconds)
            Box(Modifier.fillMaxSize())
        }
        entry<OnboardingKey.Paired>(named, metadata) { Box(Modifier.fillMaxSize()) }
    }

    private fun frames(count: Int) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(count * FRAME_MILLIS)
    }

    /** The ceremony from Pair to Verify with [gateway]'s daemon, and Verify drawn with 31 s left. */
    private fun toVerify(
        model: OnboardingViewModel,
        starter: FakeStarter,
        gateway: Int,
    ) {
        model.ceremony.onLink(readLink(linkText(gateway)))
        main.scheduler.advanceUntilIdle()
        seconds = PAIRING_COUNTDOWN_SECONDS
        starter.control.state.value = PairingState.Verify(PREVIEW_SAS, time.markNow() + 60.seconds, PHONE)
        main.scheduler.advanceUntilIdle()
        assertEquals(OnboardingKey.Verify, model.stack.value.last())
        frames(40)
        seconds = 31
        frames(2)
    }

    /** Whether the ring tells TalkBack the time left as the clock turns to 30 s. */
    private fun toldAtThirty(): Boolean {
        seconds = 30
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        return checkNotNull(ring).announcing
    }

    @Test
    fun `a pairing whose screens were popped behind the lock leaves nothing played for the next pairing`() {
        val starter = FakeStarter()
        val model = pairingModel(starter)
        showBehindTheLock(model)
        frames(80)
        assertEquals(PairBuild.CLOCK_MILLIS.toFloat(), build())
        toVerify(model, starter, gateway = 1)
        assertTrue("the first pairing told its 30 s", toldAtThirty())
        frames(30)
        // The lock comes, the owner approves on the computer meanwhile, and the screens come back on Paired.
        locked = true
        frames(2)
        starter.control.state.value = PairingState.Approved(facts(gateway = 1), idleSession(CoroutineScope(main)))
        main.scheduler.advanceUntilIdle()
        assertEquals(listOf(OnboardingKey.Paired), model.stack.value)
        locked = false
        frames(40)
        model.back()
        frames(40)
        // The next pairing, from the root.
        model.getStarted()
        // A frame for the stack to reach NavDisplay, and one for Pair's first.
        frames(2)
        assertTrue("the next pairing's diagram stood at ${build()} ms", build() < PairBuild.CLOCK_MILLIS / 4f)
        frames(80)
        // Another Fermix, as one paired already is asked about first.
        toVerify(model, starter, gateway = 3)
        assertTrue("the next pairing told its 30 s", toldAtThirty())
    }
}

/** The entries' screen changes, which these tests, building entries on their own, never run. */
private val NO_CHANGES = ScreenChanges(reduced = { false }, axisShift = { 0 })

/** A camera the screens may use, which shows nothing and reads nothing. */
private val NO_CAMERA = ScanCamera(allowed = { true }, preview = { _, _ -> })
