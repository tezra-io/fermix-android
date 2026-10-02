package io.tezra.fermix.onboarding

import androidx.navigation3.runtime.NavKey
import io.tezra.fermix.session.PairingState
import io.tezra.fermix.transport.Reachability
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.time.TestTimeSource

/** The app's root, as the app keys its Chats list. */
private data object Chats : NavKey

/** The route rules of design section 13.3: the stacks, where each ending and each failure action lead. */
class RoutesTest {
    @Test
    fun `each screen stands on the stack section 13 3 walks to it, back removing the top`() {
        val pair = OnboardingKey.Pair
        val scan = OnboardingKey.Scan
        assertEquals(emptyList<OnboardingKey>(), stackOf(OnboardingKey.Welcome))
        assertEquals(listOf(pair), stackOf(pair))
        assertEquals(listOf(pair, scan), stackOf(scan))
        assertEquals(listOf(pair, scan, OnboardingKey.Connecting), stackOf(OnboardingKey.Connecting))
        assertEquals(listOf(pair, scan, OnboardingKey.Verify), stackOf(OnboardingKey.Verify))
        assertEquals(listOf(OnboardingKey.Paired), stackOf(OnboardingKey.Paired))
        assertEquals(listOf(OnboardingKey.Paired, OnboardingKey.Name), stackOf(OnboardingKey.Name))
        assertEquals(listOf(OnboardingKey.Notifications), stackOf(OnboardingKey.Notifications))
    }

    @Test
    fun `the screen on top is the stack's last, and Welcome when onboarding shows none`() {
        val gate = OnboardingKey.Failure(FailureCase.NO_SECURE_HARDWARE)
        assertEquals(OnboardingKey.Welcome, topOf(emptyList()))
        assertEquals(OnboardingKey.Pair, topOf(stackOf(OnboardingKey.Pair)))
        assertEquals(OnboardingKey.Scan, topOf(stackOf(OnboardingKey.Scan)))
        assertEquals(gate, topOf(stackOf(gate)))
    }

    @Test
    fun `a failure stands over the scan, but the gate's stands on Welcome alone`() {
        for (case in FailureCase.entries - FailureCase.NO_SECURE_HARDWARE) {
            val key = OnboardingKey.Failure(case)
            assertEquals(listOf(OnboardingKey.Pair, OnboardingKey.Scan, key), stackOf(key))
        }
        val gate = OnboardingKey.Failure(FailureCase.NO_SECURE_HARDWARE)
        assertEquals(listOf(gate), stackOf(gate))
    }

    @Test
    fun `every ending of the table leads to its own failure screen`() {
        val endings =
            mapOf(
                PairingState.Expired to FailureCase.EXPIRED,
                PairingState.Denied to FailureCase.DENIED,
                PairingState.RateLimited to FailureCase.RATE_LIMITED,
                PairingState.AnotherPairingInProgress to FailureCase.ANOTHER_PAIRING,
                PairingState.WrongMachine to FailureCase.WRONG_MACHINE,
                PairingState.OlderFermix to FailureCase.OLDER_FERMIX,
                PairingState.NewerFermix to FailureCase.NEWER_FERMIX,
                PairingState.AttestationRefused to FailureCase.ATTESTATION_REFUSED,
                PairingState.AttestationUnavailable to FailureCase.ATTESTATION_UNAVAILABLE,
                PairingState.LostMidWait to FailureCase.LOST_MID_WAIT,
                PairingState.ProtocolError("hello_ack before pair_approved") to FailureCase.PROTOCOL_ERROR,
                PairingState.NoSecureHardware(IOException("no StrongBox")) to FailureCase.NO_SECURE_HARDWARE,
            )
        for ((ending, case) in endings) {
            assertEquals(OnboardingKey.Failure(case), keyAfter(ending, Reachability.PLAIN), "$ending")
        }
    }

    @Test
    fun `can't reach reads the network as section 5 2 does`() {
        val unreached = PairingState.CannotReach(mapOf(TAILNET to IOException("timed out")))
        val cases =
            mapOf(
                Reachability.EXCLUDED_FROM_TAILSCALE to FailureCase.EXCLUDED_FROM_TAILSCALE,
                Reachability.VPN_HOLDS_THE_SLOT to FailureCase.VPN_HOLDS_THE_SLOT,
                Reachability.TAILSCALE_OFF to FailureCase.CANT_REACH,
                Reachability.NO_NETWORK to FailureCase.CANT_REACH,
                Reachability.PLAIN to FailureCase.CANT_REACH,
                Reachability.TAILNET_LIKELY to FailureCase.CANT_REACH,
            )
        for ((reachability, case) in cases) {
            assertEquals(OnboardingKey.Failure(case), keyAfter(unreached, reachability), "$reachability")
        }
    }

    @Test
    fun `a link refused on the phone, and a cancel, go back to the scan`() {
        assertEquals(OnboardingKey.Scan, keyAfter(PairingState.InvalidLink("8.8.8.8 is public"), Reachability.PLAIN))
        assertEquals(OnboardingKey.Scan, keyAfter(PairingState.Cancelled, Reachability.PLAIN))
    }

    @Test
    fun `scan again and start over lead back, and only can't reach retries its link`() {
        assertEquals(FailureStep.Go(OnboardingKey.Scan), stepAfter(FailureCase.EXPIRED, FailureAction.SCAN_AGAIN))
        assertEquals(FailureStep.Go(OnboardingKey.Pair), stepAfter(FailureCase.DENIED, FailureAction.START_OVER))
        assertEquals(FailureStep.Retry, stepAfter(FailureCase.CANT_REACH, FailureAction.TRY_AGAIN))
        assertEquals(
            FailureStep.Go(OnboardingKey.Scan),
            stepAfter(FailureCase.ANOTHER_PAIRING, FailureAction.TRY_AGAIN),
        )
        assertEquals(
            FailureStep.Go(OnboardingKey.Scan),
            stepAfter(FailureCase.ATTESTATION_UNAVAILABLE, FailureAction.TRY_AGAIN),
        )
        assertEquals(FailureStep.Go(OnboardingKey.Pair), stepAfter(FailureCase.OLDER_FERMIX, FailureAction.OK))
        assertEquals(
            FailureStep.Go(OnboardingKey.Welcome),
            stepAfter(FailureCase.NO_SECURE_HARDWARE, FailureAction.OK),
        )
    }

    @Test
    fun `the wrong machine has no retry, and its one way on is to start over`() {
        val case = FailureCase.WRONG_MACHINE
        assertEquals(FailureStep.Go(OnboardingKey.Pair), stepAfter(case, FailureAction.START_OVER))
        for (action in FailureAction.entries - FailureAction.START_OVER - FailureAction.TROUBLESHOOTING) {
            assertThrows<IllegalArgumentException> { stepAfter(case, action) }
        }
    }

    @Test
    fun `the pages, the apps and the paste are outside the ceremony`() {
        val outside =
            listOf(
                FailureCase.CANT_REACH to FailureAction.OPEN_TAILSCALE,
                FailureCase.EXCLUDED_FROM_TAILSCALE to FailureAction.OPEN_TAILSCALE,
                FailureCase.VPN_HOLDS_THE_SLOT to FailureAction.OPEN_VPN_SETTINGS,
                FailureCase.NEWER_FERMIX to FailureAction.OPEN_RELEASE_PAGE,
                FailureCase.ATTESTATION_REFUSED to FailureAction.LEARN_MORE,
                FailureCase.EXPIRED to FailureAction.TROUBLESHOOTING,
                FailureCase.EXPIRED to FailureAction.PASTE_LINK,
            )
        for ((case, action) in outside) assertEquals(FailureStep.Outside, stepAfter(case, action), "$case $action")
    }

    @Test
    fun `an action its screen does not show is refused`() {
        assertThrows<IllegalArgumentException> { stepAfter(FailureCase.EXPIRED, FailureAction.TRY_AGAIN) }
        assertThrows<IllegalArgumentException> { stepAfter(FailureCase.OLDER_FERMIX, FailureAction.PASTE_LINK) }
    }

    @Test
    fun `each of the ceremony's twenty-one states has its screen, and what the screen shows`() =
        runTest {
            val ui = OnboardingUi(deviceName = PHONE, host = HOST)
            val expiresAt = TestTimeSource().markNow()
            val refused = "8.8.8.8 is outside the LAN and tailnet ranges"

            fun connecting(phase: ConnectingPhase) = OnboardingKey.Connecting to ui.copy(connecting = phase)

            fun failed(case: FailureCase) = OnboardingKey.Failure(case) to ui

            val rows =
                listOf(
                    PairingState.Validating to connecting(ConnectingPhase.REACHING),
                    PairingState.Reaching(listOf(LAN, TAILNET), elapsedMs = 4_200) to
                        connecting(ConnectingPhase.TRYING_TAILSCALE),
                    PairingState.Checking to connecting(ConnectingPhase.CHECKING),
                    PairingState.Securing to connecting(ConnectingPhase.SECURING),
                    PairingState.Verify(PREVIEW_SAS, expiresAt, PHONE) to
                        (OnboardingKey.Verify to ui.copy(verify = VerifyFacts(PREVIEW_SAS, expiresAt, PHONE))),
                    PairingState.Approved(facts(gateway = 1), idleSession(backgroundScope)) to
                        (OnboardingKey.Paired to ui),
                    PairingState.InvalidLink(refused) to (OnboardingKey.Scan to ui.copy(scanRefusal = refused)),
                    PairingState.Expired to failed(FailureCase.EXPIRED),
                    PairingState.Denied to failed(FailureCase.DENIED),
                    PairingState.RateLimited to failed(FailureCase.RATE_LIMITED),
                    PairingState.AnotherPairingInProgress to failed(FailureCase.ANOTHER_PAIRING),
                    PairingState.CannotReach(mapOf(TAILNET to IOException("timed out"))) to
                        failed(FailureCase.CANT_REACH),
                    PairingState.WrongMachine to failed(FailureCase.WRONG_MACHINE),
                    PairingState.OlderFermix to failed(FailureCase.OLDER_FERMIX),
                    PairingState.NewerFermix to failed(FailureCase.NEWER_FERMIX),
                    PairingState.AttestationRefused to failed(FailureCase.ATTESTATION_REFUSED),
                    PairingState.AttestationUnavailable to failed(FailureCase.ATTESTATION_UNAVAILABLE),
                    PairingState.LostMidWait to failed(FailureCase.LOST_MID_WAIT),
                    PairingState.ProtocolError("hello_ack before pair_approved") to failed(FailureCase.PROTOCOL_ERROR),
                    PairingState.NoSecureHardware(IOException("no StrongBox")) to
                        failed(FailureCase.NO_SECURE_HARDWARE),
                    PairingState.Cancelled to (OnboardingKey.Scan to ui),
                )
            assertEquals(21, rows.map { (state, _) -> state::class }.toSet().size)
            for ((state, screen) in rows) {
                assertEquals(screen, screenFor(state, ui, Reachability.PLAIN, reachingLong = true), "$state")
            }
        }

    @Test
    fun `the connecting line follows the ceremony, and Tailscale only after 4 s with a tailnet candidate`() {
        val both = PairingState.Reaching(listOf(LAN, TAILNET), elapsedMs = 120)
        val lanOnly = PairingState.Reaching(listOf(LAN), elapsedMs = 120)
        assertEquals(ConnectingPhase.REACHING, connectingPhase(PairingState.Validating, reachingLong = false))
        assertEquals(ConnectingPhase.REACHING, connectingPhase(both, reachingLong = false))
        assertEquals(ConnectingPhase.TRYING_TAILSCALE, connectingPhase(both, reachingLong = true))
        assertEquals(ConnectingPhase.REACHING, connectingPhase(lanOnly, reachingLong = true))
        assertEquals(ConnectingPhase.CHECKING, connectingPhase(PairingState.Checking, reachingLong = true))
        assertEquals(ConnectingPhase.SECURING, connectingPhase(PairingState.Securing, reachingLong = false))
        assertThrows<IllegalStateException> { connectingPhase(PairingState.Expired, reachingLong = false) }
    }

    @Test
    fun `the app's stack stands on Welcome until a Fermix is paired, then on the Chats list`() {
        val pairing = listOf(OnboardingKey.Pair, OnboardingKey.Scan)
        assertEquals(
            listOf(OnboardingKey.Welcome),
            appBackStack(paired = false, chats = Chats, onboarding = emptyList()),
        )
        assertEquals(
            listOf(OnboardingKey.Welcome) + pairing,
            appBackStack(paired = false, chats = Chats, onboarding = pairing),
        )
        assertEquals(listOf(Chats, OnboardingKey.Paired), appBackStack(true, Chats, listOf(OnboardingKey.Paired)))
        assertThrows<IllegalArgumentException> { appBackStack(true, OnboardingKey.Welcome, emptyList()) }
    }

    @Test
    fun `every onboarding screen keeps the window secure, and the Chats list does not`() {
        val screens = listOf(OnboardingKey.Welcome, OnboardingKey.Verify, OnboardingKey.Notifications)
        for (screen in screens + FailureCase.entries.map(OnboardingKey::Failure)) assertTrue(securesWindow(screen))
        assertFalse(securesWindow(Chats))
    }

    @Test
    fun `only the scan's camera lies dark under the system bars, in both modes`() {
        val screens =
            listOf(
                OnboardingKey.Welcome,
                OnboardingKey.Pair,
                OnboardingKey.Connecting,
                OnboardingKey.Verify,
                OnboardingKey.Paired,
                OnboardingKey.Name,
                OnboardingKey.Notifications,
            ) + FailureCase.entries.map(OnboardingKey::Failure)
        assertTrue(darkUnderBars(OnboardingKey.Scan))
        for (screen in screens) assertFalse(darkUnderBars(screen), "$screen")
        assertFalse(darkUnderBars(Chats))
    }

    @Test
    fun `the step dots count Tailscale as part of reaching`() {
        assertEquals(listOf(0, 0, 1, 2), ConnectingPhase.entries.map(::stepOf))
    }
}
