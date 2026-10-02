package io.tezra.fermix.onboarding

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.plus
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The stub camera's frame, which the tests find by this tag. */
internal const val STUB_PREVIEW = "stub-preview"

/** The app's Chats list, which the host never shows: no Fermix is paired when a test begins. */
private data object NoChats : NavKey

/**
 * Onboarding as the app shows it, in Navigation 3's NavDisplay over the theme's canvas, with no daemon and
 * no camera: the rig's fake pairing control, stub camera, prompt and clip stand in for them. The rig and
 * the ViewModel live in the activity's ViewModel store, as the app's ViewModel does, so a rotation or a
 * fold recreates the activity and keeps both.
 */
class OnboardingTestActivity : ComponentActivity() {
    internal val rig: TestRig by viewModels {
        viewModelFactory { initializer { TestRig(File(cacheDir, "rig-${UUID.randomUUID()}")) } }
    }

    internal val onboarding: OnboardingViewModel by viewModels {
        viewModelFactory { initializer { OnboardingViewModel(rig.parts) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        rig.creations.incrementAndGet()
        setContent { OnboardingHost(rig, onboarding) }
    }
}

@Composable
private fun OnboardingHost(
    rig: TestRig,
    model: OnboardingViewModel,
) {
    val onboarding by model.stack.collectAsState()
    val owner =
        remember(rig) {
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = rig.prompts
            }
        }
    FermixTheme {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            Box(modifier = Modifier.fillMaxSize().background(LocalFermixColors.current.canvas)) {
                NavDisplay(
                    backStack = appBackStack(paired = false, chats = NoChats, onboarding = onboarding),
                    onBack = model::back,
                    entryProvider = entryProvider { onboardingEntries(this, model, rig.camera, rig.clip) },
                )
            }
        }
    }
}

/**
 * What the instrumented tests set and watch, made once per activity and kept across its recreation: the
 * ceremonies as [FakeStarter]'s controls, the hardware gate's answer, the network facts, the camera's
 * permission and its prompt, the primary clip, and the reader of the stub camera on the scan. The instance
 * records are written under [directory], in the test APK's cache, which goes with the APK, on [io].
 */
internal class TestRig(
    directory: File,
    io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val starter = FakeStarter()
    val clip = FakeClip(held = null)

    /** How many times the activity was made: a rotation or a fold that recreated it adds one. */
    val creations = AtomicInteger()
    val network = MutableStateFlow(NetworkFacts.NONE)

    @Volatile var gate: GateResult = GateResult.Ok

    /** Whether the system lets the app use the camera; the prompt's answer sets it, as the system's would. */
    @Volatile var cameraAllowed = false

    val prompts = PromptRegistry { granted -> cameraAllowed = granted }

    private var reader: ((String) -> Unit)? = null

    val parts =
        OnboardingParts(
            gate = { gate },
            pairings = starter,
            identity = PhoneIdentity(PHONE, "Google Pixel 9 Pro", "0.1.0"),
            instances = instanceStore(directory, viewModelScope + io),
            network = network,
            // The fake starter runs nothing in a ceremony's scope.
            pairingDispatcher = Dispatchers.Main.immediate,
            pairingWait = MutableStateFlow(null),
        )

    /** The scan's camera: allowed as [cameraAllowed] says, and a dark frame with a torch, off, for its preview. */
    val camera =
        ScanCamera(
            allowed = { cameraAllowed },
            preview = { onRead, onTorch -> StubPreview(onRead, onTorch) { reader = it } },
        )

    /** [text] as if the camera read it off a code; on the main thread, with the scan's camera showing. */
    fun read(text: String) {
        val read = checkNotNull(reader) { "the scan is not showing its camera" }
        read(text)
    }
}

/**
 * The camera's stand-in: a dark frame, tagged [STUB_PREVIEW], that reports a torch, off, once shown, as a
 * bound camera does, and hands its reader to [onReader] while it shows.
 */
@Composable
private fun StubPreview(
    onRead: (String) -> Unit,
    onTorch: (CameraTorch?) -> Unit,
    onReader: (((String) -> Unit)?) -> Unit,
) {
    val reads by rememberUpdatedState(onRead)
    val torches by rememberUpdatedState(onTorch)
    DisposableEffect(Unit) {
        onReader { text -> reads(text) }
        torches(stubTorch(lit = false) { torches(it) })
        onDispose {
            onReader(null)
            torches(null)
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black).testTag(STUB_PREVIEW))
}

/** A torch that turns as it is asked to, and reports itself again to [onTorch]. */
private fun stubTorch(
    lit: Boolean,
    onTorch: (CameraTorch) -> Unit,
): CameraTorch = CameraTorch(lit) { on -> onTorch(stubTorch(on, onTorch)) }
