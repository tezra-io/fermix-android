package io.tezra.fermix.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.io.ByteArrayOutputStream

/** The fewest frames a run has: an event that fits one frame is sent as one. */
const val MIN_EVENT_PARTS = 2

/** The most frames a run has (the schema's `x-max-event-parts`): 1 MiB in 60 KiB tails. */
const val MAX_EVENT_PARTS = 18

/** A logical event's bound, 1 MiB (the schema's `x-max-event-bytes`). */
const val MAX_EVENT_BYTES = 1_048_576

internal const val EVENT_PART = "event_part"

/**
 * Joins `event_part` runs into the events they carry (PROTOCOL.md "Continuation frames"). Fed
 * every decoded server frame in order, it hands back a frame that is not a part as it is, holds a
 * part until its run completes, and then hands back the run's logical event, decoded at the run's
 * `v` with its first frame's `seq`. A run is `count` frames with `index` 0 to `count - 1` in order,
 * one `count` and one `v` throughout, consecutive `seq`s, nothing between them, and at most 1 MiB in
 * all. A run that breaks any of these is a protocol error the session closes on, and the assembler
 * refuses every frame after it. What one part must be on its own, a `count` of 2 to 18, an `index`
 * below it and a tail that is not empty, the decoder has checked (Rules.kt). That the run's `seq`s
 * follow the frames before it and its `v` is the session's is the session's to check, as for every
 * frame (PROTOCOL.md "Envelope, ordering, and version negotiation").
 *
 * A pure state machine: no I/O and no clock, fed one session's frames from one thread.
 */
class EventPartAssembler {
    private var run: Run? = null
    private var failed = false

    /**
     * The next server frame, as [decodeServerEvent] returned it: the event it completes, the frame
     * itself, or null while a run is open.
     */
    fun accept(frame: Decoded<ServerEvent>): Decoded<ServerEvent>? {
        refuseIf(failed) { ProtocolException.AssemblerFailed() }
        val event = frame.event
        try {
            return if (event is ServerEvent.EventPart) part(frame, event) else whole(frame)
        } catch (refusal: ProtocolException) {
            failed = true
            throw refusal
        }
    }

    private fun whole(frame: Decoded<ServerEvent>): Decoded<ServerEvent> {
        refuseIf(run != null) { ProtocolException.FrameInsideRun(frame.event.wireName()) }
        return frame
    }

    private fun part(
        frame: Decoded<ServerEvent>,
        part: ServerEvent.EventPart,
    ): Decoded<ServerEvent>? {
        val open = run ?: Run(frame.v, frame.seq, part.count)
        refuseIf(part.count != open.count) { ProtocolException.PartCountChanged(part.count, open.count) }
        refuseIf(part.index != open.next) { ProtocolException.PartOutOfOrder(part.index, open.next) }
        refuseIf(frame.v != open.v) { ProtocolException.PartVersionChanged(frame.v, open.v) }
        val due = open.seq + open.next.toULong()
        refuseIf(frame.seq != due) { ProtocolException.PartOutOfSequence(frame.seq, due) }
        open.append(frame.raw)
        run = open.takeIf { it.next < it.count }
        return if (run == null) decodeLogicalServerEvent(open.v, open.seq, open.json()) else null
    }

    /** An open run: the version and seq of its first frame, its length, and its tails so far. */
    private class Run(
        val v: Int,
        val seq: ULong,
        val count: Int,
    ) {
        private val tails = ByteArrayOutputStream()
        var next = 0
            private set

        fun append(tail: ByteArray) {
            val size = tails.size().toLong() + tail.size
            refuseIf(size > MAX_EVENT_BYTES) { ProtocolException.RunTooLarge(size) }
            tails.write(tail)
            next++
        }

        fun json(): ByteArray = tails.toByteArray()
    }
}

/** An event's `t`, for a refusal that names it. */
private fun ServerEvent.wireName(): String =
    when (this) {
        is ServerEvent.Unknown -> {
            t
        }

        is ServerEvent.Known -> {
            WIRE_JSON
                .encodeToJsonElement(serializer<ServerEvent.Known>(), this)
                .jsonObject
                .getValue("t")
                .jsonPrimitive.content
        }
    }
