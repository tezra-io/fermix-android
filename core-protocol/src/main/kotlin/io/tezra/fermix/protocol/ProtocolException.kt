package io.tezra.fermix.protocol

/**
 * Every refusal of a frame, an event, a continuation run or a pairing link, each its own type and
 * none recovered from inside this module. A refused frame or run is a protocol error the session
 * closes on (PROTOCOL.md "Delivery and failure behavior"); an unknown server event is not a refusal
 * but a [ServerEvent.Unknown].
 */
sealed class ProtocolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** A frame too short to hold its four-byte header length. */
    class FrameTooShort(
        val size: Int,
    ) : ProtocolException("a frame of $size bytes cannot hold its $LENGTH_PREFIX_BYTES-byte header length")

    /** A header past 4,096 bytes, declared by a frame's length or handed to the encoder. */
    class HeaderTooLong(
        val length: Long,
    ) : ProtocolException("a header of $length bytes is past the bound of $MAX_HEADER_BYTES")

    /** A raw tail past 61,440 bytes. */
    class RawTooLong(
        val size: Int,
    ) : ProtocolException("a raw tail of $size bytes is past the bound of $MAX_RAW_BYTES")

    /** A whole frame past 65,519 bytes. */
    class FrameTooLong(
        val size: Int,
    ) : ProtocolException("a frame of $size bytes is past the bound of $MAX_PLAINTEXT_BYTES")

    /** A header length past the bytes that follow it. */
    class LengthOverrunsFrame(
        val length: Long,
        val available: Int,
    ) : ProtocolException("a header length of $length overruns the $available bytes after it")

    /** A header that is not valid UTF-8, not JSON, or not one JSON object. */
    class MalformedHeader(
        val detail: String,
        cause: Throwable? = null,
    ) : ProtocolException("the header is not one JSON object in UTF-8: $detail", cause)

    /** A `v`, `t` or `seq` that is absent or of the wrong type or range. */
    class InvalidEnvelope(
        val field: String,
        val detail: String,
    ) : ProtocolException("the envelope's $field $detail")

    /** A `v` other than the two versions this codec reads, 1 and 2. */
    class UnsupportedVersion(
        val version: Long,
    ) : ProtocolException("protocol version $version is neither 1 nor 2")

    /** A client `t` outside the catalogue, which the daemon refuses as well. */
    class UnknownEvent(
        val t: String,
    ) : ProtocolException("'$t' is not a client event")

    /** A known event at a protocol version that does not carry it. */
    class EventNotInVersion(
        val t: String,
        val version: Int,
    ) : ProtocolException("'$t' is not an event of protocol v$version")

    /** A field the event requires at its version, absent. */
    class MissingField(
        val t: String,
        val field: String,
    ) : ProtocolException("'$t' has no $field")

    /** A field sent as `null`: an optional field is absent, never null (PROTOCOL.md). */
    class NullField(
        val t: String,
        val field: String,
    ) : ProtocolException("'$t' carries $field as null")

    /** A field of the wrong type, out of its bounds, or breaking a rule of its event. */
    class InvalidField(
        val t: String,
        val field: String,
        val reason: String,
        cause: Throwable? = null,
    ) : ProtocolException("'$t' $field $reason", cause)

    /** A field the encoder was handed that the event does not carry at the frame's version. */
    class FieldNotInVersion(
        val t: String,
        val field: String,
        val version: Int,
    ) : ProtocolException("'$t' carries no $field in protocol v$version")

    /** A raw tail on an event that takes none. */
    class UnexpectedRaw(
        val t: String,
        val size: Int,
    ) : ProtocolException("'$t' takes no raw tail, and has $size bytes")

    /** An empty raw tail on an event whose tail is never empty. */
    class MissingRaw(
        val t: String,
    ) : ProtocolException("'$t' has an empty raw tail")

    /** An `event_part` whose `count` differs from its run's. */
    class PartCountChanged(
        val count: Int,
        val runCount: Int,
    ) : ProtocolException("a part says the run has $count parts, its first said $runCount")

    /** An `event_part` whose `index` is not the next one of its run, or a run that does not start at 0. */
    class PartOutOfOrder(
        val index: Int,
        val expected: Int,
    ) : ProtocolException("a part has index $index where $expected was due")

    /** A frame other than an `event_part` inside an open run. */
    class FrameInsideRun(
        val t: String,
    ) : ProtocolException("'$t' arrived inside an event_part run")

    /** An `event_part` whose `seq` is not its run's first plus its `index`. */
    class PartOutOfSequence(
        val seq: ULong,
        val expected: ULong,
    ) : ProtocolException("a part has seq $seq where $expected was due")

    /** An `event_part` whose `v` differs from its run's. */
    class PartVersionChanged(
        val version: Int,
        val runVersion: Int,
    ) : ProtocolException("a part is protocol v$version, its run's first v$runVersion")

    /** A run whose tails add up past the 1 MiB logical event bound. */
    class RunTooLarge(
        val size: Long,
    ) : ProtocolException("a run of $size bytes is past the event bound of $MAX_EVENT_BYTES")

    /** A logical event that names `event_part` or `media_chunk`, neither of which is ever split. */
    class UnsplittableEvent(
        val t: String,
    ) : ProtocolException("'$t' never arrives as an event_part run")

    /** An assembler called again after a refusal ended its run. */
    class AssemblerFailed : ProtocolException("an earlier refusal ended this event_part assembler")

    /** A link that does not start with `fermix://pair?`. */
    class NotAPairingLink : ProtocolException("the link does not start with $PAIRING_LINK_PREFIX")

    /** A pairing link without a parameter its version requires. */
    class MissingParameter(
        val name: String,
    ) : ProtocolException("the pairing link has no $name")

    /** A pairing link parameter whose value is not what its row of PROTOCOL.md says. */
    class MalformedParameter(
        val name: String,
        val reason: String,
        cause: Throwable? = null,
    ) : ProtocolException("the pairing link's $name $reason", cause)

    /** A pairing link that names a parameter it reads more than once. */
    class RepeatedParameter(
        val name: String,
    ) : ProtocolException("the pairing link names $name more than once")
}

/**
 * The cause of a refusal kotlinx.serialization raised, without its message: that message quotes the
 * text it refused, which may hold an approval's token, and a refusal is logged and shown in
 * Diagnostics. Its type and stack trace are kept for a developer.
 */
internal class ParserRefusal(
    refusal: Exception,
) : Exception("${refusal::class.qualifiedName}, its message withheld as it quotes the header") {
    init {
        stackTrace = refusal.stackTrace
    }
}

/** How much of a free-form key, a row's metadata's, a refusal's path quotes. */
private const val MAX_QUOTED_KEY_CHARS = 64

/**
 * A free-form key as a refusal's path names it: the daemon's text, which may run to the event's
 * 1 MiB, cut to its first [MAX_QUOTED_KEY_CHARS] characters, counted in code points so no pair is
 * split, since a refusal is logged and shown in Diagnostics.
 */
internal fun quotedKey(name: String): String {
    if (name.codePointCount(0, name.length) <= MAX_QUOTED_KEY_CHARS) return name
    return name.substring(0, name.offsetByCodePoints(0, MAX_QUOTED_KEY_CHARS)) + "…"
}

/** Throws the refusal when [refused] holds: the guard every check in this module is written with. */
internal inline fun refuseIf(
    refused: Boolean,
    refusal: () -> ProtocolException,
) {
    if (refused) throw refusal()
}
