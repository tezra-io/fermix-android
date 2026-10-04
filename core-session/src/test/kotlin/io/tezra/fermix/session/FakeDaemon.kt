package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertInstanceOf

/** The most `ack`s a test reads in a row: one per row of a forward page. */
private const val MAX_ACKS_READ = FORWARD_PAGE_LIMIT

/** The most pings a test skips while it waits for another frame. */
private const val MAX_PINGS_SKIPPED = 100

/** The most frames a daemon that answers pings reads: an hour of pings, with room. */
private const val MAX_FRAMES_ANSWERED = 10_000

/**
 * Both ends of one socket in memory. Like core-transport's Connection, a daemon's close ends the
 * phone's incoming messages after those it already holds, and the phone's own close drops the ones
 * it has not read.
 */
internal class FakeLink : Link {
    val toDaemon = Channel<ByteArray>(Channel.UNLIMITED)
    private val toPhone = Channel<ByteArray>(Channel.UNLIMITED)
    private val ended = CompletableDeferred<TransportException>()

    /** The close this side sent, once it sent one. */
    val phoneClose = CompletableDeferred<TransportException.Closed>()

    /** What the socket holds unwritten, as a test sets it: a daemon that reads slowly. */
    @Volatile
    var queued = 0L

    override val queuedBytes: Long get() = queued

    override val incoming: ReceiveChannel<ByteArray> get() = toPhone
    override val closed: Deferred<TransportException> get() = ended

    override fun send(message: ByteArray): Boolean = !ended.isCompleted && toDaemon.trySend(message).isSuccess

    override fun close(
        code: Int,
        reason: String,
    ) {
        val close = TransportException.Closed(code, reason, byDaemon = false)
        phoneClose.complete(close)
        ended.complete(close)
        toPhone.cancel()
        toDaemon.close()
    }

    fun deliver(message: ByteArray) {
        toPhone.trySend(message)
    }

    fun closeByDaemon(
        code: Int,
        reason: String,
    ) {
        ended.complete(TransportException.Closed(code, reason, byDaemon = true))
        toPhone.close()
        toDaemon.close()
    }

    /** The socket fails with no close frame, as a dropped network leaves it. */
    fun fail(cause: Exception) {
        ended.complete(TransportException.Unreachable(cause))
        toPhone.close()
        toDaemon.close()
    }
}

/** A `pair_request` as the daemon reads it: its frame, its event, and its raw tail split into the chain. */
internal class PairRequestRead(
    val frame: ClientFrame,
    val request: ClientEvent.PairRequest,
    val chain: List<ByteArray>,
)

/** The daemon's end of one connection: the IK or IKpsk2 responder, its seq, and the frames both ways. */
internal class DaemonConnection(
    val link: FakeLink,
    private val gatewayKey: SoftwareKey,
    val candidate: Candidate,
) {
    private var receiving: TransportCipher? = null
    private var sending: TransportCipher? = null
    private var seq = 0uL

    /** The handshake's outcome on the daemon's side, once message 2 went: its hash and the phone's key. */
    var responded: Responded? = null
        private set

    /** Reads message 1 and answers it with message 2. */
    suspend fun handshake() = respondWith(IkResponder(gatewayKey))

    /** Reads a pairing's message 1, `FXM1·02` and IKpsk2 with [psk], and answers it with message 2. */
    suspend fun pair(psk: ByteArray) = respondWith(IkResponder(gatewayKey, psk.copyOf()))

    /** Reads message 1 and refuses it before message 2, as a daemon with no window open does. */
    suspend fun refuseHandshake(
        code: Int,
        reason: String,
    ) {
        link.toDaemon.receive()
        close(code, reason)
    }

    private suspend fun respondWith(responder: IkResponder) {
        val outcome = responder.respond(link.toDaemon.receive())
        receiving = outcome.receive
        sending = outcome.send
        responded = outcome
        link.deliver(outcome.second)
    }

    /** Answers message 1 with bytes that do not authenticate as the paired daemon's. */
    suspend fun answerAsAnotherDaemon() {
        link.toDaemon.receive()
        link.deliver(ByteArray(48) { 7 })
    }

    /** The phone's next frame, or null once the phone closed. */
    suspend fun receive(): ClientFrame? {
        val message = link.toDaemon.receiveCatching().getOrNull() ?: return null
        return clientFrame(checkNotNull(receiving) { "no handshake yet" }.decrypt(message))
    }

    /** The phone's next frame; the phone closing first fails the test. */
    suspend fun next(): ClientFrame = checkNotNull(receive()) { "the phone closed" }

    /** The phone's next frame that is not a `ping`, for a test whose virtual clock runs the keepalive. */
    suspend fun nextBesidesPing(): ClientFrame {
        repeat(MAX_PINGS_SKIPPED) {
            val frame = next()
            if (frame.event != ClientEvent.Ping) return frame
        }
        error("only pings in $MAX_PINGS_SKIPPED frames")
    }

    /**
     * Answers each of the phone's pings with a `pong` [afterMs] later, in order, on [scope], and keeps
     * every other event in [others], until the phone closes.
     */
    suspend fun answerPings(
        scope: CoroutineScope,
        afterMs: Long,
        others: MutableList<ClientEvent>,
    ) {
        repeat(MAX_FRAMES_ANSWERED) {
            val event = receive()?.event ?: return
            when (event) {
                ClientEvent.Ping -> scope.launch { pongAfter(afterMs) }
                else -> others.add(event)
            }
        }
        error("the phone sent $MAX_FRAMES_ANSWERED frames")
    }

    private suspend fun pongAfter(afterMs: Long) {
        delay(afterMs)
        send(ServerEvent.Pong)
    }

    suspend inline fun <reified E : ClientEvent> expect(): E = assertInstanceOf<E>(receive()?.event)

    /**
     * The phone's next frame, which must be a `pair_request` whose `attestation.cert_lengths` add up to its
     * raw tail exactly; the tail split by them, leaf first.
     */
    suspend fun pairRequest(): PairRequestRead {
        val frame = next()
        val request = assertInstanceOf<ClientEvent.PairRequest>(frame.event)
        val lengths = checkNotNull(request.attestation) { "a protocol v2 pair_request carries attestation" }.certLengths
        assertEquals(frame.raw.size, lengths.sum()) { "cert_lengths add up to the raw tail" }
        var at = 0
        val chain = lengths.map { length -> frame.raw.copyOfRange(at, at + length).also { at += length } }
        return PairRequestRead(frame, request, chain)
    }

    /** The `ack`s the phone sends next, up to the one for [seq]; any other frame first fails the test. */
    suspend fun acksThrough(seq: ULong): List<ULong> {
        val acks = mutableListOf<ULong>()
        repeat(MAX_ACKS_READ) {
            acks += expect<ClientEvent.Ack>().serverSeq
            if (acks.last() >= seq) return acks.also { read -> assertEquals(seq, read.last()) }
        }
        error("no ack for row $seq in $MAX_ACKS_READ frames")
    }

    /** The handshake, the phone's `hello`, and [ack]; the `hello` it read. */
    suspend fun connect(ack: ServerEvent.HelloAck = HELLO_ACK): ClientFrame {
        handshake()
        val hello = checkNotNull(receive()) { "the phone closed before its hello" }
        send(ack)
        return hello
    }

    fun send(
        event: ServerEvent.Known,
        v: Int = 2,
    ) = sendAt(seq + 1u, event, v)

    /** Sends [event] with [raw] as its frame's tail: a blob's chunk. */
    fun sendWithRaw(
        event: ServerEvent.Known,
        raw: ByteArray,
    ) {
        seq++
        deliverSealed(serverFrame(2, seq, event, raw))
    }

    /** Sends [event] at [at], which a test may put out of sequence. */
    fun sendAt(
        at: ULong,
        event: ServerEvent.Known,
        v: Int = 2,
    ) {
        seq = at
        deliverSealed(serverFrame(v, at, event))
    }

    fun sendRun(
        event: ServerEvent.Known,
        parts: Int,
    ) {
        serverRun(seq + 1u, event, parts).forEach(::deliverSealed)
        seq += parts.toUInt()
    }

    fun sendUnknown(t: String) {
        seq++
        deliverSealed(unknownFrame(seq, t))
    }

    fun close(
        code: Int,
        reason: String = "",
    ) = link.closeByDaemon(code, reason)

    suspend fun phoneClosed(): TransportException.Closed = link.phoneClose.await()

    private fun deliverSealed(frame: ByteArray) {
        link.deliver(checkNotNull(sending) { "no handshake yet" }.encrypt(frame))
    }
}

/** The daemon at every candidate: each dial opens a socket a test then accepts and drives. */
internal class FakeDaemon : Dialer {
    val gatewayKey = SoftwareKey.generate()
    private val connections = Channel<DaemonConnection>(Channel.UNLIMITED)

    /** What a dial throws instead of opening, when it returns one. */
    var refusal: (Candidate) -> Exception? = { null }

    /** Every candidate the phone dialed, in order, refused dials included. */
    val dialed = mutableListOf<Candidate>()

    /** How many times the phone dialed, refused dials included. */
    val dials: Int get() = dialed.size

    override suspend fun dial(candidate: Candidate): Link {
        dialed += candidate
        refusal(candidate)?.let { throw it }
        val link = FakeLink()
        connections.send(DaemonConnection(link, gatewayKey, candidate))
        return link
    }

    suspend fun accept(): DaemonConnection = connections.receive()
}
