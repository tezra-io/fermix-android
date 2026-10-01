package io.tezra.fermix.session

import io.tezra.fermix.transport.Candidate

/**
 * What a session says about its link, for the app bar's subtitle and the connection dot (design
 * section 13.5); the words are the UI's. A turn's thinking is not a session state: it is per turn.
 */
sealed interface SessionState {
    /** The phone has no network. */
    data object WaitingForNetwork : SessionState

    /** A race is running, or the races have failed for less than 30 s. */
    data object Connecting : SessionState

    /**
     * A handshake and `hello` completed over a candidate of [scope]. [latencyMs] is the last round trip,
     * `hello` to `hello_ack` and then each `ping` to its `pong`. [caughtUp] is false from `hello_ack`
     * until the connection's reconnect reconciliation is done and its history pulls have reached the
     * head, the subtitle's `Updating…` (design section 13.5). A reconnect after the hourly close that
     * completes while the link is still shown up and caught up keeps it true, so the subtitle stays silent.
     */
    data class Connected(
        val scope: Candidate.Scope,
        val latencyMs: Long,
        val caughtUp: Boolean,
    ) : SessionState

    /** The phone has a network and every candidate has failed for 30 s; the session keeps trying. */
    data object CannotReach : SessionState

    /**
     * The app put the session aside, its socket closed, until it resumes it (design section 12.5); or
     * the session ran out of races in one run (MAX_RACES), and the app's resume races again.
     */
    data object Suspended : SessionState

    /** A state the session never leaves: it reconnects no more. */
    sealed interface Ended : SessionState

    /** Unpaired by the daemon: `4003` while live, or `4004` on a reconnect. */
    data object Revoked : Ended

    /** The daemon is not the one paired: its certificate or its Noise key changed (onboarding gotcha 9). */
    data object IdentityChanged : Ended

    /** The daemon does not speak protocol v2 yet: "Update Fermix on {host}". */
    data object OlderDaemon : Ended

    /** The daemon no longer speaks protocol v2: "Update Fermix on this phone". */
    data object NewerDaemon : Ended

    /** `4001`: another connection of this phone took over. */
    data object Replaced : Ended

    /** The app closed the session, or ended the scope it ran in. */
    data object Closed : Ended

    /** The session failed on a fault of the phone's own, its store or its announcer; [cause] says which. */
    data class Failed(
        val cause: Throwable,
    ) : Ended
}
