package io.tezra.fermix.session

import kotlin.random.Random

/** The line reads the opening word this long before its first phrase (design section 8.2). */
private const val OPENING_MS = 5_000L

/** How long each phrase stays. */
private const val PHRASE_MS = 8_000L

/** From this long on, the phrases come from the second pool, where "Still working" lives. */
private const val SECOND_POOL_FROM_MS = 45_000L

/** The phrase slots before the second pool: 5 s to 45 s, 8 s each. */
private const val FIRST_POOL_SLOTS = (SECOND_POOL_FROM_MS - OPENING_MS + PHRASE_MS - 1) / PHRASE_MS

/**
 * The working indicator's words: the opening word, the phrases of its first 45 s, and those after.
 * The pools are copy the owner edits (design section 13.9), so the UI may pass its own. A phrase says
 * that work goes on and never how far along it is. No phrase may come twice in a row, so each pool
 * holds two distinct phrases at least, the pools share none, and neither holds the opening word.
 */
class IndicatorPools(
    val opening: String,
    val first: List<String>,
    val second: List<String>,
) {
    init {
        val phrases = first + second
        require(first.size >= 2 && second.size >= 2) { "each pool needs two phrases at least" }
        require(phrases.distinct().size == phrases.size) { "a phrase is in a pool twice, or in both pools" }
        require(opening !in phrases) { "the opening word '$opening' is also a phrase" }
        require(opening.isNotBlank() && phrases.none { it.isBlank() }) { "a phrase is blank" }
    }
}

/** Design section 13.9's draft pools, which the owner edits. */
val DRAFT_INDICATOR_POOLS =
    IndicatorPools(
        opening = "Thinking",
        first =
            listOf(
                "Connecting the dots",
                "Mulling it over",
                "Digging in",
                "Piecing it together",
                "Turning it over",
                "Following the thread",
                "Working it out",
                "Chewing on it",
            ),
        second =
            listOf(
                "Still working",
                "Still on it",
                "Staying with it",
                "Deep in it",
                "Not done yet",
                "Taking the long road",
            ),
    )

/**
 * The indicator's line [elapsedMs] after the phone's message was accepted (onboarding gotcha 19): the
 * opening word for 5 s, then a phrase every 8 s from the first pool, and from the second once 45 s have
 * passed. While the daemon's headings or a running tool chip are on the card, [daemonSpeaking], it is
 * the opening word, and the headings do the talking. A pure function of the time and the turn's [seed].
 */
fun indicatorLine(
    elapsedMs: Long,
    seed: Long,
    daemonSpeaking: Boolean,
    pools: IndicatorPools = DRAFT_INDICATOR_POOLS,
): String {
    require(elapsedMs >= 0) { "an elapsed time of $elapsedMs ms is negative" }
    if (daemonSpeaking || elapsedMs < OPENING_MS) return pools.opening
    val slot = (elapsedMs - OPENING_MS) / PHRASE_MS
    return if (slot < FIRST_POOL_SLOTS) {
        phrase(pools.first, slot, seed)
    } else {
        phrase(pools.second, slot - FIRST_POOL_SLOTS, seed.inv())
    }
}

/**
 * The pool's phrase for [slot]: a walk from a seeded start by a seeded stride coprime with the pool's
 * size. Each step moves, so no phrase comes twice in a row, and the walk visits every phrase before
 * any comes again.
 */
private fun phrase(
    pool: List<String>,
    slot: Long,
    seed: Long,
): String {
    val random = Random(seed)
    val start = random.nextInt(pool.size)
    val strides = (1 until pool.size).filter { greatestCommonDivisor(it, pool.size) == 1 }
    val stride = strides[random.nextInt(strides.size)]
    val index = (start + (slot % pool.size) * stride) % pool.size
    return pool[index.toInt()]
}

/** Euclid's algorithm, which ends in a number of steps logarithmic in its smaller argument. */
private tailrec fun greatestCommonDivisor(
    a: Int,
    b: Int,
): Int = if (b == 0) a else greatestCommonDivisor(b, a % b)
