package io.tezra.fermix.transport

import io.tezra.fermix.transport.Candidate.Kind.IP
import io.tezra.fermix.transport.Candidate.Scope.LAN
import io.tezra.fermix.transport.Candidate.Scope.TAILNET
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * The race of design section 5.1 on a virtual clock: attempts start 250 ms apart, the first to
 * complete wins and the rest are cancelled, a pin mismatch ends the race at once (section 13.3, "No
 * retry"), and every other failure is reported by candidate.
 */
class CandidateRacerTest {
    private val candidates = (1..4).map { Candidate("100.64.0.$it", TAILNET, IP) }

    @Test
    fun `attempts start 250 ms apart, in order`() =
        runTest {
            val clock = testScheduler.timeSource.markNow()
            val starts = mutableListOf<Long>()
            val result =
                CandidateRacer.race(candidates) {
                    starts += clock.elapsedNow().inWholeMilliseconds
                    throw IOException("refused")
                }
            assertEquals(listOf(0L, 250L, 500L, 750L), starts)
            assertInstanceOf<RaceResult.AllFailed>(result)
        }

    @Test
    fun `the first attempt to complete wins and every other is cancelled or never started`() =
        runTest {
            val clock = testScheduler.timeSource.markNow()
            val started = mutableListOf<Candidate>()
            val cancelled = mutableListOf<Candidate>()
            val result =
                CandidateRacer.race(candidates) { candidate ->
                    started += candidate
                    if (candidate == candidates[1]) {
                        delay(300)
                        return@race Session(candidate)
                    }
                    completesLate(candidate, cancelled)
                }
            val won = result as RaceResult.Won
            assertSame(candidates[1], won.candidate)
            assertFalse(won.value.closed)
            assertEquals(550L, clock.elapsedNow().inWholeMilliseconds)
            assertEquals(candidates.take(3), started)
            assertEquals(listOf(candidates[0], candidates[2]), cancelled)
        }

    @Test
    fun `when every attempt fails, each failure is reported by candidate in the order given`() =
        runTest {
            val failures = candidates.associateWith { IOException("no route to ${it.host}") }
            val result = CandidateRacer.race(candidates.reversed()) { throw failures.getValue(it) }
            val allFailed = assertInstanceOf<RaceResult.AllFailed>(result)
            assertEquals(candidates.reversed(), allFailed.failures.keys.toList())
            candidates.forEach { assertSame(failures[it], allFailed.failures[it]) }
        }

    @Test
    fun `a pin mismatch ends the race at once and no other candidate is tried`() =
        runTest {
            val clock = testScheduler.timeSource.markNow()
            val started = mutableListOf<Candidate>()
            val cancelled = mutableListOf<Candidate>()
            val mismatch = TransportException.PinMismatch(IOException("another leaf"))
            val result =
                CandidateRacer.race(candidates) { candidate ->
                    started += candidate
                    if (candidate == candidates[1]) throw mismatch
                    completesLate(candidate, cancelled)
                }
            val refused = assertInstanceOf<RaceResult.PinMismatch>(result)
            assertSame(candidates[1], refused.candidate)
            assertSame(mismatch, refused.failure)
            assertEquals(250L, clock.elapsedNow().inWholeMilliseconds)
            assertEquals(candidates.take(2), started)
            assertEquals(listOf(candidates[0]), cancelled)
        }

    @Test
    fun `cancelling the race cancels every running attempt`() =
        runTest {
            val cancelled = mutableListOf<Candidate>()
            val race = async { CandidateRacer.race(candidates) { cancellable(it, cancelled) } }
            testScheduler.advanceTimeBy(300.milliseconds)
            testScheduler.runCurrent()
            race.cancel()
            assertThrows<CancellationException> { race.await() }
            assertEquals(candidates.take(2), cancelled)
        }

    @Test
    fun `a winner that completes after the race was decided is closed`() =
        runTest {
            val sessions = mutableListOf<Session>()
            val result =
                CandidateRacer.race(candidates.take(2), staggerMs = 0) { candidate ->
                    delay(100)
                    Session(candidate).also { sessions += it }
                }
            val won = result as RaceResult.Won
            assertEquals(2, sessions.size)
            sessions.forEach { assertEquals(it !== won.value, it.closed) }
        }

    @Test
    fun `a race cancelled after it was decided closes its winner`() =
        runTest {
            val sessions = mutableListOf<Session>()
            val race =
                async {
                    CandidateRacer.race(candidates.take(2), staggerMs = 0) { candidate ->
                        if (candidate == candidates[1]) unwindingSlowly()
                        delay(100)
                        Session(candidate).also { sessions += it }
                    }
                }
            // Decided at 100 ms; the loser takes until 150 ms to unwind, and the race is cancelled between.
            testScheduler.advanceTimeBy(120.milliseconds)
            testScheduler.runCurrent()
            race.cancel()
            assertThrows<CancellationException> { race.await() }
            assertEquals(1, sessions.size)
            assertTrue(sessions.single().closed, "the decided winner was left open")
        }

    @Test
    fun `a winner handed to the race as it is cancelled is closed`() =
        runTest {
            val sessions = mutableListOf<Session>()
            lateinit var race: Deferred<RaceResult<Session>>
            race =
                async {
                    CandidateRacer.race(candidates.take(2), staggerMs = 0) { candidate ->
                        delay(100)
                        // The second attempt runs after the first has handed the race its winner, and
                        // before the race has taken it.
                        if (candidate == candidates[1]) cancelAndWait(race)
                        Session(candidate).also { sessions += it }
                    }
                }
            assertThrows<CancellationException> { race.await() }
            assertEquals(1, sessions.size)
            assertTrue(sessions.single().closed, "the winner the race was handed was left open")
        }

    @Test
    fun `a winner that fails to close leaves no other winner open, and the race throws its failure`() =
        runTest {
            val sessions = mutableListOf<Session>()
            val failure =
                assertThrows<IllegalStateException> {
                    CandidateRacer.race(candidates.take(3), staggerMs = 0) { candidate ->
                        delay(100)
                        Session(candidate, failsToClose = candidate == candidates[1]).also { sessions += it }
                    }
                }
            assertEquals("${candidates[1].host} failed to close", failure.message)
            assertEquals(3, sessions.size)
            sessions.forEach { assertTrue(it.closed, "${it.candidate.host} was left open") }
        }

    @Test
    fun `an attempt's own timeout is its failure, not the race's cancellation`() =
        runTest {
            val result = CandidateRacer.race(candidates.take(1)) { withTimeout(100) { awaitCancellation() } }
            val allFailed = assertInstanceOf<RaceResult.AllFailed>(result)
            assertInstanceOf<CancellationException>(allFailed.failures.getValue(candidates[0]))
        }

    @Test
    fun `an error is not a candidate's failure and ends the race`() =
        runTest {
            assertThrows<AssertionError> { CandidateRacer.race(candidates.take(1)) { throw AssertionError("a bug") } }
        }

    @Test
    fun `an empty, oversized or repeated list, or a stagger outside 0 to 10 s, is refused`() =
        runTest {
            val seventeen = (1..17).map { Candidate("192.168.1.$it", LAN, IP) }
            assertThrows<IllegalArgumentException> { CandidateRacer.race(emptyList()) { Session(it) } }
            assertThrows<IllegalArgumentException> { CandidateRacer.race(seventeen) { Session(it) } }
            assertThrows<IllegalArgumentException> { CandidateRacer.race(candidates + candidates[0]) { Session(it) } }
            assertThrows<IllegalArgumentException> { CandidateRacer.race(candidates, staggerMs = -1) { Session(it) } }
            // A stagger past the bound is no race, and a large one times an index wraps to a negative delay.
            assertThrows<IllegalArgumentException> {
                CandidateRacer.race(candidates, staggerMs = MAX_STAGGER_MS + 1) { Session(it) }
            }
            assertThrows<IllegalArgumentException> {
                CandidateRacer.race(candidates, staggerMs = Long.MAX_VALUE) { Session(it) }
            }
            val longest = CandidateRacer.race(candidates.take(1), staggerMs = MAX_STAGGER_MS) { Session(it) }
            assertInstanceOf<RaceResult.Won<Session>>(longest)
        }

    /** An attempt that never completes and records its cancellation. */
    private suspend fun cancellable(
        candidate: Candidate,
        cancelled: MutableList<Candidate>,
    ): Session =
        try {
            awaitCancellation()
        } finally {
            cancelled += candidate
        }

    /**
     * An attempt that completes [LATE_MS] after it starts, unless it is cancelled first, which it records.
     * A race that failed to cancel it would end later, on its winner, rather than wait forever.
     */
    private suspend fun completesLate(
        candidate: Candidate,
        cancelled: MutableList<Candidate>,
    ): Session =
        try {
            delay(LATE_MS)
            Session(candidate)
        } catch (cancellation: CancellationException) {
            cancelled += candidate
            throw cancellation
        }

    /** Cancels [race] from inside one of its attempts, and waits for that cancellation to arrive. */
    private suspend fun cancelAndWait(race: Deferred<*>): Nothing {
        race.cancel()
        awaitCancellation()
    }

    /** An attempt that never completes, and takes 50 ms to unwind once it is cancelled. */
    private suspend fun unwindingSlowly(): Nothing =
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { delay(50) }
        }

    /** What a winning attempt holds: closed when it lost a race it had completed. */
    private class Session(
        val candidate: Candidate,
        private val failsToClose: Boolean = false,
    ) : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
            check(!failsToClose) { "${candidate.host} failed to close" }
        }
    }

    private companion object {
        /** Well after every other attempt in a test has started and the race has been decided. */
        const val LATE_MS = 1_000L
    }
}
