package io.tezra.fermix.session

/** A `ping` goes after this long with nothing sent, and its `pong` is due within it (design section 5.1). */
internal const val KEEPALIVE_MS = 25_000L

/** Missed pongs in a row that lose the link. */
private const val MISSED_PONGS_LOST = 2

internal enum class KeepaliveAction { NONE, PING, LOST }

/**
 * A ping whose pong has not been read: when it went, moved on by the time the reader was held since;
 * whether its 25 s ran out; and whether its round trip still measures the link.
 */
private class OwedPing(
    var sentMs: Long,
    var missed: Boolean = false,
    var timed: Boolean = true,
)

/**
 * One connection's keepalive, as rules over the times it is given: a `ping` after 25 s of silence in
 * the send direction, the latency from the `pong`, and the link lost after two pings in a row went
 * unanswered for 25 s each. The daemon answers pings in order, so each pong answers the oldest ping
 * owed; one that comes after its ping's 25 s ran out is that ping's, with its round trip, and forgives
 * no miss. While the actor keeps the reader waiting, [stall] to [unstall], no pong can be read, so no
 * ping is missed then and that time is given back to every ping owed; pings still go, so the daemon
 * hears from the phone. At most one ping goes per [poll], so the caller's bound on polls bounds the pings
 * owed. The caller sends the ping and owns the clock.
 */
internal class Keepalive(
    startMs: Long,
) {
    private var lastSentMs = startMs
    private val owed = ArrayDeque<OwedPing>()
    private var missed = 0
    private var stalledSinceMs: Long? = null

    /** When [poll] has something to say next, if nothing is sent or received before then. */
    val nextCheckMs: Long
        get() {
            val pingAt = lastSentMs + KEEPALIVE_MS
            val dueAt = owed.firstOrNull { !it.missed }?.sentMs?.plus(KEEPALIVE_MS)
            return if (dueAt == null || stalledSinceMs != null) pingAt else minOf(pingAt, dueAt)
        }

    /** A frame went out: the silence starts again. */
    fun sent(atMs: Long) {
        lastSentMs = maxOf(lastSentMs, atMs)
    }

    /**
     * A `pong` arrived: the round trip of the oldest ping owed, or null when none was owed or the reader
     * was held while it was out, which leaves its round trip unmeasured.
     */
    fun pong(atMs: Long): Long? {
        val ping = owed.removeFirstOrNull() ?: return null
        if (!ping.missed) missed = 0
        return if (ping.timed) atMs - ping.sentMs else null
    }

    /** The reader holds an event the actor has not taken yet, from [atMs]. */
    fun stall(atMs: Long) {
        stalledSinceMs = atMs
    }

    /** The actor took it at [atMs]: every ping owed gets the held time back, or starts then if sent within it. */
    fun unstall(atMs: Long) {
        val since = stalledSinceMs ?: return
        stalledSinceMs = null
        if (atMs == since) return
        owed.forEach { ping ->
            ping.sentMs += atMs - maxOf(ping.sentMs, since)
            ping.timed = false
        }
    }

    /** What is due at [atMs]. A ping returned here counts as sent at [atMs]. */
    fun poll(atMs: Long): KeepaliveAction {
        if (stalledSinceMs == null) countMissed(atMs)
        return when {
            missed >= MISSED_PONGS_LOST -> KeepaliveAction.LOST
            atMs - lastSentMs >= KEEPALIVE_MS -> ping(atMs)
            else -> KeepaliveAction.NONE
        }
    }

    private fun countMissed(atMs: Long) {
        val late = owed.filter { !it.missed && atMs - it.sentMs >= KEEPALIVE_MS }
        late.forEach { it.missed = true }
        missed += late.size
    }

    private fun ping(atMs: Long): KeepaliveAction {
        owed.addLast(OwedPing(atMs))
        lastSentMs = atMs
        return KeepaliveAction.PING
    }
}
