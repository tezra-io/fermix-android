package io.tezra.fermix.onboarding

import io.tezra.fermix.session.PairingState
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.Reachability

/**
 * The onboarding part of the back stack that shows [key], above the app's root (Welcome, or the Chats
 * list once a Fermix is paired), in design section 13.2's order. Back always removes the top: from
 * Connecting, Verify or a failure it returns to Scan, which ends the pairing, and once a pairing is
 * approved nothing leads back into the ceremony. No secure hardware stands on Welcome alone, as the gate
 * runs there. Welcome is a root, with nothing above it.
 */
fun stackOf(key: OnboardingKey): List<OnboardingKey> =
    when (key) {
        OnboardingKey.Welcome -> emptyList()
        OnboardingKey.Pair -> listOf(OnboardingKey.Pair)
        OnboardingKey.Scan -> listOf(OnboardingKey.Pair, OnboardingKey.Scan)
        OnboardingKey.Connecting -> listOf(OnboardingKey.Pair, OnboardingKey.Scan, OnboardingKey.Connecting)
        OnboardingKey.Verify -> listOf(OnboardingKey.Pair, OnboardingKey.Scan, OnboardingKey.Verify)
        OnboardingKey.Paired -> listOf(OnboardingKey.Paired)
        OnboardingKey.Name -> listOf(OnboardingKey.Paired, OnboardingKey.Name)
        OnboardingKey.Notifications -> listOf(OnboardingKey.Notifications)
        is OnboardingKey.Failure -> failureStack(key)
    }

/**
 * The screen on top of [onboarding]: its last, or Welcome, onboarding's root, when it holds none. A screen
 * popped off the stack is still drawn, and hit, until its exit ends, so the entries hand the ViewModel an
 * action only from the screen on top; the ViewModel holds each call to the screen it belongs to.
 */
fun topOf(onboarding: List<OnboardingKey>): OnboardingKey = onboarding.lastOrNull() ?: OnboardingKey.Welcome

private fun failureStack(key: OnboardingKey.Failure): List<OnboardingKey> =
    if (key.case == FailureCase.NO_SECURE_HARDWARE) {
        listOf(key)
    } else {
        listOf(OnboardingKey.Pair, OnboardingKey.Scan, key)
    }

/**
 * The screen [state] shows, and [ui] as that screen shows it (design section 13.3): Connecting with its
 * line, Verify with its facts, Paired, a failure screen, or the scan with the reason the phone refused the
 * link. [reachability] reads a "Can't reach" ([keyAfter]); [reachingLong] is whether Reaching has lasted
 * 4 s ([connectingPhase]). Paired shows the stored record, which only the commit gives, so [ui] is left as
 * it is for it.
 */
fun screenFor(
    state: PairingState,
    ui: OnboardingUi,
    reachability: Reachability,
    reachingLong: Boolean,
): Pair<OnboardingKey, OnboardingUi> =
    when (state) {
        is PairingState.Approved -> {
            OnboardingKey.Paired to ui
        }

        is PairingState.Verify -> {
            val facts = VerifyFacts(state.sas, state.expiresAt, state.deviceName)
            OnboardingKey.Verify to ui.copy(verify = facts)
        }

        is PairingState.InvalidLink -> {
            keyAfter(state, reachability) to ui.copy(scanRefusal = state.detail)
        }

        is PairingState.Ended -> {
            keyAfter(state, reachability) to ui
        }

        else -> {
            OnboardingKey.Connecting to ui.copy(connecting = connectingPhase(state, reachingLong))
        }
    }

/** The endings that are one failure screen each, whatever the network says. */
private val FAILURE_OF_ENDING: Map<PairingState.Ended, FailureCase> =
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
    )

/**
 * The screen a pairing [ending] leads to (design section 13.3's failure table). "Can't reach" is
 * section 5.2's reading of the network as every candidate failed, [reachability]: split out of
 * Tailscale, or a VPN holding the slot, are screens of their own. A link the ceremony refused on the
 * phone is the scan's "That's not a Fermix pairing code.", and a cancel goes back to the scan too.
 */
fun keyAfter(
    ending: PairingState.Ended,
    reachability: Reachability,
): OnboardingKey =
    when (ending) {
        is PairingState.CannotReach -> OnboardingKey.Failure(cannotReachCase(reachability))
        is PairingState.InvalidLink, PairingState.Cancelled -> OnboardingKey.Scan
        is PairingState.ProtocolError -> OnboardingKey.Failure(FailureCase.PROTOCOL_ERROR)
        is PairingState.NoSecureHardware -> OnboardingKey.Failure(FailureCase.NO_SECURE_HARDWARE)
        else -> OnboardingKey.Failure(FAILURE_OF_ENDING.getValue(ending))
    }

private fun cannotReachCase(reachability: Reachability): FailureCase =
    when (reachability) {
        Reachability.EXCLUDED_FROM_TAILSCALE -> FailureCase.EXCLUDED_FROM_TAILSCALE
        Reachability.VPN_HOLDS_THE_SLOT -> FailureCase.VPN_HOLDS_THE_SLOT
        else -> FailureCase.CANT_REACH
    }

/** Where a failure screen's button leads. */
sealed interface FailureStep {
    /** Onboarding shows [key]. */
    data class Go(
        val key: OnboardingKey,
    ) : FailureStep

    /** "Try again" after "Can't reach": the same link, a new attempt (PairingHandle.retry). */
    data object Retry : FailureStep

    /** Something outside the app: a page, the Tailscale app, the VPN settings, or the pasted link. */
    data object Outside : FailureStep
}

/**
 * What [action] on [case]'s screen does. "Scan again", and "Try again" where only a new code helps, go
 * back to the scan; "Start over" goes back to Pair; "OK" goes back to Pair, or to Welcome when the phone
 * cannot hold a key at all. Only "Can't reach" retries the link it holds.
 */
fun stepAfter(
    case: FailureCase,
    action: FailureAction,
): FailureStep {
    require(action == case.primary || action in case.secondaries) { "$case's screen has no $action" }
    return when (action) {
        FailureAction.SCAN_AGAIN -> FailureStep.Go(OnboardingKey.Scan)
        FailureAction.START_OVER -> FailureStep.Go(OnboardingKey.Pair)
        FailureAction.TRY_AGAIN -> afterTryAgain(case)
        FailureAction.OK -> FailureStep.Go(afterOk(case))
        else -> FailureStep.Outside
    }
}

private fun afterTryAgain(case: FailureCase): FailureStep =
    if (case == FailureCase.CANT_REACH) FailureStep.Retry else FailureStep.Go(OnboardingKey.Scan)

private fun afterOk(case: FailureCase): OnboardingKey =
    if (case == FailureCase.NO_SECURE_HARDWARE) OnboardingKey.Welcome else OnboardingKey.Pair

/** The Connecting screen's one line (design section 13.3, step 4). */
enum class ConnectingPhase { REACHING, TRYING_TAILSCALE, CHECKING, SECURING }

/** How long Reaching lasts before its line becomes "Trying Tailscale…" (section 13.3, step 4). */
const val TRYING_TAILSCALE_AFTER_MILLIS = 4_000L

/**
 * The line [state] shows. Reaching becomes "Trying Tailscale…" once it has lasted
 * [TRYING_TAILSCALE_AFTER_MILLIS] ([reachingLong]) and a tailnet candidate is among those tried: the
 * design asks for the line after 4 s, and a link with no tailnet candidate never tries Tailscale.
 */
fun connectingPhase(
    state: PairingState,
    reachingLong: Boolean,
): ConnectingPhase =
    when (state) {
        PairingState.Validating -> ConnectingPhase.REACHING
        is PairingState.Reaching -> reachingPhase(state, reachingLong)
        PairingState.Checking -> ConnectingPhase.CHECKING
        PairingState.Securing -> ConnectingPhase.SECURING
        else -> error("$state is past the Connecting screen")
    }

private fun reachingPhase(
    state: PairingState.Reaching,
    reachingLong: Boolean,
): ConnectingPhase {
    val triesTailnet = state.tried.any { it.scope == Candidate.Scope.TAILNET }
    return if (reachingLong && triesTailnet) ConnectingPhase.TRYING_TAILSCALE else ConnectingPhase.REACHING
}
