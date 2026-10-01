package io.tezra.fermix.transport

import kotlin.random.Random

/** The reconnect loop's first wait and its last (design section 5.1: "Backoff 1 → 30 s with jitter"). */
private const val BACKOFF_FIRST_MS = 1_000L
private const val BACKOFF_MAX_MS = 30_000L

/** Past this a doubling could overflow a Long, so no wait is allowed this long. */
private const val LONGEST_MAX_MS = Long.MAX_VALUE / 2

/**
 * The wait before the reconnect loop's next race, as a value: each failure gives [next], a success
 * gives [reset]. A step's ceiling is [firstMs] doubled once per step and held at [maxMs], and its wait
 * is drawn from the upper half of the ceiling, so instances that lost the same network do not retry in
 * step. The step stops where the ceiling reaches [maxMs] (step 5 for 1 s to 30 s), so no count of
 * failures overflows it: past that every wait is between half of [maxMs] and [maxMs], for as long as
 * the loop runs. The loop is unbounded on purpose: it keeps trying underneath "Can't reach" (design
 * section 13.5). The hourly `1000` close is not a failure and reconnects at once, without a wait.
 */
class Backoff private constructor(
    private val firstMs: Long,
    private val maxMs: Long,
    private val jitter: Random,
    val step: Int,
) {
    constructor(firstMs: Long = BACKOFF_FIRST_MS, maxMs: Long = BACKOFF_MAX_MS, jitter: Random) :
        this(firstMs, maxMs, jitter, step = 0) {
        require(firstMs > 0) { "a first wait of $firstMs ms is not positive" }
        require(firstMs <= maxMs) { "the first wait, $firstMs ms, is past the last, $maxMs ms" }
        require(maxMs <= LONGEST_MAX_MS) { "a last wait of $maxMs ms is past $LONGEST_MAX_MS" }
    }

    /** This step's longest wait. */
    val ceilingMs: Long = minOf(maxMs, firstMs shl step)

    /** This step's wait: between half of [ceilingMs] and all of it, drawn from the jitter. */
    fun delayMs(): Long = ceilingMs - jitter.nextLong(ceilingMs / 2 + 1)

    /** The wait after one more failure; at the cap, this one. */
    fun next(): Backoff = if (ceilingMs == maxMs) this else Backoff(firstMs, maxMs, jitter, step + 1)

    /** The wait after a success: the first again. */
    fun reset(): Backoff = Backoff(firstMs, maxMs, jitter, step = 0)
}
