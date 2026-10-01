package io.tezra.fermix.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Each attempt starts this long after the one before it (design section 5.1). */
const val STAGGER_MS = 250L

/**
 * The longest stagger a race takes: the connect timeout. A longer one starts each attempt after the one
 * before has had its whole connect, which is no race; the bound also keeps the last start, the stagger
 * times at most 15, far inside a Long.
 */
const val MAX_STAGGER_MS = 10_000L

/** How a race ended. */
sealed interface RaceResult<out T> {
    /** [candidate]'s attempt completed first; [value] is its winner, the caller's to close. */
    data class Won<T>(
        val candidate: Candidate,
        val value: T,
    ) : RaceResult<T>

    /** Every attempt failed; each candidate's failure, in the order the race was given. */
    data class AllFailed(
        val failures: Map<Candidate, Exception>,
    ) : RaceResult<Nothing>

    /**
     * [candidate] presented a certificate that is not pinned. The race ended there and no other
     * candidate was tried (design section 13.3, "Wrong machine": no retry).
     */
    data class PinMismatch(
        val candidate: Candidate,
        val failure: TransportException.PinMismatch,
    ) : RaceResult<Nothing>
}

/**
 * Design section 5.1's race. It orders nothing itself: it starts the attempts in the order given,
 * [STAGGER_MS] apart, takes the first to complete, cancels the rest, and reports. An attempt is the
 * caller's, from a candidate to a winner: core-session's WSS open and Noise handshake, so "first
 * complete" is first to finish both. The clock is the caller's coroutine context, so a test runs the
 * race on virtual time.
 */
object CandidateRacer {
    /**
     * Races [ordered], at most [MAX_CANDIDATES], each candidate once, [staggerMs] apart, at most
     * [MAX_STAGGER_MS]. An attempt fails by throwing an Exception, which is that candidate's failure,
     * and a [TransportException.PinMismatch] ends the race at once. An Error is no candidate's
     * failure: it cancels the race and is thrown on. Every winner the caller does not get is closed,
     * however the race ends: one that completes after the race was decided, one handed over just as
     * the race is cancelled, and the decided one when the race is cancelled while its losers unwind. A
     * winner that fails to close leaves no other open, and the race throws that failure; for the one
     * handed over as the race is cancelled, the coroutine's exception handler gets it.
     */
    suspend fun <T : AutoCloseable> race(
        ordered: List<Candidate>,
        staggerMs: Long = STAGGER_MS,
        attempt: suspend (Candidate) -> T,
    ): RaceResult<T> {
        require(ordered.isNotEmpty()) { "a race needs a candidate" }
        require(ordered.size <= MAX_CANDIDATES) { "${ordered.size} candidates is past the wire's $MAX_CANDIDATES" }
        require(ordered.distinct().size == ordered.size) { "a candidate is listed twice" }
        require(staggerMs in 0..MAX_STAGGER_MS) { "a stagger of $staggerMs ms is outside 0 to $MAX_STAGGER_MS ms" }
        // Room for every attempt's outcome, so reporting one never waits. A winner handed to the race's
        // receive just as the race is cancelled never comes out of it; the channel closes that one.
        val settled = Channel<Settled<T>>(ordered.size) { undelivered -> winnerIn(undelivered)?.close() }
        var decided: RaceResult<T>? = null
        var handedOver = false
        // Runs however the race ends. coroutineScope has waited for every attempt by then, so each
        // outcome is in the channel; the decided winner is closed too, unless it reached the caller.
        val leftovers =
            AutoCloseable {
                val stranded = winnerOf(decided)?.takeUnless { handedOver }
                closeAll(winners(settled, ordered.size) + listOfNotNull(stranded))
            }
        return leftovers.use {
            val result = runAttempts(ordered, staggerMs, settled, attempt) { decision -> decided = decision }
            // The late winners are closed before this one is handed over, so if one fails, so is this.
            closeAll(winners(settled, ordered.size))
            handedOver = true
            result
        }
    }

    /**
     * Starts every attempt, takes outcomes until one decides the race and hands it to [onDecided] at
     * once, then cancels the attempts still running. Returns when every attempt has ended.
     */
    private suspend fun <T> runAttempts(
        ordered: List<Candidate>,
        staggerMs: Long,
        settled: Channel<Settled<T>>,
        attempt: suspend (Candidate) -> T,
        onDecided: (RaceResult<T>) -> Unit,
    ): RaceResult<T> =
        coroutineScope {
            val attempts =
                ordered.mapIndexed { index, candidate -> startAttempt(candidate, staggerMs * index, settled, attempt) }
            val result = decide(ordered, settled)
            onDecided(result)
            attempts.forEach { it.cancel() }
            result
        }

    /** Starts [candidate]'s attempt [delayMs] into the race; its outcome goes to [settled]. */
    private fun <T> CoroutineScope.startAttempt(
        candidate: Candidate,
        delayMs: Long,
        settled: SendChannel<Settled<T>>,
        attempt: suspend (Candidate) -> T,
    ): Job =
        launch {
            delay(delayMs)
            settled.trySend(settle(candidate, attempt)).getOrThrow()
        }

    /** Takes outcomes as they come until one decides the race, or every candidate has failed. */
    private suspend fun <T> decide(
        ordered: List<Candidate>,
        settled: ReceiveChannel<Settled<T>>,
    ): RaceResult<T> {
        val failures = HashMap<Candidate, Exception>()
        repeat(ordered.size) {
            when (val next = settled.receive()) {
                is Settled.Decided -> return next.result
                is Settled.Lost -> failures[next.candidate] = next.failure
            }
        }
        return RaceResult.AllFailed(ordered.associateWith { failures.getValue(it) })
    }

    /**
     * Runs one attempt to its outcome. Failing is what an attempt is expected to be able to do, so
     * every Exception it throws is its candidate's outcome, a timeout of its own included. The race's
     * own cancellation is not: ensureActive throws it on.
     */
    private suspend fun <T> settle(
        candidate: Candidate,
        attempt: suspend (Candidate) -> T,
    ): Settled<T> =
        try {
            Settled.Decided(RaceResult.Won(candidate, attempt(candidate)))
        } catch (expectedFailure: Exception) {
            currentCoroutineContext().ensureActive()
            lost(candidate, expectedFailure)
        }

    /** A pin mismatch decides the race against every candidate; any other failure leaves it running. */
    private fun lost(
        candidate: Candidate,
        failure: Exception,
    ): Settled<Nothing> =
        if (failure is TransportException.PinMismatch) {
            Settled.Decided(RaceResult.PinMismatch(candidate, failure))
        } else {
            Settled.Lost(candidate, failure)
        }

    /** The winners among the outcomes left in [settled], at most [count]: attempts no one took. */
    private fun <T : AutoCloseable> winners(
        settled: ReceiveChannel<Settled<T>>,
        count: Int,
    ): List<T> =
        List(count) { settled.tryReceive().getOrNull() }
            .mapNotNull { winnerIn(it) }

    private fun <T : AutoCloseable> winnerIn(outcome: Settled<T>?): T? =
        winnerOf((outcome as? Settled.Decided<T>)?.result)

    private fun <T : AutoCloseable> winnerOf(result: RaceResult<T>?): T? = (result as? RaceResult.Won<T>)?.value

    /**
     * Closes every one of [winners], past any that fails to close: one failure is thrown, the others
     * suppressed under it. The recursion goes one level per winner, so at most [MAX_CANDIDATES] deep.
     */
    private fun closeAll(winners: List<AutoCloseable>) {
        require(winners.size <= MAX_CANDIDATES) { "${winners.size} winners is past the wire's $MAX_CANDIDATES" }
        winners.firstOrNull()?.use { closeAll(winners.drop(1)) }
    }

    /** One attempt's outcome: one that decides the race, or a failure that leaves it running. */
    private sealed interface Settled<out T> {
        class Decided<T>(
            val result: RaceResult<T>,
        ) : Settled<T>

        class Lost(
            val candidate: Candidate,
            val failure: Exception,
        ) : Settled<Nothing>
    }
}
