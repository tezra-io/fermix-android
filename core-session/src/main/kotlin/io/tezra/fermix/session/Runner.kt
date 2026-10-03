package io.tezra.fermix.session

import io.tezra.fermix.transport.Backoff
import io.tezra.fermix.transport.LinkStatus
import io.tezra.fermix.transport.NetworkFacts
import io.tezra.fermix.transport.UnreachableTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The races one run makes before it stops as [SessionState.Suspended] and waits for [Session.resume]:
 * at the backoff's 30 s, a day of trying under "Can't reach", or 120 days of hourly connections. The
 * backoff itself never stops (core-transport); this bounds the loop.
 */
internal const val MAX_RACES = 2_880

/**
 * How long a reconnect after the hourly close stays silent: the link the UI shows as up is up again
 * within it, or the UI is told (design section 13.5, "the dot is never optimistic").
 */
internal const val SILENT_GRACE_MS = 2_000L

/**
 * The session's life: one run at a time of the reconnect loop, which [pause] and [close] stop and
 * [resume] starts again. A fault of the phone's own, its store or its announcer failing, ends the
 * session as [SessionState.Failed] with that fault; nothing else does but a close code, a refusal or
 * [close]. Each call publishes its state before it stops the run, so one made from inside the run, an
 * announcer's, still lands while the run unwinds.
 */
internal class Runner(
    private val core: SessionCore,
    private val requests: Requests,
    private val scope: CoroutineScope,
) {
    private val lifecycle = Mutex()
    private var job: Job? = null

    /** How many of the app's calls that touch the store run now ([request]); [close] returns once none does. */
    private val requestsRunning = MutableStateFlow(0)

    fun start() {
        job = scope.launch { run() }
    }

    /**
     * [call], one of the app's that touches the store, which runs in its caller's coroutine, so no stop of the
     * run reaches it: it is counted until it returns or throws, and [close] waits for it.
     */
    suspend fun <T> request(call: suspend () -> T): T {
        requestsRunning.update { it + 1 }
        try {
            return call()
        } finally {
            requestsRunning.update { it - 1 }
        }
    }

    suspend fun pause() {
        lifecycle.withLock {
            core.publish(SessionState.Suspended)
            stop()
        }
    }

    suspend fun resume() {
        lifecycle.withLock {
            if (job?.isActive != true && core.state.value !is SessionState.Ended) start()
        }
    }

    /**
     * Ends the session and returns once its run has stopped and every request that came before the end has
     * returned, each as it would have: a request after it is refused (SessionCore.requireOpen). From then on
     * nothing of this session's touches the store, which may be another session's, or its files gone.
     */
    suspend fun close() {
        lifecycle.withLock {
            core.end(SessionState.Closed)
            stop()
            requestsRunning.first { it == 0 }
        }
    }

    private suspend fun stop() {
        val running = job
        job = null
        running?.cancelAndJoin()
    }

    /**
     * A run that was stopped ends here, whatever it threw last. A CancellationException while the run is
     * still active came from the store or the announcer, and is a fault like any other.
     */
    private suspend fun run() {
        val failure = runCatching { Reconnects(core, requests).run() }.exceptionOrNull() ?: return
        currentCoroutineContext().ensureActive()
        if (failure !is Exception) throw failure
        core.log(DiagnosticKind.FAILED, failure.toString())
        core.end(SessionState.Failed(failure))
    }
}

/**
 * One run of the reconnect loop (design section 5.1): race, hello, serve, and after each ending what
 * the close code says, at once, after the backoff's wait, or never again. The "Can't reach" clock is
 * core-transport's tracker; the hourly `1000` close reconnects without a word to the UI.
 */
private class Reconnects(
    private val core: SessionCore,
    requests: Requests,
) {
    private val connector = Connector(core, requests)
    private var tracker = UnreachableTracker()
    private var backoff = Backoff(jitter = core.parts.random)

    suspend fun run() {
        // The cursors hello carries are read before the first race.
        core.timeline()
        var silent = false
        repeat(MAX_RACES) {
            awaitNetwork()
            if (!silent) publishLink()
            val attempt = if (silent) silently() else connector.attempt()
            track(attempt)
            val next = attempt.next
            if (next is Next.Stop) return core.end(next.state)
            if (attempt.connected) backoff = backoff.reset()
            // A silent race that failed, or a live connection that ended into a wait (the daemon's 1002 among
            // them), shows the link as it is before the wait, never a stale Connected (design section 13.5).
            if (silent && !attempt.connected) publishLink()
            if (attempt.connected && next == Next.Backoff) publishLink()
            if (next == Next.Backoff) waitBackoff()
            silent = attempt.silent
        }
        core.log(DiagnosticKind.BOUND, "$MAX_RACES races in one run; resume() races again")
        core.publish(SessionState.Suspended)
    }

    /** An attempt after the hourly close: the UI hears of it only if no link is up within the grace. */
    private suspend fun silently(): Attempt =
        coroutineScope {
            val grace =
                launch {
                    delay(SILENT_GRACE_MS)
                    if (core.live == null) publishLink()
                }
            connector.attempt().also { grace.cancel() }
        }

    private fun track(attempt: Attempt) {
        val reached = attempt.reached
        tracker = if (reached != null) tracker.connected(reached).lost() else tracker.allFailed(core.now())
    }

    private suspend fun awaitNetwork() {
        val network = core.parts.network
        tracker = tracker.network(network.value)
        if (network.value.hasNetwork) return
        core.publish(SessionState.WaitingForNetwork)
        tracker = tracker.network(network.first { it.hasNetwork })
    }

    private fun publishLink() {
        val state =
            when (tracker.status(core.now())) {
                LinkStatus.WaitingForNetwork -> SessionState.WaitingForNetwork
                LinkStatus.CannotReach -> SessionState.CannotReach
                LinkStatus.Connecting, is LinkStatus.Connected -> SessionState.Connecting
            }
        core.publish(state)
    }

    /**
     * The backoff's wait, cut short by a change of network, which is a reason to race at once. "Can't
     * reach" shows when its 30 s run out inside the wait.
     */
    private suspend fun waitBackoff() {
        val until = core.now() + backoff.delayMs()
        backoff = backoff.next()
        val from = core.parts.network.value
        val cannotReachAt = tracker.cannotReachAtMs
        if (cannotReachAt != null && cannotReachAt < until) {
            if (networkChanged(from, cannotReachAt - core.now())) return
            publishLink()
        }
        networkChanged(from, until - core.now())
    }

    private suspend fun networkChanged(
        from: NetworkFacts,
        withinMs: Long,
    ): Boolean = withTimeoutOrNull(withinMs.coerceAtLeast(0)) { core.parts.network.first { it != from } } != null
}
