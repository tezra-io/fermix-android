package io.tezra.fermix.session

import io.tezra.fermix.attest.AliasNames
import io.tezra.fermix.attest.AttestationChallenge
import io.tezra.fermix.attest.AttestedKey
import io.tezra.fermix.attest.Chain
import io.tezra.fermix.attest.ChainShapeException
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.noise.NoiseException
import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.protocol.Attestation
import io.tezra.fermix.protocol.AttestationKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.protocol.Platform
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.CandidateRacer
import io.tezra.fermix.transport.RaceResult
import io.tezra.fermix.transport.TransportException
import io.tezra.fermix.transport.candidateOrder
import io.tezra.fermix.transport.linkCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

/** A link of this version is an older Fermix's (design D1): the app pairs with version 2 alone. */
private const val OLDER_LINK_VERSION = 1

/** Ends a ceremony as [state], from whichever step finds it over. */
internal class CeremonyEnded(
    val state: PairingState.Ended,
) : Exception(state.toString())

/**
 * One run of the pairing ceremony (design section 6.3; PROTOCOL.md "Noise modes and pairing"), in order:
 * the link checked on the phone, a version-1 link refused before any key exists; a new key alias,
 * generated with the attestation challenge, its chain's shape checked; the race with IKpsk2, the secret
 * zeroed once the handshake consumed it; `pair_request` at seq 1 with the chain as its raw tail; the SAS;
 * the owner's decision, with the keepalive; and on `pair_approved` the facts and the same connection
 * handed to a paired session. Whatever else it ends in, it closes the socket and deletes the attempt's
 * key, and a pairing the phone held before is never touched here: its alias reaches only the handle's
 * commit, as the store's report of the record its write replaced. The ending is published once that is
 * done, or as a Keystore fault on the way throws.
 */
internal class Ceremony(
    private val link: PairingLink,
    private val keys: DeviceKeyFacade,
    private val identity: PhoneIdentity,
    private val parts: PairingParts,
    private val board: PairingBoard,
) {
    /**
     * The attempt's alias, once generate made a key under it: never before, so no other key is deleted, and
     * always before a cancel can end the ceremony, so release deletes every key it made.
     */
    private var alias: String? = null
    private var won: Handshaken? = null

    /** The ceremony to its end, which it publishes and returns; the session it hands over runs in [sessionScope]. */
    suspend fun run(sessionScope: CoroutineScope): PairingState {
        var outcome: PairingState? = null
        try {
            outcome =
                try {
                    steps(sessionScope)
                } catch (ended: CeremonyEnded) {
                    ended.state
                }
            return outcome
        } finally {
            releaseThenPublish(outcome)
        }
    }

    /**
     * The steps in order. After `pair_approved` the same connection goes on as the session, `hello` at its
     * next seq (PROTOCOL.md "Noise modes and pairing", step 3, and the engine's socket handler, which
     * awaits `hello` on that socket). Design section 6.3's diagram and onboarding section 3, step 4, say
     * instead that the phone switches `key_alias`, deletes the old alias, reconnects with `FXM1·01` and
     * then sends `hello`; the wire contract wins, and the docs' order is the owner's to correct. The old
     * alias is deleted at the handle's commit, once the record is stored, as design section 6.1 requires.
     */
    private suspend fun steps(sessionScope: CoroutineScope): PairingState.Approved {
        linkRefusal(link)?.let { throw CeremonyEnded(it) }
        val candidates = link.candidates.map { checkNotNull(linkCandidate(it)) }
        val dialer = parts.dialerFor(link.port, link.tlsFingerprint.copyOf())
        val attempt = AliasNames.next(link.gatewayPublicKey, parts.random)
        val (attested, staticKey) = deviceKey(attempt)
        val race = reach(candidates, staticKey, dialer)
        val channel = request(race.value, attested)
        val approval = awaitDecision(channel, parts.clock)
        if (approval.profiles.none { it.id == parts.profileId }) {
            throw CeremonyEnded(PairingState.ProtocolError("pair_approved lists no profile ${parts.profileId}"))
        }
        val routes = routesOf(approval.candidates).ifEmpty { candidates }
        val facts = factsOf(approval, routes, attempt)
        val instance = PairedInstance(approval.deviceId, parts.profileId, link.gatewayPublicKey)
        val adopted = Adopted(race.candidate, race.value, channel)
        val session = Session.adopt(instance, routes, sessionParts(staticKey, dialer), sessionScope, adopted)
        won = null
        return PairingState.Approved(facts, session)
    }

    private fun sessionParts(
        staticKey: StaticKey,
        dialer: Dialer,
    ): SessionParts =
        SessionParts(
            identity.appVersion,
            staticKey,
            dialer,
            parts.store,
            parts.announcer,
            parts.network,
            parts.clock,
            parts.random,
        )

    /**
     * The attempt's key, generated under [attempt] with the challenge of the link's secret, and the key the
     * handshake runs, whose public bytes the chain's leaf must carry. An alias the Keystore holds already is
     * another attempt's key and fails loud, untouched (design section 6.1: a stray scan never destroys a
     * working key). A Keystore or provider fault, or a chain the daemon would refuse for its shape, is
     * [PairingState.NoSecureHardware], before any socket opens; anything else is a bug and propagates.
     */
    private suspend fun deviceKey(attempt: String): Pair<AttestedKey, StaticKey> {
        val challenge = AttestationChallenge.of(link.secret)
        check(!keystore { keys.exists(attempt) }) { "the Keystore holds $attempt already; an attempt's alias is new" }
        // A key the Keystore made is recorded before a cancel lands, then the cancel ends the ceremony.
        val attested = withContext(NonCancellable) { keystore { keys.generate(attempt, challenge) } }
        alias = attempt
        currentCoroutineContext().ensureActive()
        val staticKey = keystore { keys.staticKey(attempt) }
        try {
            Chain.validateShape(attested.chain(), staticKey.publicKey)
        } catch (refused: ChainShapeException) {
            throw CeremonyEnded(PairingState.NoSecureHardware(refused))
        }
        return attested to staticKey
    }

    /** [call], a blocking Keystore operation, on the Keystore's dispatcher; its faults are "No secure hardware". */
    private suspend fun <T> keystore(call: () -> T): T =
        try {
            withContext(parts.keystore) { call() }
        } catch (fault: GeneralSecurityException) {
            throw CeremonyEnded(PairingState.NoSecureHardware(fault))
        } catch (fault: ProviderException) {
            throw CeremonyEnded(PairingState.NoSecureHardware(fault))
        }

    /**
     * The race (design section 5.1) with IKpsk2 to the link's key over [linkDialer], the link's port and
     * pin; the winner is this ceremony's to close until it is handed on, and the link's secret is zeroed
     * once the race is over, since every handshake took its copy then.
     */
    private suspend fun reach(
        candidates: List<Candidate>,
        staticKey: StaticKey,
        linkDialer: Dialer,
    ): RaceResult.Won<Handshaken> {
        val started = parts.clock.markNow()
        board.publish(PairingState.Reaching(emptyList(), 0))
        val dialer = Dialer { candidate -> linkDialer.dial(candidate).also { board.checking() } }
        val gatewayKey = link.gatewayPublicKey
        val race =
            CandidateRacer.race(candidateOrder(candidates)) { candidate ->
                board.reaching(candidate, started.elapsedNow().inWholeMilliseconds)
                pairingHandshake(dialer, candidate, staticKey, gatewayKey, link.secret)
            }
        return when (race) {
            is RaceResult.Won -> race.also { won = it.value }.also { link.secret.fill(0) }
            is RaceResult.PinMismatch -> throw CeremonyEnded(PairingState.WrongMachine)
            is RaceResult.AllFailed -> throw CeremonyEnded(unreached(race.failures))
        }
    }

    /**
     * `pair_request` at seq 1 with the chain as its raw tail, within the handshake deadline; then Verify.
     * Protocol v2 shapes, design section 7's `pair_request.platform` row (`android`, required) and its
     * `pair_request.attestation` row (`{kind, cert_lengths}`, the DER chain leaf first as the raw tail).
     * The name is taken here, as the handshake completes, so a rename on the Verify screen comes too late;
     * section 13.3 step 5 and the visual canon put it there, and how to reconcile that with PROTOCOL.md's
     * 10 s for `pair_request` is the owner's decision.
     */
    private fun request(
        won: Handshaken,
        attested: AttestedKey,
    ): SecureChannel {
        board.publish(PairingState.Securing)
        val name = board.takeName()
        val attestation = Attestation(AttestationKind.ANDROID_KEYMINT, attested.certLengths)
        val request = ClientEvent.PairRequest(name, identity.model, identity.appVersion, Platform.ANDROID, attestation)
        val channel = SecureChannel(won.link, won.noise)
        channel.send(request, attested.tail())
        val expiresAt = parts.clock.markNow() + PAIRING_WINDOW_MS.milliseconds
        board.publish(PairingState.Verify(won.noise.sas, expiresAt, name))
        return channel
    }

    /**
     * The instance record's facts. Protocol v2 shapes: design section 7's `pair_approved.push_salt` row,
     * the salt the push topic is derived with, and its `pair_approved.push` row, the push platforms the
     * daemon can send through.
     */
    private fun factsOf(
        approval: ServerEvent.PairApproved,
        routes: List<Candidate>,
        attempt: String,
    ): InstanceFacts =
        InstanceFacts(
            gatewayPk = Base64.getEncoder().encodeToString(link.gatewayPublicKey),
            tlsFp = link.tlsFingerprint.toHexString(),
            host = link.name,
            profile = checkNotNull(link.profile) { "a version-2 link names its profile" },
            label = link.name,
            candidates = routes,
            port = link.port,
            deviceId = approval.deviceId,
            keyAlias = attempt,
            pushSalt = checkNotNull(approval.pushSalt) { "a protocol v2 pair_approved carries push_salt" },
            pushPlatforms = approval.push.orEmpty(),
        )

    /** Releases what [outcome] leaves, and publishes [outcome] even when the Keystore fails on the way. */
    private suspend fun releaseThenPublish(outcome: PairingState?) {
        try {
            release(outcome)
        } finally {
            outcome?.let(board::publish)
        }
    }

    /**
     * Whatever the ceremony leaves but an approval: the link's secret zeroed first, unless the race never
     * reached the daemon, which leaves the link for a retry; the socket closed, `1002` after a protocol
     * error; and the attempt's key deleted, also when the ceremony was cancelled.
     */
    private suspend fun release(outcome: PairingState?) {
        if (outcome is PairingState.Approved) return
        if (outcome !is PairingState.CannotReach) link.secret.fill(0)
        won?.let { open ->
            if (outcome is PairingState.ProtocolError) open.link.close(PROTOCOL_ERROR, "mobile protocol error")
            open.close()
        }
        won = null
        val attempt = alias ?: return
        alias = null
        // Nested: NonCancellable beside the dispatcher would still throw, on the way back to a cancelled ceremony.
        withContext(NonCancellable) { withContext(parts.keystore) { keys.delete(attempt) } }
    }
}

/**
 * What the link's check on the phone refuses (design section 13.3, step 3): a version-1 link, an older
 * Fermix's, and a link without a candidate or with one outside the LAN and tailnet ranges.
 */
internal fun linkRefusal(link: PairingLink): PairingState.Ended? =
    when {
        link.version == OLDER_LINK_VERSION -> {
            PairingState.OlderFermix
        }

        link.candidates.isEmpty() -> {
            PairingState.InvalidLink("the link names no candidate")
        }

        else -> {
            link.candidates
                .firstOrNull { linkCandidate(it) == null }
                ?.let { PairingState.InvalidLink("candidate $it is outside the LAN and tailnet ranges") }
        }
    }

/**
 * A race every candidate lost. A daemon that closed the handshake `1002` refused the pairing handshake,
 * as it does with no window open, or with this address past five failures; one whose message 2 did not
 * authenticate holds another secret, a newer window's. Either is the link expired; anything else is the
 * daemon out of reach.
 */
internal fun unreached(failures: Map<Candidate, Exception>): PairingState.Ended {
    val refused =
        failures.values.any {
            (it is TransportException.Closed && it.byDaemon && it.code == PROTOCOL_ERROR) ||
                it is NoiseException.AuthenticationFailed
        }
    return if (refused) PairingState.Expired else PairingState.CannotReach(failures)
}
