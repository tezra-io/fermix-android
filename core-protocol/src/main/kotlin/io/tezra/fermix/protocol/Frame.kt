package io.tezra.fermix.protocol

import java.nio.ByteBuffer

/** The JSON header's bound (PROTOCOL.md "Stack and bounds", the schema's `x-max-json-header-bytes`). */
const val MAX_HEADER_BYTES = 4_096

/** One raw tail's bound, 60 KiB (the schema's `x-max-raw-chunk-bytes`). */
const val MAX_RAW_BYTES = 61_440

/** A whole frame's bound: a Noise message, 65,535 bytes, less its 16-byte tag (`x-max-plaintext-bytes`). */
const val MAX_PLAINTEXT_BYTES = 65_519

/** The header length ahead of the header: an unsigned 32-bit big-endian integer. */
const val LENGTH_PREFIX_BYTES = 4

/**
 * One mobile plaintext frame, `uint32be header length | JSON header | raw tail`: what one Noise
 * transport message carries. A frame past any bound cannot be built, so a refused frame is refused
 * before any byte of it is written. The frame holds the arrays it is given, without copying them.
 */
class Frame(
    val header: ByteArray,
    val raw: ByteArray,
) {
    init {
        refuseIf(header.size > MAX_HEADER_BYTES) { ProtocolException.HeaderTooLong(header.size.toLong()) }
        refuseIf(raw.size > MAX_RAW_BYTES) { ProtocolException.RawTooLong(raw.size) }
        val size = LENGTH_PREFIX_BYTES + header.size + raw.size
        refuseIf(size > MAX_PLAINTEXT_BYTES) { ProtocolException.FrameTooLong(size) }
    }

    /** The frame's bytes, ready for one Noise transport message. */
    fun encode(): ByteArray =
        ByteBuffer
            .allocate(LENGTH_PREFIX_BYTES + header.size + raw.size)
            .putInt(header.size)
            .put(header)
            .put(raw)
            .array()

    companion object {
        /**
         * Splits one decrypted Noise message into its header and raw tail, as the daemon does: the
         * whole frame's bound first, then the declared header length against its bound and against
         * the bytes that follow it, so a length past 4,096 is refused as such even when the bytes
         * run out sooner. The tail's bound is the frame's own, checked as it is built.
         */
        fun decode(bytes: ByteArray): Frame {
            refuseIf(bytes.size > MAX_PLAINTEXT_BYTES) { ProtocolException.FrameTooLong(bytes.size) }
            refuseIf(bytes.size < LENGTH_PREFIX_BYTES) { ProtocolException.FrameTooShort(bytes.size) }
            val length =
                ByteBuffer
                    .wrap(bytes, 0, LENGTH_PREFIX_BYTES)
                    .int
                    .toUInt()
                    .toLong()
            refuseIf(length > MAX_HEADER_BYTES) { ProtocolException.HeaderTooLong(length) }
            val available = bytes.size - LENGTH_PREFIX_BYTES
            refuseIf(length > available) { ProtocolException.LengthOverrunsFrame(length, available) }
            val rawStart = LENGTH_PREFIX_BYTES + length.toInt()
            return Frame(bytes.copyOfRange(LENGTH_PREFIX_BYTES, rawStart), bytes.copyOfRange(rawStart, bytes.size))
        }
    }
}
