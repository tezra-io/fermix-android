package io.tezra.fermix.transport

/** How long every candidate must have failed, with a network, before the phone cannot reach the instance. */
const val CANNOT_REACH_AFTER_MS = 30_000L

/** What the app bar says about an instance's link (design section 13.5); the UI picks the words. */
sealed interface LinkStatus {
    /** The phone has no network: "Waiting for network…". */
    data object WaitingForNetwork : LinkStatus

    /** A race is running, or the races have failed for less than 30 s: "Connecting…". */
    data object Connecting : LinkStatus

    /** The phone has a network and every candidate has failed for 30 s: "Can't reach {instance}". */
    data object CannotReach : LinkStatus

    /** A handshake completed over [candidate]; its scope is what the subtitle names. */
    data class Connected(
        val candidate: Candidate,
    ) : LinkStatus
}

/**
 * The clock behind "Can't reach" (onboarding gotcha 20, design section 13.5). It says
 * [LinkStatus.CannotReach] only when both facts hold: the phone has a network, and every candidate
 * has failed for [CANNOT_REACH_AFTER_MS]. The clock starts at the first race of a run that every
 * candidate failed, keeps running across races and across a change of network, and stops when the
 * phone loses its network or a handshake completes; the first completed handshake clears the line. A
 * lost network ends a live connection's claim too, and a change of network leaves it to the caller,
 * which re-races and reports the connection [lost] when it drops it.
 * The reconnect loop keeps racing under either line. A value: every event gives a new tracker, and the
 * caller's clock is monotonic milliseconds.
 */
class UnreachableTracker private constructor(
    private val hasNetwork: Boolean,
    private val failingSinceMs: Long?,
    private val live: Candidate?,
) {
    /** Before the first network facts: no network yet. */
    constructor() : this(hasNetwork = false, failingSinceMs = null, live = null)

    /** When "Can't reach" begins if nothing changes, for the caller to look again then; null when no clock runs. */
    val cannotReachAtMs: Long? get() = failingSinceMs?.plus(CANNOT_REACH_AFTER_MS)

    /**
     * The network changed. Losing it stops the clock and ends the live connection's claim: when a
     * network comes back, only a new handshake says connected (design section 13.5, "The dot is never
     * optimistic").
     */
    fun network(facts: NetworkFacts): UnreachableTracker =
        UnreachableTracker(
            facts.hasNetwork,
            failingSinceMs.takeIf { facts.hasNetwork },
            live.takeIf { facts.hasNetwork },
        )

    /** A race ended with every candidate failed at [atMs]; with a network, the clock starts if it was not running. */
    fun allFailed(atMs: Long): UnreachableTracker {
        require(atMs >= 0) { "a monotonic time of $atMs ms is negative" }
        return UnreachableTracker(hasNetwork, (failingSinceMs ?: atMs).takeIf { hasNetwork }, live = null)
    }

    /** A handshake completed over [candidate]: the clock stops. */
    fun connected(candidate: Candidate): UnreachableTracker =
        UnreachableTracker(hasNetwork, failingSinceMs = null, live = candidate)

    /** The live connection ended; the reconnect loop races again. */
    fun lost(): UnreachableTracker = UnreachableTracker(hasNetwork, failingSinceMs, live = null)

    fun status(nowMs: Long): LinkStatus {
        require(nowMs >= 0) { "a monotonic time of $nowMs ms is negative" }
        val cannotReachAt = cannotReachAtMs
        return when {
            !hasNetwork -> LinkStatus.WaitingForNetwork
            live != null -> LinkStatus.Connected(live)
            cannotReachAt != null && nowMs >= cannotReachAt -> LinkStatus.CannotReach
            else -> LinkStatus.Connecting
        }
    }
}
