package io.tezra.fermix.instance

import io.tezra.fermix.data.Instance
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.Reachability

/**
 * How one "Test connection" race ended (design section 13.7), with [failed], the candidates whose own
 * attempt failed before it ended; the others were cancelled by the winner or never heard from.
 */
sealed interface TestOutcome {
    val failed: Set<Candidate>

    /** [candidate] answered first, its connection open [millis] after the race began. */
    data class Reached(
        val candidate: Candidate,
        val millis: Long,
        override val failed: Set<Candidate> = emptySet(),
    ) : TestOutcome

    /** No candidate answered. */
    data class NotReached(
        override val failed: Set<Candidate>,
    ) : TestOutcome

    /** A candidate presented a certificate that is not the one paired: the daemon's identity changed. */
    data object WrongIdentity : TestOutcome {
        override val failed: Set<Candidate> = emptySet()
    }
}

/**
 * "Test connection": one race over the record's candidates, as a session's (design section 5.1), whose
 * winner is closed at once. The app's opens sockets; the tests' answers as they set it.
 */
fun interface ConnectionTester {
    suspend fun test(instance: Instance): TestOutcome
}

/** The Connection section's test: none yet, one running, or how the last one ended. */
sealed interface TestState {
    data object Idle : TestState

    data object Running : TestState

    data class Done(
        val outcome: TestOutcome,
    ) : TestState
}

/** What the last test said of [candidate]: reached, failed, or nothing, as none ran or it never heard from it. */
private fun TestState.said(candidate: Candidate): Boolean? {
    val outcome = (this as? TestState.Done)?.outcome ?: return null
    return when {
        outcome is TestOutcome.Reached && outcome.candidate == candidate -> true
        candidate in outcome.failed -> false
        else -> null
    }
}

/** The facts that rule a tailnet candidate out (design section 5.2): Tailscale is off, or kept from this app. */
private val TAILNET_RULED_OUT =
    setOf(Reachability.TAILSCALE_OFF, Reachability.EXCLUDED_FROM_TAILSCALE, Reachability.VPN_HOLDS_THE_SLOT)

/**
 * The candidates whose dot is ok (design section 13.7, "candidates with scope and reachability"), each by
 * what answered for that candidate alone: the live connection went over it ([live]), or the last [test]
 * reached it. The network facts ([reachability], design section 5.2) never light a dot, since only a
 * completed handshake confirms a tailnet (core-transport's Reachability); they only put out a test's word
 * that no longer holds: every candidate's with no network, a tailnet one's while Tailscale is off or kept
 * from this app.
 */
fun reachableCandidates(
    candidates: List<Candidate>,
    live: Candidate?,
    test: TestState,
    reachability: Reachability,
): Set<Candidate> =
    candidates
        .filter { candidate ->
            candidate == live || (test.said(candidate) == true && !ruledOut(candidate, reachability))
        }.toSet()

private fun ruledOut(
    candidate: Candidate,
    reachability: Reachability,
): Boolean =
    reachability == Reachability.NO_NETWORK ||
        (candidate.scope == Candidate.Scope.TAILNET && reachability in TAILNET_RULED_OUT)
