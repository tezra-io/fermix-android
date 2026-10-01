package io.tezra.fermix.session

/**
 * The cumulative `ack` the phone may send: the highest row such that every row applied up to it was
 * announced, or is one the owner has read (design section 7, the `ack` row). A row the announcer did
 * not announce holds it below that row, whatever is announced after (tla/specs/mobile_push, PUSH-2): an
 * ack tells the daemon this phone has told its owner, so the daemon skips it when it decides a push. A
 * row the owner has read needs no ack to be spared a push, since the daemon pushes no row the read
 * frontier covers (design section 10, the `DecidesOnEvidence` rule), so a read releases it. [held] is the
 * last row applied that was not announced and is not read yet, 0 when none: once the read frontier
 * reaches it, nothing holds the ack, which is then every row applied and follows each announcement after
 * it. A read that falls between two such rows moves the ack only up to the read, since only the last one
 * is kept: the announced rows between the read and [held] wait until the read reaches it, a redundant
 * push at worst, never an ack for a row the owner was not told of. A value, fed the applied rows in order
 * after [lastApplied]; a seq the feed skips is no row of this phone's.
 */
internal class AckFrontier private constructor(
    val ackable: ULong,
    val lastApplied: ULong,
    val held: ULong,
) {
    init {
        val consistent = if (held == 0uL) ackable == lastApplied else held in (ackable + 1uL)..lastApplied
        require(consistent) { "an ack of $ackable, row $held held and row $lastApplied applied do not agree" }
    }

    constructor(base: ULong) : this(base, base, 0uL)

    fun applied(
        seq: ULong,
        announcement: Announcement,
    ): AckFrontier {
        require(seq > lastApplied) { "row $seq is applied after row $lastApplied" }
        return when {
            announcement == Announcement.NOT_ANNOUNCED -> AckFrontier(ackable, seq, seq)
            held == 0uL -> AckFrontier(seq, seq, 0uL)
            else -> AckFrontier(ackable, seq, held)
        }
    }

    /** The owner has read up to [frontier]: those rows need no announcement. */
    fun read(frontier: ULong): AckFrontier =
        if (held != 0uL && frontier >= held) {
            AckFrontier(lastApplied, lastApplied, 0uL)
        } else {
            AckFrontier(maxOf(ackable, minOf(frontier, lastApplied)), lastApplied, held)
        }

    companion object {
        /**
         * The frontier a session starts from: [announcedUpTo] as stored, below [cursor] while
         * [lastUnannounced], a row the owner was not told of, holds it. After a `mutations_gone` rebuild the
         * cursor is below it: the rows past it went with the cache, the held one among them, and each is
         * judged again when the rebuilt cache brings it back.
         */
        fun resumed(
            announcedUpTo: ULong,
            cursor: ULong,
            lastUnannounced: ULong,
        ): AckFrontier {
            val lastApplied = maxOf(announcedUpTo, cursor)
            val held = if (announcedUpTo < lastApplied) lastUnannounced else 0uL
            return AckFrontier(announcedUpTo, lastApplied, held)
        }
    }
}

/**
 * The read frontier after [reported]: it only moves forward, and never past the newest row, [head]
 * (PROTOCOL.md "Read state").
 */
internal fun advanceReadFrontier(
    current: ULong,
    reported: ULong,
    head: ULong,
): ULong = maxOf(current, minOf(reported, head))
