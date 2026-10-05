package io.tezra.fermix.chat

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** How long a caller here waits for a provider, a landing's time made short for a test on real threads. */
private const val CALLER_WAIT_MILLIS = 200L

/** How many callers come at once: more than the landing threads, as shares and pastes may. */
private const val CALLERS = 6

/** A provider's stream whose read waits until it is closed, then throws, as a pipe's does on the phone. */
private class StalledStream : InputStream() {
    private val closed = CountDownLatch(1)

    override fun read(): Int {
        closed.await()
        throw IOException("closed as it was read")
    }

    override fun close() = closed.countDown()
}

/**
 * The phone's landing threads (onLandingThreads) on real threads: however many items land at once, no more than
 * [LANDING_THREADS] provider calls run, each caller goes on once its time passes without waiting for the thread, a call
 * not yet begun then never begins, and the stop it is given frees a thread a stalled stream held.
 */
@Timeout(10, unit = TimeUnit.SECONDS)
class LandingThreadsTest {
    /** A pool wider than the landing threads, as the app's `io` is, which the test shuts down. */
    private val pool = Executors.newFixedThreadPool(CALLERS)
    private val threads = pool.asCoroutineDispatcher().limitedParallelism(LANDING_THREADS)

    @AfterEach
    fun poolDown() {
        pool.shutdownNow()
    }

    @Test
    fun `providers that stall hold no more than the landing threads, and their callers go on once their time passes`() {
        val running = AtomicInteger(0)
        val most = AtomicInteger(0)
        val begun = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val never = CountDownLatch(1)
        val answers =
            runBlocking {
                (1..CALLERS)
                    .map {
                        async {
                            withTimeoutOrNull(CALLER_WAIT_MILLIS) {
                                onLandingThreads(threads, stop = { stops.incrementAndGet() }) {
                                    begun.incrementAndGet()
                                    most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                                    never.await()
                                }
                            }
                        }
                    }.awaitAll()
            }
        assertEquals(List(CALLERS) { null }, answers)
        assertEquals(CALLERS, stops.get())
        never.countDown()
        Thread.sleep(CALLER_WAIT_MILLIS)
        assertEquals(LANDING_THREADS, most.get())
        assertEquals(LANDING_THREADS, begun.get(), "a call whose caller had gone began")
    }

    @Test
    fun `the stop a caller gives closes a stalled stream, so its thread is free for the next landing`() {
        val answer =
            runBlocking {
                repeat(LANDING_THREADS) {
                    val stream = StalledStream()
                    val read =
                        withTimeoutOrNull(CALLER_WAIT_MILLIS) { onLandingThreads(threads, stream::close, stream::read) }
                    assertEquals(null, read)
                }
                withTimeoutOrNull(CALLER_WAIT_MILLIS) { onLandingThreads(threads, stop = {}) { "landed" } }
            }
        assertEquals("landed", answer)
    }

    @Test
    fun `what a provider's call throws reaches its caller, as a call on its own thread would throw it`() {
        val thrown =
            runBlocking {
                runCatching {
                    onLandingThreads(
                        threads,
                        stop = {},
                    ) { throw IllegalStateException("the provider failed") }
                }
            }
        assertTrue(thrown.exceptionOrNull() is IllegalStateException, "$thrown")
    }
}
