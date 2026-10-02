package io.tezra.fermix.onboarding

import androidx.compose.runtime.Composable
import io.tezra.fermix.design.FermixPreviewTheme
import io.tezra.fermix.design.FermixPreviews

// Every failure screen, the fourteen of design section 13.3's table and the protocol error, at the twelve
// windows of @FermixPreviews with the host suj-mbp. One preview a case, so that each image is named for its
// case and keeps it whatever FailureCase's order.

@Composable
private fun Failure(case: FailureCase) {
    FermixPreviewTheme { FailureScreen(case = case, host = HOST, onAction = {}) }
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
