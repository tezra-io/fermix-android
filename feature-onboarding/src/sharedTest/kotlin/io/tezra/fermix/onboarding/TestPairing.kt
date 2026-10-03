package io.tezra.fermix.onboarding

import android.content.ContextWrapper
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.instanceDataStore
import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.session.Announcement
import io.tezra.fermix.session.Announcer
import io.tezra.fermix.session.Dialer
import io.tezra.fermix.session.InstanceFacts
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.PairedInstance
import io.tezra.fermix.session.PairingState
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.Retry
import io.tezra.fermix.session.RowEdits
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionParts
import io.tezra.fermix.session.SessionStore
import io.tezra.fermix.session.StoredCursors
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import java.util.HexFormat

internal const val HOST = "suj-mbp"
internal const val PHONE = "Pixel 9 Pro"

/** When the tests' pairings are approved: 27 September 2026, 09:41 UTC. */
internal const val PAIRED_AT = 1_790_502_060_000L
internal val TAILNET = Candidate("100.101.102.103", Candidate.Scope.TAILNET, Candidate.Kind.IP)
internal val LAN = Candidate("192.168.1.20", Candidate.Scope.LAN, Candidate.Kind.IP)

private const val KEY_BYTES = 32
private const val PORT = 4031

/** The byte every byte of the push salt is. */
private const val PUSH_SALT_FILL = 0x73

private fun key(fill: Int): ByteArray = ByteArray(KEY_BYTES) { fill.toByte() }

private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** A version-2 link to [HOST] as the daemon writes it, its gateway key every byte [gateway]. */
internal fun linkText(gateway: Int = 1): String {
    val parameters =
        listOf(
            "v" to "2",
            "candidates" to "[\"${TAILNET.host}\",\"${LAN.host}\"]",
            "port" to "$PORT",
            "tls_fp" to HexFormat.of().formatHex(key(gateway + 1)),
            "gateway_pk" to base64(key(gateway)),
            "secret" to base64(ByteArray(KEY_BYTES) { (it + 1).toByte() }),
            "name" to HOST,
            "profile" to "fermix",
        )
    return "fermix://pair?" +
        parameters.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
}

/** What an approved pairing with the daemon whose gateway key is every byte [gateway] reports. */
internal fun facts(
    gateway: Int = 1,
    profile: String = "fermix",
    push: List<PushPlatform> = listOf(PushPlatform.FCM),
): InstanceFacts =
    InstanceFacts(
        gatewayPk = base64(key(gateway)),
        tlsFp = HexFormat.of().formatHex(key(gateway + 1)),
        host = HOST,
        profile = profile,
        label = HOST,
        candidates = listOf(TAILNET),
        port = PORT,
        deviceId = "device-$gateway",
        keyAlias = "fermix.device.$gateway.0102030405060708",
        pushSalt = base64(key(PUSH_SALT_FILL)),
        pushPlatforms = push,
    )

/** A stored record, as a pairing with [gateway]'s daemon leaves it. */
internal fun record(
    gateway: Int,
    tint: String = "Slate",
    nickname: String? = null,
): Instance = instanceOf(facts(gateway), tint, PHONE, PAIRED_AT).copy(nickname = nickname)

/** The Context ProfileDatabases holds and never asks anything of until a database opens, which these tests never do. */
private object NoContext : ContextWrapper(null)

/** The instance records in a DataStore under [directory], its writes in [scope]. */
internal fun instanceStore(
    directory: File,
    scope: CoroutineScope,
): InstanceStore =
    InstanceStore(
        instanceDataStore(File(directory, "instances.json"), scope),
        ProfileDatabases(NoContext, File(directory, "instances")),
    )

/**
 * A ceremony whose state the test sets: what it was asked, and what the handle would answer. Its commit
 * stores [PairingState.Approved]'s facts through the caller's store, as the handle's does.
 */
internal class FakeControl : PairingControl {
    override val state = MutableStateFlow<PairingState>(PairingState.Validating)
    var retryAnswer = Retry.RETRYING
    var retries = 0
    var cancels = 0
    val replaced = mutableListOf<String?>()

    override suspend fun retry(): Retry {
        retries++
        return retryAnswer
    }

    override suspend fun cancel(): Boolean {
        cancels++
        return true
    }

    override suspend fun commit(store: suspend (InstanceFacts) -> String?) {
        val approved = state.value as PairingState.Approved
        replaced += store(approved.facts)
    }
}

/**
 * The keeper of sessions the tests hand onboarding: it stores the record, then keeps the session by instance.
 * The approved record must come into [records] inside the handover, new or in place of the one a pairing
 * replaces: a record stored before it would let the app's keeper open a second session for the instance
 * (design section 6.3).
 */
internal class FakeHandover(
    private val records: Flow<List<Instance>>,
) : SessionHandover {
    val adopted = mutableListOf<Pair<String, Session>>()

    override suspend fun adopt(
        instanceId: String,
        session: Session,
        record: suspend () -> String?,
    ): String? {
        val before = records.first().find { it.id == instanceId }
        val replaced = record()
        val after = records.first().find { it.id == instanceId }
        check(after != null && after != before) { "$instanceId's record was not stored inside the handover" }
        adopted += instanceId to session
        return replaced
    }
}

/** The starter the tests hand onboarding: a [FakeControl] for each link, kept with the identity it began with. */
internal class FakeStarter : PairingStarter {
    val started = mutableListOf<Pair<FakeControl, String>>()

    val control: FakeControl get() = started.last().first

    override fun start(
        link: io.tezra.fermix.protocol.PairingLink,
        identity: io.tezra.fermix.session.PhoneIdentity,
        scope: CoroutineScope,
    ): PairingControl {
        val control = FakeControl()
        started += control to identity.deviceName
        return control
    }
}

/** The primary clip as the tests set it, [held], with every read and clear it was asked for, in order. */
internal class FakeClip(
    var held: String?,
) : PrimaryClip {
    val calls = mutableListOf<String>()

    override fun text(): String? {
        calls += "text"
        return held
    }

    override fun clear() {
        calls += "clear"
        held = null
    }
}

/** A paired session that dials forever: what Approved hands over, never asked anything here. */
internal fun idleSession(scope: CoroutineScope): Session =
    Session.open(
        PairedInstance("device-1", "main", key(1)),
        listOf(TAILNET),
        SessionParts(
            appVersion = "0.1.0",
            staticKey = NoKey,
            dialer = Dialer { awaitCancellation() },
            store = NoStore,
            announcer = Announcer { Announcement.NOT_ANNOUNCED },
            network = MutableStateFlow(NetworkFacts.NONE) as StateFlow<NetworkFacts>,
        ),
        scope,
    )

private object NoKey : StaticKey {
    override val publicKey: ByteArray get() = key(9)

    override fun agree(peerPublicKey: ByteArray): ByteArray = error("the idle session never handshakes")
}

/** The store of a session that never connects: empty cursors and outbox, and no write. */
private object NoStore : SessionStore, RowEdits by NoRowEdits {
    override suspend fun cursors() = StoredCursors(0uL, 0uL, 0uL, 0uL, 0uL)

    override suspend fun setServerCursor(
        seq: ULong,
        announcedUpToSeq: ULong,
        lastUnannouncedSeq: ULong,
    ) = error("the idle session never acks")

    override suspend fun setReadFrontier(seq: ULong) = error("the idle session never reads")

    override suspend fun applyMutations(
        rows: List<io.tezra.fermix.protocol.MutationRow>,
        lastMutationSeq: ULong,
    ) = error("the idle session never mutates")

    override suspend fun rebuildCache(mutationHeadSeq: ULong) = error("the idle session never rebuilds")

    override suspend fun outbox(): List<OutboxItem> = emptyList()

    override suspend fun enqueue(item: OutboxItem) = error("the idle session never sends")

    override suspend fun dequeue(clientMsgId: String) = error("the idle session never sends")

    override suspend fun markWritten(clientMsgId: String) = error("the idle session never sends")

    override suspend fun withdraw(clientMsgId: String) = error("the idle session never sends")

    override suspend fun markFailed(
        clientMsgId: String,
        failure: RequestFailure,
    ) = error("the idle session never sends")
}

/** The row edits of a session that never connects: none, since nothing reaches it. */
private object NoRowEdits : RowEdits {
    override suspend fun applyReaction(
        clientMsgId: String,
        emoji: String,
    ) = error("the idle session gets no reaction")

    override suspend fun addLinkPreview(
        serverSeq: ULong,
        card: LinkPreviewCard,
    ) = error("the idle session gets no link preview")
}
