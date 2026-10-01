package io.tezra.fermix.protocol

/**
 * One event and its envelope: the version it was encoded at, its seq, and the frame's raw tail,
 * empty when it has none. Which versions a session accepts, and whether seqs follow each other,
 * are the session's to judge (PROTOCOL.md "Envelope, ordering, and version negotiation"). Only the
 * decoder builds one, so each holds what the decoder checked.
 */
class Decoded<out E> internal constructor(
    val v: Int,
    val seq: ULong,
    val event: E,
    val raw: ByteArray,
)
