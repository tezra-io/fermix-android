package io.tezra.fermix.session

import io.tezra.fermix.attest.DEVICE_KEY_ALIAS_PREFIX
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.protocol.requirePairRequestText
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** What [PairingHandle.rename] did. */
enum class Rename {
    /** `pair_request` will carry the new name. */
    RENAMED,

    /** `pair_request` went out already, under the name [PairingState.Verify] shows. */
    ALREADY_SENT,
}

/** What [PairingHandle.retry] did. */
enum class Retry {
    /** The ceremony runs again from the race, with a new key. */
    RETRYING,

    /** Only [PairingState.CannotReach] is retried: any other ending spent the link or is final. */
    REFUSED,
}

/**
 * One pairing ceremony as the onboarding screens drive it (design section 13.3): its [state], the device
 * name until `pair_request` goes out, a retry after "Can't reach", cancel, and the commit that stores the
 * record. Every call runs on the ceremony's dispatcher, one coroutine at a time, and decides and takes
 * what it acts on in one step there, so no other call finds it half done. An approval and a "Can't
 * reach" are held for the caller until it commits, retries or cancels; the scope's end first abandons
 * them as [cancel] does.
 */
class PairingHandle internal constructor(
    private val link: PairingLink,
    private val keys: DeviceKeyFacade,
    private val identity: PhoneIdentity,
    private val parts: PairingParts,
    private val scope: CoroutineScope,
    private val confined: CoroutineDispatcher,
) {
    private val board = PairingBoard(identity.deviceName)
    private var running: Job? = null

    /** The ending [running] holds for the caller, until the caller takes it on or it is abandoned. */
    private var held: PairingState? = null

    /** Set once [commit] took the approval: neither [cancel] nor the scope's end undoes it from then on. */
    private var committing = false

    /** Set once [cancel] took the ceremony to end it: no [retry] or [commit] takes its ending from then on. */
    private var cancelling = false

    val state: StateFlow<PairingState> = board.state.asStateFlow()

    /**
     * Names the phone [deviceName] in the `pair_request` to come; refused, typed, once it went out, which
     * is as the handshake completes, before Verify shows. A name `pair_request` cannot carry throws the
     * codec's ProtocolException.InvalidField.
     */
    suspend fun rename(deviceName: String): Rename =
        withContext(confined) {
            requirePairRequestText("device_name", deviceName)
            if (board.rename(deviceName)) Rename.RENAMED else Rename.ALREADY_SENT
        }

    /**
     * "Try again" after [PairingState.CannotReach], the one ending that leaves the link unspent, while its
     * ceremony holds it: a new attempt with a new key alias. [PairingState.WrongMachine] above all is
     * never retried, nor a "Can't reach" whose ceremony failed on the way out, which spent the link.
     */
    suspend fun retry(): Retry =
        withContext(confined) {
            if (cancelling || held !is PairingState.CannotReach) return@withContext Retry.REFUSED
            letGo()
            board.publish(PairingState.Validating)
            begin()
            Retry.RETRYING
        }

    /**
     * Ends the ceremony as [PairingState.Cancelled]: the socket closes, the attempt's key is deleted, the
     * link's secret is zeroed, and a pairing this phone held already is untouched. An approval not yet
     * committed is undone too: its session closes and its key is deleted. False when there was nothing to
     * cancel: the ceremony had ended, [commit] took its approval, or another cancel came first.
     */
    suspend fun cancel(): Boolean =
        withContext(confined) {
            val now = board.state.value
            val over = now is PairingState.Ended && now !is PairingState.CannotReach
            if (cancelling || committing || over) return@withContext false
            cancelling = true
            running?.cancelAndJoin()
            link.secret.fill(0)
            board.publish(PairingState.Cancelled)
            true
        }

    /**
     * Takes [PairingState.Approved] on: runs [store], the caller's write of the instance record under the
     * new key alias, which returns the alias of the record it replaced, if one, and once it has returned
     * deletes that key (design section 6.1: never before the record is stored). Once the approval is
     * taken, both run to the end even if the caller is cancelled meanwhile, and no [cancel] lands between
     * them; a caller cancelled before the take takes nothing. A [store] that throws stored nothing: the
     * approval is undone as [cancel] undoes it, and the fault rethrown. Once, and only after an approval.
     */
    suspend fun commit(store: suspend (InstanceFacts) -> String?) {
        val caller = currentCoroutineContext()
        withContext(NonCancellable) {
            val approved = withContext(confined) { takeApproval(caller) }
            var stored = false
            var replaced: String? = null
            try {
                replaced = store(approved.facts)
                stored = true
            } finally {
                if (!stored) undo(approved)
            }
            replaced?.let { old ->
                require(old.startsWith(DEVICE_KEY_ALIAS_PREFIX)) { "the store replaced $old, no device key's alias" }
                require(old != approved.facts.keyAlias) { "the store replaced the pairing's own key, $old" }
                withContext(parts.keystore) { keys.delete(old) }
            }
        }
    }

    /**
     * Starts the ceremony. ATOMIC, so it runs even on a scope that ended before it was dispatched: its own
     * release then zeroes the link's secret and Cancelled shows, where a coroutine that never ran leaves both.
     */
    @OptIn(DelicateCoroutinesApi::class)
    internal fun begin() {
        running = scope.launch(confined, CoroutineStart.ATOMIC) { ceremony() }
    }

    private suspend fun ceremony() {
        var ending: PairingState? = null
        try {
            ending = Ceremony(link, keys, identity, parts, board).run(scope)
        } catch (cancellation: CancellationException) {
            board.publish(PairingState.Cancelled)
            throw cancellation
        } finally {
            // Cancelled, or failed on the way out, the ceremony holds nothing for a retry: its link is spent.
            if (ending == null) link.secret.fill(0)
        }
        if (ending is PairingState.Approved || ending is PairingState.CannotReach) hold(ending)
    }

    /**
     * Holds [ending] for the caller, an approval until [commit] and a "Can't reach" until [retry], and
     * abandons it when [cancel] or the scope's end comes first.
     */
    private suspend fun hold(ending: PairingState) {
        held = ending
        try {
            awaitCancellation()
        } finally {
            if (held === ending) withContext(NonCancellable) { abandon(ending) }
        }
    }

    /** An ending no caller took on: the link's secret zeroed, an approval undone, and the pairing Cancelled. */
    private suspend fun abandon(ending: PairingState) {
        held = null
        link.secret.fill(0)
        if (ending is PairingState.Approved) undo(ending) else board.publish(PairingState.Cancelled)
    }

    /** Undoes [approved]: its session closed and its key deleted; Cancelled even if the Keystore fails. */
    private suspend fun undo(approved: PairingState.Approved) {
        try {
            approved.session.close()
            withContext(parts.keystore) { keys.delete(approved.facts.keyAlias) }
        } finally {
            board.publish(PairingState.Cancelled)
        }
    }

    /**
     * Lets go of the ending held, which a caller took on: its holder ends without abandoning it, later, as
     * nothing waits for it, so the take is one step.
     */
    private fun letGo() {
        held = null
        running?.cancel()
    }

    /** The approval held, taken for [commit], unless [caller] was cancelled first, which leaves it held. */
    private fun takeApproval(caller: CoroutineContext): PairingState.Approved {
        caller.ensureActive()
        val approved = board.state.value as? PairingState.Approved
        check(!cancelling && approved != null && held === approved && !committing) { "only an approval commits, once" }
        committing = true
        letGo()
        return approved
    }
}

/**
 * What a handle and its ceremony share, on the handle's dispatcher: the state, and the device name until
 * `pair_request` takes it.
 */
internal class PairingBoard(
    private var deviceName: String,
) {
    private var nameTaken = false

    val state = MutableStateFlow<PairingState>(PairingState.Validating)

    fun publish(next: PairingState) {
        state.value = next
    }

    /** An attempt at [candidate] started, [elapsedMs] into the race; told only while the race is the news. */
    fun reaching(
        candidate: Candidate,
        elapsedMs: Long,
    ) {
        val reaching = state.value as? PairingState.Reaching ?: return
        state.value = PairingState.Reaching(reaching.tried + candidate, elapsedMs)
    }

    /** A socket opened over its pinned certificate: the handshake checks the daemon's key now. */
    fun checking() {
        if (state.value is PairingState.Reaching) state.value = PairingState.Checking
    }

    /** False once `pair_request` has taken the name. */
    fun rename(name: String): Boolean {
        if (!nameTaken) deviceName = name
        return !nameTaken
    }

    /** The name `pair_request` carries; no rename reaches it after this. */
    fun takeName(): String {
        nameTaken = true
        return deviceName
    }
}
