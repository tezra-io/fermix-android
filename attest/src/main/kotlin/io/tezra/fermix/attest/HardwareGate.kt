package io.tezra.fermix.attest

import android.content.pm.PackageManager
import android.os.Build

/** Android 15, the floor (design D18). */
private const val FLOOR_SDK = 35

/** `FEATURE_HARDWARE_KEYSTORE` from this version on: hardware Curve25519 (design section 6.1). */
private const val KEYSTORE_CURVE25519_VERSION = 200

/** What the hardware gate found. */
sealed interface GateResult {
    /** The phone can hold a Fermix key. */
    data object Ok : GateResult

    /** The phone runs [sdkInt], below Android 15. */
    data class SdkBelowFloor(
        val sdkInt: Int,
    ) : GateResult

    /** The phone's Keystore reports `FEATURE_HARDWARE_KEYSTORE` below version 200: no hardware Curve25519. */
    data object NoHardwareCurve25519 : GateResult
}

/**
 * Design section 6.1's gate, run when the owner taps "Get started" (section 13.3, step 1): `minSdk 35` and
 * `hasSystemFeature(FEATURE_HARDWARE_KEYSTORE, 200)`. A phone that fails it cannot pair, and there is no
 * software key to fall back to (D2); the screen says why in one sentence.
 */
object HardwareGate {
    fun check(packageManager: PackageManager): GateResult =
        decide(
            Build.VERSION.SDK_INT,
            packageManager.hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE, KEYSTORE_CURVE25519_VERSION),
        )

    /** The gate's rule over the two facts [check] reads; the floor is checked first. */
    internal fun decide(
        sdkInt: Int,
        keystoreCurve25519: Boolean,
    ): GateResult =
        when {
            sdkInt < FLOOR_SDK -> GateResult.SdkBelowFloor(sdkInt)
            !keystoreCurve25519 -> GateResult.NoHardwareCurve25519
            else -> GateResult.Ok
        }
}
