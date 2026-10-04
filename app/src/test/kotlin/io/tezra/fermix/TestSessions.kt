package io.tezra.fermix

import io.tezra.fermix.chat.ChatClock
import io.tezra.fermix.data.Instance
import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.protocol.LinkPreviewCard
import io.tezra.fermix.protocol.MutationRow
import io.tezra.fermix.session.Announcement
import io.tezra.fermix.session.Announcer
import io.tezra.fermix.session.Dialer
import io.tezra.fermix.session.OutboxItem
import io.tezra.fermix.session.PairedInstance
import io.tezra.fermix.session.RequestFailure
import io.tezra.fermix.session.Session
import io.tezra.fermix.session.SessionEvent
import io.tezra.fermix.session.SessionParts
import io.tezra.fermix.session.SessionStore
import io.tezra.fermix.session.StoredCursors
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.Locale

// What the app's tests of its sessions share: a record of a daemon, and a session that never connects.

private const val KEY_BYTES = 32

private fun base64(fill: Int): String = Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES) { fill.toByte() })

/** A record of the daemon whose gateway key is every byte [gateway]. */
internal fun record(gateway: Int): Instance =
    Instance(
        gatewayPk = base64(gateway),
        tlsFp = "%02x".format(Locale.ROOT, gateway + 1).repeat(KEY_BYTES),
        host = "suj-mbp",
        profile = "fermix",
        label = "suj-mbp",
        tint = "Slate",
        candidates = listOf(Candidate("100.101.102.$gateway", Candidate.Scope.TAILNET, Candidate.Kind.IP)),
        port = 4031,
        deviceId = "device-$gateway",
        keyAlias = "fermix.device.$gateway.0102030405060708",
        pushSalt = base64(0x73),
        pushPlatforms = emptyList(),
        notificationsEnabled = false,
    )

/** The one candidate an idle session races. */
internal val IDLE_CANDIDATE = Candidate("100.101.102.9", Candidate.Scope.TAILNET, Candidate.Kind.IP)

/**
 * A session that never connects: its dialer waits for ever, on a phone with no network. Its [lastSuccessful]
 * names [IDLE_CANDIDATE] when asked, as a completed `hello` over it leaves it.
 */
internal fun idleSession(
    scope: CoroutineScope,
    store: SessionStore = NoStore,
    lastSuccessful: Candidate? = null,
): Session =
    Session.open(
        PairedInstance("device-9", "main", ByteArray(KEY_BYTES) { 9 }),
        listOf(IDLE_CANDIDATE),
        SessionParts(
            appVersion = "0.1.0",
            staticKey = NoKey,
            dialer = Dialer { awaitCancellation() },
            store = store,
            announcer = Announcer { Announcement.NOT_ANNOUNCED },
            network = MutableStateFlow(NetworkFacts.NONE),
        ),
        scope,
        lastSuccessful,
    )

private object NoKey : StaticKey {
    override val publicKey: ByteArray get() = ByteArray(KEY_BYTES) { 8 }

    override fun agree(peerPublicKey: ByteArray): ByteArray = error("the idle session never handshakes")
}

/** The store of a session that never connects: empty cursors and outbox, and no write. */
internal object NoStore : SessionStore {
    override suspend fun cursors() = StoredCursors(0uL, 0uL, 0uL, 0uL, 0uL)

    override suspend fun setServerCursor(
        seq: ULong,
        announcedUpToSeq: ULong,
        lastUnannouncedSeq: ULong,
    ) = error("the idle session never acks")

    override suspend fun setReadFrontier(seq: ULong) = error("the idle session reads nothing")

    override suspend fun applyMutations(
        rows: List<MutationRow>,
        lastMutationSeq: ULong,
    ) = error("the idle session gets no mutation")

    override suspend fun rebuildCache(mutationHeadSeq: ULong) = error("the idle session rebuilds nothing")

    override suspend fun outbox(): List<OutboxItem> = emptyList()

    override suspend fun enqueue(item: OutboxItem) = error("the idle session sends nothing")

    override suspend fun dequeue(clientMsgId: String) = error("the idle session sends nothing")

    override suspend fun markWritten(clientMsgId: String) = error("the idle session sends nothing")

    override suspend fun withdraw(clientMsgId: String) = error("the idle session sends nothing")

    override suspend fun markFailed(
        clientMsgId: String,
        failure: RequestFailure,
    ) = error("the idle session sends nothing")

    override suspend fun applyReaction(
        clientMsgId: String,
        emoji: String,
    ) = error("the idle session gets no reaction")

    override suspend fun addLinkPreview(
        serverSeq: ULong,
        card: LinkPreviewCard,
    ) = error("the idle session gets no link preview")

    override suspend fun applyTranscript(
        clientMsgId: String,
        text: String,
    ) = error("the idle session gets no transcript")

    override suspend fun markUploaded(
        clientMsgId: String,
        attachId: String,
    ) = error("the idle session uploads nothing")

    override suspend fun setUploadStarts(
        clientMsgId: String,
        starts: Int,
    ) = error("the idle session uploads nothing")
}

/**
 * [NoStore], but the cursors, which a session's run reads before its first race, come only once [gate] opens,
 * whatever cancels the run meanwhile: a store call still running as its session is closed.
 */
internal class GatedStore(
    private val gate: CompletableDeferred<Unit>,
) : SessionStore by NoStore {
    override suspend fun cursors(): StoredCursors =
        withContext(NonCancellable) {
            gate.await()
            NoStore.cursors()
        }
}

/**
 * [NoStore], but a request's enqueue, made in its caller's coroutine (Session.send), returns only once [gate]
 * opens: a request still writing the store as its session is closed.
 */
internal class GatedSends(
    private val gate: CompletableDeferred<Unit>,
) : SessionStore by NoStore {
    override suspend fun enqueue(item: OutboxItem) = gate.await()
}

/** A sink that keeps nothing, as if every event's Fermix were kept: the supervisor's tests count sessions. */
internal object NoSink : EventSink {
    override suspend fun take(
        instanceId: String,
        session: Session,
        event: SessionEvent,
    ): Boolean = true

    override suspend fun reached(
        instanceId: String,
        candidate: Candidate,
    ) = Unit

    override fun ended(instanceId: String) = Unit

    override fun removed(instanceId: String) = Unit
}

/** A clock that stands still, for the folds of sessions that never connect. */
internal object TestClock : ChatClock {
    override fun monoMs(): Long = 0L

    override fun wallMs(): Long = 0L
}

/** No approval is notified: every chat counts as on screen. */
internal val NoAlerts =
    ApprovalAlerts(
        { _, _ -> true },
        object : ApprovalNotifier {
            override fun canNotify(instanceId: String): Boolean = false

            override fun notify(
                instanceId: String,
                approval: SessionEvent.Approval,
            ): Unit = error("no approval is notified in these tests")
        },
    ) { 0L }
