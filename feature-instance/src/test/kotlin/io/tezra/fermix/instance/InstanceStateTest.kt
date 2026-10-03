package io.tezra.fermix.instance

import io.tezra.fermix.data.ChatState
import io.tezra.fermix.session.Diagnostic
import io.tezra.fermix.session.DiagnosticKind
import io.tezra.fermix.session.SessionState
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import io.tezra.fermix.transport.Reachability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A phone online with Tailscale up: its default network a VPN holding a 100.64/10 address (design section 5.2). */
private val TAILSCALE_UP =
    NetworkFacts(1L, defaultHasVpn = true, defaultHasCgnatAddress = true, otherUidVpnPresent = false)

/** Online, Tailscale off. */
private val TAILSCALE_OFF =
    NetworkFacts(1L, defaultHasVpn = false, defaultHasCgnatAddress = false, otherUidVpnPresent = false)

private val CHAT = ChatState(draft = null, agentName = null, previews = false)

/**
 * The Instance screen's state (design section 13.7): which candidates are reachable, from the live connection,
 * the last test and the network facts, and the screen's state read from the record, the session and the chat.
 */
class InstanceStateTest {
    private val both = listOf(LAN, TAILNET)

    @Test
    fun `the live candidate is reachable, and the facts light no other, a likely tailnet's neither`() {
        assertEquals(setOf(LAN), reachableCandidates(both, LAN, TestState.Idle, Reachability.TAILSCALE_OFF))
        assertEquals(setOf(LAN), reachableCandidates(both, LAN, TestState.Idle, Reachability.TAILNET_LIKELY))
        assertEquals(emptySet<Candidate>(), reachableCandidates(both, null, TestState.Idle, Reachability.NO_NETWORK))
    }

    @Test
    fun `a Fermix that cannot be reached lights no candidate while Tailscale is likely up and no test ran`() {
        val cannotReach = Watched(SessionState.CannotReach, emptyList(), live = null)
        val ui =
            instanceUiOf(
                listOf(sample()),
                sample().id,
                cannotReach,
                PhoneFacts(CHAT, ScreenFacts(TestState.Idle, cacheBytes = null), TAILSCALE_UP),
                releaseBuild = true,
            )
        assertEquals(Link.CannotReach, ui?.link)
        assertEquals(emptySet<Candidate>(), ui?.reachable)
        assertEquals(
            emptySet<Candidate>(),
            reachableCandidates(both, null, TestState.Idle, Reachability.TAILNET_LIKELY),
        )
    }

    @Test
    fun `a test marks each candidate by its own attempt, and says nothing of those the winner cancelled`() {
        val won = TestState.Done(TestOutcome.Reached(LAN, millis = 12))
        assertEquals(setOf(LAN), reachableCandidates(both, null, won, Reachability.TAILSCALE_OFF))
        assertEquals(setOf(LAN), reachableCandidates(both, null, won, Reachability.TAILNET_LIKELY))
        val tailnetFailed = TestState.Done(TestOutcome.Reached(LAN, millis = 12, failed = setOf(TAILNET)))
        assertEquals(setOf(LAN), reachableCandidates(both, null, tailnetFailed, Reachability.TAILNET_LIKELY))
        val none = TestState.Done(TestOutcome.NotReached(failed = both.toSet()))
        assertEquals(emptySet<Candidate>(), reachableCandidates(both, null, none, Reachability.TAILNET_LIKELY))
        // The live connection is a handshake now, whatever a test said before.
        assertEquals(setOf(TAILNET), reachableCandidates(both, TAILNET, none, Reachability.TAILSCALE_OFF))
    }

    @Test
    fun `the facts put out a test's word that no longer holds, and never light a dot`() {
        val tailnetWon = TestState.Done(TestOutcome.Reached(TAILNET, millis = 38))
        assertEquals(setOf(TAILNET), reachableCandidates(both, null, tailnetWon, Reachability.TAILNET_LIKELY))
        for (out in listOf(
            Reachability.TAILSCALE_OFF,
            Reachability.EXCLUDED_FROM_TAILSCALE,
            Reachability.VPN_HOLDS_THE_SLOT,
            Reachability.NO_NETWORK,
        )) {
            assertEquals(emptySet<Candidate>(), reachableCandidates(both, null, tailnetWon, out), "$out")
        }
        val lanWon = TestState.Done(TestOutcome.Reached(LAN, millis = 12))
        assertEquals(setOf(LAN), reachableCandidates(both, null, lanWon, Reachability.TAILSCALE_OFF))
        assertEquals(emptySet<Candidate>(), reachableCandidates(both, null, lanWon, Reachability.NO_NETWORK))
    }

    @Test
    fun `the screen reads the record, the session, the chat, the test and the cache, and none once the record goes`() {
        val record = sample(nickname = "Studio")
        val other = sample(gateway = 2)
        val connected = SessionState.Connected(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true)
        val log = listOf(Diagnostic(0, DiagnosticKind.UNKNOWN_EVENT, "t=call_offer"))
        val facts = ScreenFacts(TestState.Running, cacheBytes = 4_096)
        val ui =
            checkNotNull(
                instanceUiOf(
                    listOf(other, record),
                    record.id,
                    Watched(connected, log, live = LAN),
                    PhoneFacts(CHAT, facts, TAILSCALE_UP),
                    releaseBuild = true,
                ),
            )
        assertEquals(record, ui.record)
        assertEquals(listOf(other), ui.others)
        assertEquals(Link.Up(Candidate.Scope.LAN, 9, caughtUp = true), ui.link)
        assertEquals(log, ui.diagnostics)
        assertEquals(setOf(LAN), ui.reachable)
        assertEquals(false, ui.previews)
        assertEquals(4_096L, ui.cacheBytes)
        assertEquals(TestState.Running, ui.test)
        assertEquals(true, ui.releaseBuild)

        val unwatched = Watched(null, emptyList(), null)
        val offline = PhoneFacts(CHAT, facts, TAILSCALE_OFF)
        val alone = instanceUiOf(listOf(record), record.id, unwatched, offline, releaseBuild = false)
        assertEquals(Link.NotOpen, alone?.link)
        assertEquals(emptySet<Candidate>(), alone?.reachable)
        assertNull(instanceUiOf(listOf(other), record.id, unwatched, offline, releaseBuild = false))
    }
}
