package io.tezra.fermix.onboarding

import io.tezra.fermix.attest.GateResult
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.protocol.ProtocolException
import io.tezra.fermix.protocol.requirePairRequestText
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.session.Session
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeMark

/**
 * What onboarding runs on, all of it the app's: the hardware gate of design section 6.1 ([gate], attest's
 * HardwareGate on the phone), the ceremony's starter, this phone's identity with the name its owner gave
 * it, the instance records, the network facts behind section 5.2's failures, the dispatcher a ceremony
 * runs on (never the main thread), [pairingWait], where onboarding says what the pairing-wait
 * notification of section 12.5 shows while Verify waits, [handover], which takes the approved pairing's
 * session as the record is stored, [notifications], which acts on step 7's answer once the record holds it
 * (the channel and `push_register`, or `push_unregister`, design section 10), and [now], the wall clock a
 * record's "Paired since" is read from.
 */
data class OnboardingParts(
    val gate: () -> GateResult,
    val pairings: PairingStarter,
    val identity: PhoneIdentity,
    val instances: InstanceStore,
    val network: StateFlow<NetworkFacts>,
    val pairingDispatcher: CoroutineDispatcher,
    val pairingWait: MutableStateFlow<PairingWait?>,
    val handover: SessionHandover,
    val notifications: suspend (record: Instance, on: Boolean) -> Unit,
    val now: () -> Long = System::currentTimeMillis,
)

/**
 * Whoever keeps the instances' sessions, the app's, taking the one an approval opened: [adopt] runs
 * [record], which stores the instance's record and returns the key alias it replaced, and keeps [session]
 * as [instanceId]'s, in one step, so the keeper never sees the record without the session and opens a
 * second socket to the daemon (design section 6.3: the paired session is the instance's first).
 */
fun interface SessionHandover {
    suspend fun adopt(
        instanceId: String,
        session: Session,
        record: suspend () -> String?,
    ): String?
}

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
 * the scan, whether the scan has found a Fermix code ([scanFound], until it leaves the top), the scan's
 * visit ([scanVisit], one more each time it leaves the top, so that each time it comes back its reticle
 * settles in anew), the Connecting line, Verify's facts, and the pairing approved; [mergeInto], the row
 * whose "Pair again" started this pairing (design section 9.2), which mergeTarget weighs on approval; and
 * [alreadyPaired], the title of the row a scanned or pasted link's daemon is paired as already, while
 * section 9.2's question about it waits for the owner's answer.
 */
data class OnboardingUi(
    val deviceName: String,
    val host: String = "",
    val scanRefusal: String? = null,
    val scanFound: Boolean = false,
    val scanVisit: Int = 0,
    val connecting: ConnectingPhase = ConnectingPhase.REACHING,
    val verify: VerifyFacts? = null,
    val paired: PairedFacts? = null,
    val mergeInto: String? = null,
    val alreadyPaired: String? = null,
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
