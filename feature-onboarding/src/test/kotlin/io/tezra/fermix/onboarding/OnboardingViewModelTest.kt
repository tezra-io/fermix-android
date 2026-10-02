package io.tezra.fermix.onboarding

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.session.PairingState
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.session.Retry
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.time.TestTimeSource

private val PAIR = OnboardingKey.Pair
private val SCAN = OnboardingKey.Scan

/** A phone online with Tailscale off: section 5.2 reads every candidate failing as "Can't reach". */
private val ONLINE = NetworkFacts(1L, defaultHasVpn = false, defaultHasCgnatAddress = false, otherUidVpnPresent = false)

/**
 * Onboarding's ViewModel over a fake ceremony (design section 13.3): the gate, the link, each state's
 * screen, the retry, the commit before Paired, the name and the notifications step, and the pairing-wait
 * facts the app's notification shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @TempDir
    lateinit var directory: File

    private val main = StandardTestDispatcher()

    /** Each rig's records in a directory of their own: DataStore allows one per file in a process. */
    private var rigs = 0

    @BeforeEach
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @AfterEach
    fun mainBack() = Dispatchers.resetMain()

    private inner class Rig(
        private val scope: TestScope,
        gate: GateResult = GateResult.Ok,
    ) {
        val starter = FakeStarter()
        val network = MutableStateFlow(ONLINE)
        val wait = MutableStateFlow<PairingWait?>(null)
        val store = instanceStore(File(directory, "rig${rigs++}"), scope.backgroundScope)
        private val parts =
            OnboardingParts(
                gate = { gate },
                pairings = starter,
                identity = PhoneIdentity(PHONE, "Google Pixel 9 Pro", "0.1.0"),
                instances = store,
                network = network,
                pairingDispatcher = main,
                pairingWait = wait,
            )

        /** The activity's store of ViewModels, whose clear is the activity's end. */
        val viewModels = ViewModelStore()
        val model: OnboardingViewModel =
            ViewModelProvider
                .create(viewModels, viewModelFactory { initializer { OnboardingViewModel(parts) } })
                .get(OnboardingViewModel::class)

        val control: FakeControl get() = starter.control

        /** Get started, then the link, as the owner pastes it on Pair. */
        fun pasted(link: String = linkText()) {
            model.getStarted()
            model.ceremony.onLink(link)
        }

        suspend fun shows(vararg keys: OnboardingKey) = model.stack.first { it == keys.toList() }

        /** Verify, as the ceremony reaches it from the pasted link, the pairing-wait facts set. */
        fun verifying() {
            pasted()
            scope.runCurrent()
            control.state.value = PairingState.Verify(PREVIEW_SAS, TestTimeSource().markNow(), PHONE)
            scope.advanceUntilIdle()
            assertEquals(PairingWait(HOST, (control.state.value as PairingState.Verify).expiresAt), wait.value)
        }
    }

    @Test
    fun `get started runs the gate, then Pair, or the one screen a phone that fails it sees`() =
        runTest(main) {
            val passing = Rig(this)
            passing.model.getStarted()
            assertEquals(listOf(PAIR), passing.model.stack.value)
            val failing = Rig(this, gate = GateResult.NoHardwareCurve25519)
            failing.model.getStarted()
            val gate = OnboardingKey.Failure(FailureCase.NO_SECURE_HARDWARE)
            assertEquals(listOf(gate), failing.model.stack.value)
            failing.model.ceremony.act(FailureCase.NO_SECURE_HARDWARE, FailureAction.OK)
            assertEquals(emptyList<OnboardingKey>(), failing.model.stack.value)
        }

    @Test
    fun `a link begins the ceremony under the name chosen on Pair, and Connecting shows the host`() =
        runTest(main) {
            val rig = Rig(this)
            rig.model.getStarted()
            rig.model.rename("Suj's phone")
            rig.model.ceremony.onLink(linkText())
            assertEquals(listOf(PAIR, SCAN, OnboardingKey.Connecting), rig.model.stack.value)
            assertEquals(
                "Suj's phone",
                rig.starter.started
                    .single()
                    .second,
            )
            assertEquals(HOST, rig.model.ui.value.host)
            assertThrows<IllegalArgumentException> { rig.model.rename("bell\u0007") }
        }

    @Test
    fun `a link that is not Fermix's is the scan's refusal, and a newer one is its own screen`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pasted("https://example.com/not-a-pairing")
            assertEquals(listOf(PAIR, SCAN), rig.model.stack.value)
            assertNotNull(rig.model.ui.value.scanRefusal)
            rig.model.ceremony.onLink(linkText().replace("v=2", "v=3"))
            assertEquals(listOf(PAIR, SCAN, OnboardingKey.Failure(FailureCase.NEWER_FERMIX)), rig.model.stack.value)
            assertTrue(rig.starter.started.isEmpty())
        }

    @Test
    fun `Connecting advances, says Trying Tailscale after 4 s, and paces Securing before Verify`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pasted()
            runCurrent()
            rig.control.state.value = PairingState.Reaching(listOf(LAN, TAILNET), elapsedMs = 0)
            runCurrent()
            assertEquals(ConnectingPhase.REACHING, rig.model.ui.value.connecting)
            advanceTimeBy(TRYING_TAILSCALE_AFTER_MILLIS + 1)
            assertEquals(ConnectingPhase.TRYING_TAILSCALE, rig.model.ui.value.connecting)
            rig.control.state.value = PairingState.Checking
            runCurrent()
            assertEquals(ConnectingPhase.CHECKING, rig.model.ui.value.connecting)
            val expiresAt = TestTimeSource().markNow()
            rig.control.state.value = PairingState.Verify(PREVIEW_SAS, expiresAt, PHONE)
            runCurrent()
            assertEquals(ConnectingPhase.SECURING, rig.model.ui.value.connecting)
            assertEquals(
                OnboardingKey.Connecting,
                rig.model.stack.value
                    .last(),
            )
            advanceTimeBy(SECURING_LINE_MILLIS + 1)
            assertEquals(listOf(PAIR, SCAN, OnboardingKey.Verify), rig.model.stack.value)
            assertEquals(VerifyFacts(PREVIEW_SAS, expiresAt, PHONE), rig.model.ui.value.verify)
            assertEquals(PairingWait(HOST, expiresAt), rig.wait.value)
        }

    @Test
    fun `Cancel on Verify ends the ceremony and the pairing-wait notification`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pasted()
            runCurrent()
            rig.control.state.value = PairingState.Verify(PREVIEW_SAS, TestTimeSource().markNow(), PHONE)
            advanceUntilIdle()
            assertNotNull(rig.wait.value)
            rig.model.back()
            advanceUntilIdle()
            assertEquals(listOf(PAIR, SCAN), rig.model.stack.value)
            assertEquals(1, rig.control.cancels)
            assertNull(rig.wait.value)
        }

    @Test
    fun `the record is stored, with a tint, before Paired shows, and notifications are offered for push`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pasted()
            runCurrent()
            rig.control.state.value = PairingState.Approved(facts(gateway = 1), idleSession(backgroundScope))
            rig.shows(OnboardingKey.Paired)
            val stored =
                rig.store.instances
                    .first()
                    .single()
            assertEquals(facts(gateway = 1).id, stored.id)
            assertEquals("Slate", stored.tint)
            assertEquals(listOf<String?>(null), rig.control.replaced)
            val paired = checkNotNull(rig.model.ui.value.paired)
            assertEquals(stored, paired.record)
            assertFalse(paired.needsName)
            assertTrue(paired.offerNotifications)
            assertEquals(0, rig.control.cancels)
            rig.model.continueFromPaired()
            assertEquals(listOf(OnboardingKey.Notifications), rig.model.stack.value)
            rig.model.notificationsAnswered(granted = true)
            rig.shows()
            assertTrue(
                rig.store.instances
                    .first()
                    .single()
                    .notificationsEnabled,
            )
        }

    @Test
    fun `a daemon without push goes from Paired straight to the root`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pasted()
            runCurrent()
            val approved = facts(gateway = 1, push = emptyList())
            rig.control.state.value = PairingState.Approved(approved, idleSession(backgroundScope))
            rig.shows(OnboardingKey.Paired)
            assertFalse(checkNotNull(rig.model.ui.value.paired).offerNotifications)
            rig.model.continueFromPaired()
            assertEquals(emptyList<OnboardingKey>(), rig.model.stack.value)
        }

    @Test
    fun `a second Fermix on the same computer is named before it goes on`() =
        runTest(main) {
            val rig = Rig(this)
            rig.store.upsert(record(gateway = 5))
            rig.pasted()
            runCurrent()
            val approved = facts(gateway = 1, push = listOf(PushPlatform.FCM))
            rig.control.state.value = PairingState.Approved(approved, idleSession(backgroundScope))
            rig.shows(OnboardingKey.Paired)
            val paired = checkNotNull(rig.model.ui.value.paired)
            assertTrue(paired.needsName)
            assertEquals("Sage", paired.record.tint)
            rig.model.continueFromPaired()
            assertEquals(listOf(OnboardingKey.Paired, OnboardingKey.Name), rig.model.stack.value)
            rig.model.name("Dev")
            rig.shows(OnboardingKey.Notifications)
            assertEquals(
                "Dev",
                rig.store.instances
                    .first()
                    .single { it.id == approved.id }
                    .nickname,
            )
        }

    @Test
    fun `can't reach keeps its link for Try again, and a refused retry goes back to the scan`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pasted()
            runCurrent()
            rig.control.state.value = PairingState.CannotReach(mapOf(TAILNET to IOException("timed out")))
            runCurrent()
            val cantReach = OnboardingKey.Failure(FailureCase.CANT_REACH)
            assertEquals(listOf(PAIR, SCAN, cantReach), rig.model.stack.value)
            rig.model.ceremony.act(FailureCase.CANT_REACH, FailureAction.TRY_AGAIN)
            runCurrent()
            assertEquals(1, rig.control.retries)
            assertEquals(0, rig.control.cancels)
            rig.control.retryAnswer = Retry.REFUSED
            rig.model.ceremony.act(FailureCase.CANT_REACH, FailureAction.TRY_AGAIN)
            advanceUntilIdle()
            assertEquals(listOf(PAIR, SCAN), rig.model.stack.value)
            assertEquals(1, rig.control.cancels)
        }

    @Test
    fun `a VPN on this phone with a tailnet candidate is its own screen`() =
        runTest(main) {
            val rig = Rig(this)
            rig.network.value =
                NetworkFacts(1L, defaultHasVpn = true, defaultHasCgnatAddress = false, otherUidVpnPresent = false)
            rig.pasted()
            runCurrent()
            rig.control.state.value = PairingState.CannotReach(mapOf(TAILNET to IOException("timed out")))
            runCurrent()
            assertEquals(
                OnboardingKey.Failure(FailureCase.VPN_HOLDS_THE_SLOT),
                rig.model.stack.value
                    .last(),
            )
        }

    @Test
    fun `the wrong machine ends the ceremony, offers no retry, and starts over on Pair`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pasted()
            runCurrent()
            rig.control.state.value = PairingState.WrongMachine
            advanceUntilIdle()
            assertEquals(
                OnboardingKey.Failure(FailureCase.WRONG_MACHINE),
                rig.model.stack.value
                    .last(),
            )
            assertEquals(1, rig.control.cancels)
            assertThrows<IllegalArgumentException> {
                rig.model.ceremony.act(FailureCase.WRONG_MACHINE, FailureAction.TRY_AGAIN)
            }
            rig.model.ceremony.act(FailureCase.WRONG_MACHINE, FailureAction.START_OVER)
            assertEquals(listOf(PAIR), rig.model.stack.value)
        }

    @Test
    fun `a link the ceremony refuses on the phone is the scan's refusal`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pasted()
            runCurrent()
            rig.control.state.value = PairingState.InvalidLink("8.8.8.8 is outside the LAN and tailnet ranges")
            advanceUntilIdle()
            assertEquals(listOf(PAIR, SCAN), rig.model.stack.value)
            assertEquals("8.8.8.8 is outside the LAN and tailnet ranges", rig.model.ui.value.scanRefusal)
            rig.model.ceremony.onLink(linkText())
            assertNull(rig.model.ui.value.scanRefusal)
            assertEquals(2, rig.starter.started.size)
        }

    @Test
    fun `every ending of a ceremony waiting on Verify ends the pairing-wait notification`() =
        runTest(main) {
            val endings =
                listOf(
                    PairingState.InvalidLink("8.8.8.8 is outside the LAN and tailnet ranges"),
                    PairingState.Expired,
                    PairingState.Denied,
                    PairingState.RateLimited,
                    PairingState.AnotherPairingInProgress,
                    PairingState.CannotReach(mapOf(TAILNET to IOException("timed out"))),
                    PairingState.WrongMachine,
                    PairingState.OlderFermix,
                    PairingState.NewerFermix,
                    PairingState.AttestationRefused,
                    PairingState.AttestationUnavailable,
                    PairingState.LostMidWait,
                    PairingState.ProtocolError("hello_ack before pair_approved"),
                    PairingState.NoSecureHardware(IOException("no StrongBox")),
                    PairingState.Cancelled,
                )
            for (ending in endings) {
                val rig = Rig(this)
                rig.verifying()
                rig.control.state.value = ending
                advanceUntilIdle()
                assertNull(rig.wait.value, "$ending")
            }
        }

    @Test
    fun `an approval on Verify ends the pairing-wait notification, and so does the ViewModel's end`() =
        runTest(main) {
            val approved = Rig(this)
            approved.verifying()
            approved.control.state.value = PairingState.Approved(facts(gateway = 1), idleSession(backgroundScope))
            approved.shows(OnboardingKey.Paired)
            assertNull(approved.wait.value)
            val cleared = Rig(this)
            cleared.verifying()
            cleared.viewModels.clear()
            assertNull(cleared.wait.value)
        }

    @Test
    fun `pairing a Fermix again hands the commit the key of the record it replaced`() =
        runTest(main) {
            val rig = Rig(this)
            val old = record(gateway = 1).copy(keyAlias = "fermix.device.1.0807060504030201")
            rig.store.upsert(old)
            rig.pasted()
            runCurrent()
            rig.control.state.value = PairingState.Approved(facts(gateway = 1), idleSession(backgroundScope))
            rig.shows(OnboardingKey.Paired)
            assertEquals(listOf<String?>(old.keyAlias), rig.control.replaced)
            val stored =
                rig.store.instances
                    .first()
                    .single()
            assertEquals(facts(gateway = 1).keyAlias, stored.keyAlias)
        }

    @Test
    fun `the paired session the approval hands over is closed once the record is stored`() =
        runTest(main) {
            val rig = Rig(this)
            rig.pasted()
            runCurrent()
            val session = idleSession(backgroundScope)
            rig.control.state.value = PairingState.Approved(facts(gateway = 1), session)
            rig.shows(OnboardingKey.Paired)
            assertEquals(SessionState.Closed, session.state.value)
            assertEquals(
                1,
                rig.store.instances
                    .first()
                    .size,
            )
        }
}
