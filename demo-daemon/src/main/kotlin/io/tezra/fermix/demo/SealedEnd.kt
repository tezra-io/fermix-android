package io.tezra.fermix.demo

/**
 * The daemon's end of one [MemoryLink] once its handshake is answered: each frame the phone sends opened and
 * read, and each frame the daemon writes sealed and delivered, by the two ciphers the responder split into. One
 * coroutine drives it at a time, as a Noise session requires.
 */
class SealedEnd(
    val link: MemoryLink,
) {
    private var receiving: TransportCipher? = null
    private var sending: TransportCipher? = null

    /** The handshake's outcome on the daemon's side, once message 2 went: its hash and the phone's key. */
    var responded: Responded? = null
        private set

    /** How many frames have gone each way since the handshake: the most of either. */
    val frames: Long get() = maxOf(receiving?.messages ?: 0L, sending?.messages ?: 0L)

    /** Answers [first], the phone's message 1, with [responder]'s message 2, and keeps the ciphers it split into. */
    fun respond(
        first: ByteArray,
        responder: IkResponder,
    ): Responded {
        val outcome = responder.respond(first)
        receiving = outcome.receive
        sending = outcome.send
        responded = outcome
        link.deliver(outcome.second)
        return outcome
    }

    /** The phone's next message, still sealed, or null once the phone closed: message 1 of a handshake. */
    suspend fun nextMessage(): ByteArray? = link.toDaemon.receiveCatching().getOrNull()

    /** The phone's next frame, opened and read, or null once the phone closed. */
    suspend fun receive(): ClientFrame? = nextMessage()?.let(::open)

    /** [message], a frame the phone sealed, opened and read. */
    fun open(message: ByteArray): ClientFrame =
        clientFrame(checkNotNull(receiving) { "no handshake yet" }.decrypt(message))

    /** Seals [frame] and delivers it to the phone. */
    fun deliverSealed(frame: ByteArray) {
        link.deliver(checkNotNull(sending) { "no handshake yet" }.encrypt(frame))
    }
}
