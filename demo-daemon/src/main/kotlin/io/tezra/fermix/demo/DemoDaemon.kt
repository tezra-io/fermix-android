package io.tezra.fermix.demo

import io.tezra.fermix.session.Dialer
import io.tezra.fermix.session.Link
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.TimeSource

/** The seed of the demo the debug app runs: every key, secret and choice of the demo follows from it. */
const val DEMO_SEED = 51L

/**
 * The most bytes of the owner's uploads the demo keeps, the uploads under way included; past them an upload is
 * refused (`store_quota_exceeded`). They stay in the app's heap for as long as its process lives: a sixth of the
 * 192 MB a Pixel Fold on API 35 grows an app's heap to.
 */
internal const val DEMO_STORE_BYTES = 32L * 1024 * 1024

/**
 * What every connection of the demo shares: its durations, its clocks, the blobs its rows name and the owner's
 * uploads, and its scope, on whose confined dispatcher every coroutine of the demo runs.
 */
internal class DemoParts(
    val times: DemoTimes,
    clock: TimeSource.WithComparableMarks,
    val wallMs: () -> Long,
    val blobs: Map<DemoBlobName, DemoBlob>,
    val scope: CoroutineScope,
) {
    private val started = clock.markNow()

    /** The owner's uploads by their `attach_id`, which a `msg` names (DemoUploads). */
    val attached = mutableMapOf<String, DemoBlob>()

    /** The owner's uploads by digest, and the demo's own blobs, which a row names by ref. */
    val stored: MutableMap<String, DemoBlob> = blobs.values.associateBy { it.ref }.toMutableMap()

    /** Milliseconds since the demo started, on its monotonic clock. */
    fun nowMs(): Long = started.elapsedNow().inWholeMilliseconds

    /** The bytes the owner's uploads hold now. */
    fun storedBytes(): Long = stored.values.sumOf { it.size } - blobs.values.sumOf { it.size }
}

/**
 * A Fermix in memory for the debug app (README, the demo): six daemons behind core-session's own boundary, the
 * [Dialer] a session or a pairing dials, each answering the pairing ceremony, `hello` and the chat as PROTOCOL.md
 * and design section 7 have a daemon answer them, from scripts that a seed, never the clock, decides. It opens no
 * socket: a dial is a [MemoryLink] whose other end the demo reads and writes. It lasts as long as [scope], the
 * process's, and every coroutine it starts is a child of it, on its dispatcher one at a time; a reconnect finds
 * its Fermix as it was. [wallMs] is the wall clock, which only stamps a row's time.
 */
class DemoDaemon(
    seed: Long,
    scope: CoroutineScope,
    times: DemoTimes = DemoTimes(),
    clock: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    wallMs: () -> Long = System::currentTimeMillis,
) {
    /** The demo's Fermixes, in the order its links are offered. */
    val fermixes: List<DemoFermix> = demoFermixes(seed)

    private val confined: CoroutineDispatcher = confinedOf(scope)
    private val parts = DemoParts(times, clock, wallMs, demoBlobs(), scope + confined)
    internal val homes: List<DemoHome> =
        fermixes.map { DemoHome(it, parts.blobs, Random(seed + it.index), wallMs()) }

    init {
        require(scope.coroutineContext[Job]?.isActive == true) { "the demo runs in a scope that has not ended" }
    }

    /**
     * The dialer of the demo Fermix whose link named [port] and [pin], or none when no demo Fermix did: a real
     * daemon's, which the caller dials as it would without the demo.
     */
    fun dialerFor(
        port: Int,
        pin: ByteArray,
    ): Dialer? {
        val home = homes.firstOrNull { port == DEMO_PORT && it.fermix.tlsFingerprint.contentEquals(pin) } ?: return null
        return Dialer { candidate -> dial(home, candidate) }
    }

    /**
     * The pairing link of the next demo Fermix, with a new window open on it: the first that no phone paired with
     * in this run, or the last one again once every one is paired.
     */
    suspend fun nextLink(): String =
        withContext(confined) {
            val home = homes.firstOrNull { it.devices.isEmpty() } ?: homes.last()
            home.fermix.link(home.openWindow(parts.nowMs()))
        }

    /**
     * A socket to [home] over its one route; any other address is one no network answers, as an address of the
     * link's that the daemon does not listen on. While [home] has a pairing window open, one that has not paired
     * and is within its life, the dial may be the pairing's, so it pauses first for Connecting's "Reaching" to show;
     * a dial cannot tell a pairing from a paired phone's reconnect, which a window open on its Fermix slows alike.
     * Any other dial lands at once, as a daemon on the tailnet answers in tens of milliseconds, so a phone back
     * from the background shows no break.
     */
    private suspend fun dial(
        home: DemoHome,
        candidate: Candidate,
    ): Link {
        if (candidate.host != home.fermix.address) {
            throw TransportException.Unreachable(
                IOException("the demo's ${home.fermix.host} is not at ${candidate.host}"),
            )
        }
        val pairing = withContext(confined) { home.windowAt(parts.nowMs(), parts.times.window) != null }
        if (pairing) delay(parts.times.reach)
        val link = MemoryLink()
        parts.scope.launch { DemoConnection(home, link, parts).run() }
        return link
    }
}

/** The dispatcher [scope] runs on, one coroutine at a time: the demo's confinement. */
private fun confinedOf(scope: CoroutineScope): CoroutineDispatcher {
    val dispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
    return requireNotNull(dispatcher) { "the demo's scope names the dispatcher it runs on" }.limitedParallelism(1)
}
