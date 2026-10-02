package io.tezra.fermix.session

import io.tezra.fermix.transport.Candidate
import kotlin.time.TimeMark

/**
 * Where a pairing ceremony stands, for the onboarding screens of design section 13.3; the words are the
 * UI's. "Connecting" reads its three lines off [Reaching], [Checking] and [Securing], and its "Trying
 * Tailscale…" off its own timer over [Reaching]. Every [Ended] state is one row of section 13.3's failure
 * table, or the ceremony cancelled.
 */
sealed interface PairingState {
    /** The link is being checked on the phone: its version and its candidates' ranges. */
    data object Validating : PairingState

    /**
     * The race is on (design section 5.1): [tried] are the candidates whose attempt has started, in the
     * order they started, and [elapsedMs] how long after the race began the last of them did.
     */
    data class Reaching(
        val tried: List<Candidate>,
        val elapsedMs: Long,
    ) : PairingState

    /** A socket is open, its certificate the pinned one; the IKpsk2 handshake is checking the daemon's key. */
    data object Checking : PairingState

    /**
     * The handshake is done, and `pair_request` is going out over it. Verify follows with no suspension
     * between, since sending does not wait, so a collector that conflates (any but an unconfined one) sees
     * Checking then Verify: the Connecting screen paces its third line, "Securing the line…", itself.
     */
    data object Securing : PairingState

    /**
     * `pair_request` went out as [deviceName]: the owner compares [sas] with the daemon's, and decides on
     * the computer before [expiresAt], the end of the phone's 120 s countdown (section 13.3, step 5).
     */
    data class Verify(
        val sas: String,
        val expiresAt: TimeMark,
        val deviceName: String,
    ) : PairingState {
        /** Leaves the SAS out, so no log line that prints a state carries the code. */
        override fun toString(): String = "Verify(expiresAt=$expiresAt, deviceName=$deviceName)"
    }

    /**
     * `pair_approved`: [facts] are the instance record's, and [session] is the paired session that took
     * the same connection over; it sends `hello` at the connection's next seq as soon as its first attempt
     * runs, within the protocol's 10 s. The caller commits the handle with its store of the record, which
     * reports the key alias of the record its write replaced, if one, and the commit deletes that key once
     * the record is stored.
     */
    class Approved(
        val facts: InstanceFacts,
        val session: Session,
    ) : PairingState

    /** A state the ceremony never leaves, but for a [CannotReach] the handle retries. */
    sealed interface Ended : PairingState

    /** The link is not one a daemon writes: no candidate, or one outside the LAN and tailnet ranges. */
    data class InvalidLink(
        val detail: String,
    ) : Ended

    /**
     * The window closed: `pair_denied{timeout}`, or the daemon refused the pairing handshake, which it does
     * when no window is open or the window's secret is another (design section 13.3, "refused after the
     * window"), and also when this address has failed five times (see [RateLimited]).
     */
    data object Expired : Ended

    /** `pair_denied{denied}`, or `{cancelled}`, the owner closing the window, which was `denied` before. */
    data object Denied : Ended

    /**
     * Reserved, and never produced: section 13.3's `pair_denied{rate_limited}`, which onboarding section 4
     * also names, is neither among section 7's reasons nor in the vendored export, and the engine refuses
     * a fifth failure's address with the same `1002` before message 2 that a closed window gets, so that
     * reads as [Expired] until the contract names it.
     */
    data object RateLimited : Ended

    /**
     * The daemon closed `1002` after `pair_request`, which it does when another phone's request is waiting
     * (`request_pending`). The engine sends that same `1002 "mobile protocol error"` for every pairing
     * error, a window that closed after the handshake and a request it found invalid among them, so this
     * reading is the likeliest, not a certain one, until the wire gives the case a signal of its own.
     */
    data object AnotherPairingInProgress : Ended

    /** Every candidate failed, none for the reasons above; [failures] says how each did. Retryable. */
    data class CannotReach(
        val failures: Map<Candidate, Exception>,
    ) : Ended

    /** The certificate is not the one `tls_fp` pins: a security event, never retried. */
    data object WrongMachine : Ended

    /** A link of version 1, or the daemon's version refusal saying it is too old. */
    data object OlderFermix : Ended

    /** The daemon's version refusal saying this app is too old; a newer link is the parse's refusal. */
    data object NewerFermix : Ended

    /** `pair_denied{attestation}`, or `{platform_unsupported}`, its platform's kind of the same refusal. */
    data object AttestationRefused : Ended

    /** `pair_denied{attestation_unavailable}`. */
    data object AttestationUnavailable : Ended

    /** The connection ended while the owner decided, or `pair_denied{device_disconnected}`. */
    data object LostMidWait : Ended

    /** The daemon sent what the ceremony does not take; [detail] names it. */
    data class ProtocolError(
        val detail: String,
    ) : Ended

    /**
     * The Keystore could not make or attest the key, or its chain is not one the daemon takes ([cause]):
     * section 13.3's "No secure hardware". Nothing was sent.
     */
    data class NoSecureHardware(
        val cause: Exception,
    ) : Ended

    /** The handle was cancelled, or its scope ended before the caller took the ending on. */
    data object Cancelled : Ended
}
