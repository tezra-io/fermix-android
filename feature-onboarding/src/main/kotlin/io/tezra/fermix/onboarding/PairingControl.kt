package io.tezra.fermix.onboarding

import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.session.InstanceFacts
import io.tezra.fermix.session.Pairing
import io.tezra.fermix.session.PairingHandle
import io.tezra.fermix.session.PairingParts
import io.tezra.fermix.session.PairingState
import io.tezra.fermix.session.PhoneIdentity
import io.tezra.fermix.session.Retry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * One pairing ceremony as onboarding drives it: core-session's [PairingHandle] on the phone, and in the
 * screens' tests a fake whose state the test sets. Each call is the handle's, with its rules. The
 * handle's rename is not here: the phone's name is chosen on Pair, before any ceremony starts, and goes
 * into the PhoneIdentity the ceremony starts with.
 */
interface PairingControl {
    val state: StateFlow<PairingState>

    suspend fun retry(): Retry

    suspend fun cancel(): Boolean

    suspend fun commit(store: suspend (InstanceFacts) -> String?)
}

/** Starts a ceremony over a link the owner scanned or pasted, in a scope onboarding ends when it leaves it. */
fun interface PairingStarter {
    fun start(
        link: PairingLink,
        identity: PhoneIdentity,
        scope: CoroutineScope,
    ): PairingControl
}

/**
 * The phone's starter: [Pairing.start] with the device keys the app holds, attest's DeviceKeys, and the
 * parts the app makes for a link, its dialer, store and announcer among them (design section 6.3).
 */
fun handleStarter(
    keys: DeviceKeyFacade,
    partsFor: (PairingLink) -> PairingParts,
): PairingStarter =
    PairingStarter { link, identity, scope ->
        HandleControl(Pairing.start(link, keys, identity, partsFor(link), scope))
    }

private class HandleControl(
    private val handle: PairingHandle,
) : PairingControl {
    override val state: StateFlow<PairingState> get() = handle.state

    override suspend fun retry(): Retry = handle.retry()

    override suspend fun cancel(): Boolean = handle.cancel()

    override suspend fun commit(store: suspend (InstanceFacts) -> String?) = handle.commit(store)
}
