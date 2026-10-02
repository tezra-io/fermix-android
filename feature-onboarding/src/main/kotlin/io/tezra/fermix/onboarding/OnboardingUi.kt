package io.tezra.fermix.onboarding

import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.requirePairRequestText
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeMark

/**
 * What onboarding runs on, all of it the app's: the hardware gate of design section 6.1 ([gate], attest's
 * HardwareGate on the phone), the ceremony's starter, this phone's identity with the name its owner gave
 * it, the instance records, the network facts behind section 5.2's failures, the dispatcher a ceremony
 * runs on (never the main thread), and [pairingWait], where onboarding says what the pairing-wait
 * notification of section 12.5 shows while Verify waits.
 */
data class OnboardingParts(
    val gate: () -> GateResult,
    val pairings: PairingStarter,
    val identity: PhoneIdentity,
    val instances: InstanceStore,
    val network: StateFlow<NetworkFacts>,
    val pairingDispatcher: CoroutineDispatcher,
    val pairingWait: MutableStateFlow<PairingWait?>,
)

/**
 * The owner's decision awaited on [host] until [expiresAt]: section 13.9's "Waiting for approval on {host} ·
 * {m:ss}".
 */
data class PairingWait(
    val host: String,
    val expiresAt: TimeMark,
)

/** Verify's facts (design section 13.3, step 5): the code, its end, and the name `pair_request` carried. */
data class VerifyFacts(
    val sas: String,
    val expiresAt: TimeMark,
    val deviceName: String,
) {
    /** Leaves the SAS out, so no log line that prints a state carries the code. */
    override fun toString(): String = "VerifyFacts(expiresAt=$expiresAt, deviceName=$deviceName)"
}

/**
 * An approved pairing as stored: [record], the other Fermixes on this phone ([others]), whether section
 * 9.2 asks for a name, and whether step 7 offers notifications.
 */
data class PairedFacts(
    val record: Instance,
    val others: List<Instance>,
    val needsName: Boolean,
    val offerNotifications: Boolean,
)

/**
 * What the onboarding screens show: the phone's name, the host the link names, why a link was refused at
 * the scan, the Connecting line, Verify's facts, and the pairing approved.
 */
data class OnboardingUi(
    val deviceName: String,
    val host: String = "",
    val scanRefusal: String? = null,
    val connecting: ConnectingPhase = ConnectingPhase.REACHING,
    val verify: VerifyFacts? = null,
    val paired: PairedFacts? = null,
)

/**
 * Why `pair_request` cannot carry [deviceName] as the phone's name, by core-protocol's own rule, or null
 * when it can: the rename sheet's check before it offers "Rename".
 */
fun deviceNameRefusal(deviceName: String): String? =
    try {
        requirePairRequestText("device_name", deviceName)
        null
    } catch (refused: ProtocolException.InvalidField) {
        refused.reason
    }
