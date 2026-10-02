package io.tezra.fermix.attest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Design section 6.1's gate, `minSdk 35` and `FEATURE_HARDWARE_KEYSTORE` at version 200, as the rule it
 * reads the phone's two facts by; reading them is the device gate's.
 */
class HardwareGateTest {
    @Test
    fun `Android 15 with hardware Curve25519 passes`() {
        assertEquals(GateResult.Ok, HardwareGate.decide(sdkInt = 35, keystoreCurve25519 = true))
        assertEquals(GateResult.Ok, HardwareGate.decide(sdkInt = 37, keystoreCurve25519 = true))
    }

    @Test
    fun `a keystore below version 200 is refused, naming that fact`() {
        assertEquals(GateResult.NoHardwareCurve25519, HardwareGate.decide(sdkInt = 36, keystoreCurve25519 = false))
    }

    @Test
    fun `a release below the floor is refused first, naming it`() {
        assertEquals(GateResult.SdkBelowFloor(34), HardwareGate.decide(sdkInt = 34, keystoreCurve25519 = true))
        assertEquals(GateResult.SdkBelowFloor(33), HardwareGate.decide(sdkInt = 33, keystoreCurve25519 = false))
    }
}
