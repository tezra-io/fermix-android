package io.tezra.fermix.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A forward page's rows at most: the wire's bound on `history_pull.limit` (PROTOCOL.md "History pages"). */
internal const val FORWARD_PAGE_LIMIT = 200

/** A backward page's rows: the newest page of a fresh profile, and each page [Session.loadOlder] asks for. */
internal const val BACKWARD_PAGE_LIMIT = 50

/** What became of a live row. */
internal enum class RowFate {
    /** It was the row after the cursor: announced, and the cursor moved to it. */
    APPLIED,

    /** The cursor had passed it: it was applied before. */
    DROPPED,

    /** A row is missing before it: it is not shown, and the history pull brings both (PROTOCOL.md). */
    GAP,
}

/**
 * What this phone owes the daemon: an `ack` and a `read_state`, each when it moved past what was sent,
 * and how long the ack waited since the announcement or the read that made it due.
 */
internal data class Reports(
    val ack: ULong?,
    val read: ULong?,
    val ackHeldMs: Long,
)

/**
 * The profile's timeline as the session keeps it in step: [cursor], the last row applied in order; [head],
 * the newest row the daemon has named; [readFrontier]; and the ack frontier, stored with the cursor so a
 * new session never acks past a row the owner was not told of. It lasts across connections. Rows go to
 * the announcer one at a time, outside the lock, since the app may call back into the session from it;
 * the cursor is stored only once the announcer has returned (Announcer). Only the session's actor
 * applies rows; the lock serializes the writes, so the store sees the frontier only move forward. The read
 * frontier goes to [readFrontierSaid], outside the lock, on every `hello_ack` and `read_state` whether it moved
 * or not, and whenever this phone's own read moves it, the owner's or a row landing on screen: each takes the
 * rows it covers from the app's notified set (design section 10, "Lifecycle on the phone").
 */
internal class Timeline(
    stored: StoredCursors,
    private val store: SessionStore,
    private val announcer: Announcer,
    private val now: () -> Long,
    private val readFrontierSaid: suspend (ULong) -> Unit,
) {
    private val writes = Mutex()
    private var ack =
        AckFrontier
            .resumed(stored.announcedUpToSeq, stored.lastServerSeq, stored.lastUnannouncedSeq)
            .read(stored.readUpToSeq)
    private var ackSent = 0uL
    private var ackDueAtMs: Long? = null
    private var readReported = stored.readUpToSeq

    var cursor: ULong = stored.lastServerSeq
        private set
    var head: ULong = maxOf(stored.lastServerSeq, stored.readUpToSeq)
        private set
    var readFrontier: ULong = stored.readUpToSeq
        private set

    /** The daemon named row [seq]. */
    fun saw(seq: ULong) {
        head = maxOf(head, seq)
    }

    /** A live `row` or `text_done` (PROTOCOL.md "One timeline"). */
    suspend fun live(row: TimelineRow): RowFate {
        saw(row.serverSeq)
        val fate =
            when {
                row.serverSeq <= cursor -> RowFate.DROPPED
                row.serverSeq == cursor + 1uL -> RowFate.APPLIED
                else -> RowFate.GAP
            }
        if (fate == RowFate.APPLIED) apply(row)
        return fate
    }

    /**
     * A forward page, or the newest page: every row past the cursor, in order, [applied] run after each
     * so its ack goes before the next row's announcement. A page is exact (PROTOCOL.md "History pages"),
     * so a seq it skips is no row.
     */
    suspend fun forward(
        rows: List<TimelineRow>,
        pageHead: ULong,
        applied: suspend () -> Unit,
    ) {
        requireAscending(rows)
        saw(pageHead)
        rows.filter { it.serverSeq > cursor }.forEach {
            apply(it)
            applied()
        }
    }

    /**
     * An older page: its rows for the cache alone, never announced, and neither the cursor nor the ack
     * moves. A row past the cursor is the forward stream's to apply, in order, so it is left out.
     */
    fun older(rows: List<TimelineRow>): List<TimelineRow> {
        requireAscending(rows)
        return rows.filter { it.serverSeq <= cursor }
    }

    /**
     * A connection's `hello_ack`: the newest row, and the frontier the daemon holds. The daemon's acked
     * cursor is the socket's and goes with it (tla/specs/mobile_push), so the ack is owed again on each
     * connection. The frontier is said, moved or not.
     */
    suspend fun connected(
        daemonHead: ULong,
        daemonRead: ULong,
    ) {
        writes.withLock {
            saw(daemonHead)
            ackSent = 0uL
            ackDueAtMs = null
            readReported = daemonRead
            moveRead(daemonRead)
        }
        readFrontierSaid(readFrontier)
    }

    /**
     * A frontier [reported] by the daemon, which then knows it, or by this phone's owner. True when it moved.
     * The daemon's is said moved or not, the owner's when it moved.
     */
    suspend fun read(
        reported: ULong,
        fromDaemon: Boolean,
    ): Boolean {
        val moved =
            writes.withLock {
                if (fromDaemon) readReported = maxOf(readReported, reported)
                moveRead(reported)
            }
        if (moved || fromDaemon) readFrontierSaid(readFrontier)
        return moved
    }

    /**
     * The `mutations_gone` rebuild: the store drops the cache and the cursor, and the cursor starts over.
     * The ack frontier stays, and the rows past it are judged again as the rebuilt cache brings them.
     */
    suspend fun rebuild(mutationHeadSeq: ULong) {
        writes.withLock {
            store.rebuildCache(mutationHeadSeq)
            cursor = 0uL
            ack = AckFrontier.resumed(ack.ackable, cursor, ack.held)
        }
    }

    /** What the daemon has not heard yet on this connection; once returned, it counts as sent. */
    fun due(): Reports {
        val ackNow = ack.ackable.takeIf { it > ackSent }
        val readNow = readFrontier.takeIf { it > readReported }
        val heldMs = ackDueAtMs?.let { now() - it } ?: 0L
        if (ackNow != null) {
            ackSent = ackNow
            ackDueAtMs = null
        }
        readReported = readNow ?: readReported
        return Reports(ackNow, readNow, heldMs)
    }

    private suspend fun apply(row: TimelineRow) {
        val announcement = announcer.announce(row)
        val announcedAt = now()
        val readOnScreen =
            writes.withLock {
                // A row a rebuild dropped and brought back was judged when it was first applied.
                val judged = row.serverSeq <= ack.lastApplied
                val next = if (judged) ack else ack.applied(row.serverSeq, announcement).read(readFrontier)
                store.setServerCursor(row.serverSeq, next.ackable, next.held)
                cursor = row.serverSeq
                ack = next
                ackDue(announcedAt)
                announcement == Announcement.ON_SCREEN && moveRead(row.serverSeq)
            }
        if (readOnScreen) readFrontierSaid(readFrontier)
    }

    private suspend fun moveRead(reported: ULong): Boolean {
        val next = advanceReadFrontier(readFrontier, reported, head)
        if (next == readFrontier) return false
        store.setReadFrontier(next)
        readFrontier = next
        ack = ack.read(next)
        ackDue(now())
        return true
    }

    /** The ack became due [atMs], unless it was due already and still waits. */
    private fun ackDue(atMs: Long) {
        if (ack.ackable > ackSent && ackDueAtMs == null) ackDueAtMs = atMs
    }
}

private fun requireAscending(rows: List<TimelineRow>) {
    val out = rows.zipWithNext().firstOrNull { (before, after) -> after.serverSeq <= before.serverSeq }
    if (out != null) throw SessionProtocolError("a page holds row ${out.second.serverSeq} after ${out.first.serverSeq}")
}
