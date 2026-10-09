package io.tezra.fermix.onboarding

import androidx.compose.runtime.Composable
import io.tezra.fermix.design.FailureEntrance
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews

// Every failure screen, the fourteen of design section 13.3's table and the protocol error, at the twelve
// windows of @FermixPreviews with the host suj-mbp. One preview a case, so that each image is named for its
// case and keeps it whatever FailureCase's order. Each is drawn at one instant, its entrance over, never on a clock.

/** 20 ms into the edge's 300, where emphasized decelerate has drawn about half of it. */
private const val WRONG_MACHINE_EDGE_HALF_MILLIS = 120f

/** [case]'s screen at [ms] on its entrance's clock, by default with the entrance over. */
@Composable
private fun Failure(
    case: FailureCase,
    ms: Float = FailureEntrance.CLOCK_MILLIS.toFloat(),
) {
    FermixPreviewTheme { FailureAt(case = case, host = HOST, onAction = {}, entrance = { ms }) }
}

@FermixPreviews
@Composable
fun FailureExpiredPreview() = Failure(FailureCase.EXPIRED)

@FermixPreviews
@Composable
fun FailureDeniedPreview() = Failure(FailureCase.DENIED)

@FermixPreviews
@Composable
fun FailureRateLimitedPreview() = Failure(FailureCase.RATE_LIMITED)

@FermixPreviews
@Composable
fun FailureAnotherPairingPreview() = Failure(FailureCase.ANOTHER_PAIRING)

@FermixPreviews
@Composable
fun FailureCantReachPreview() = Failure(FailureCase.CANT_REACH)

@FermixPreviews
@Composable
fun FailureExcludedFromTailscalePreview() = Failure(FailureCase.EXCLUDED_FROM_TAILSCALE)

@FermixPreviews
@Composable
fun FailureVpnHoldsTheSlotPreview() = Failure(FailureCase.VPN_HOLDS_THE_SLOT)

@FermixPreviews
@Composable
fun FailureWrongMachinePreview() = Failure(FailureCase.WRONG_MACHINE)

/** The security event's red edge about half drawn from the start side, 120 ms in (the M51 update's 7.4). */
@FermixPreviews
@Composable
fun FailureWrongMachineEdgePreview() = Failure(FailureCase.WRONG_MACHINE, ms = WRONG_MACHINE_EDGE_HALF_MILLIS)

@FermixPreviews
@Composable
fun FailureOlderFermixPreview() = Failure(FailureCase.OLDER_FERMIX)

@FermixPreviews
@Composable
fun FailureNewerFermixPreview() = Failure(FailureCase.NEWER_FERMIX)

@FermixPreviews
@Composable
fun FailureAttestationRefusedPreview() = Failure(FailureCase.ATTESTATION_REFUSED)

@FermixPreviews
@Composable
fun FailureAttestationUnavailablePreview() = Failure(FailureCase.ATTESTATION_UNAVAILABLE)

@FermixPreviews
@Composable
fun FailureNoSecureHardwarePreview() = Failure(FailureCase.NO_SECURE_HARDWARE)

@FermixPreviews
@Composable
fun FailureLostMidWaitPreview() = Failure(FailureCase.LOST_MID_WAIT)

@FermixPreviews
@Composable
fun FailureProtocolErrorPreview() = Failure(FailureCase.PROTOCOL_ERROR)
