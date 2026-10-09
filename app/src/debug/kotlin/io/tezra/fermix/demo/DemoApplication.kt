package io.tezra.fermix.demo

import android.util.Log
import io.tezra.fermix.AppServices
import io.tezra.fermix.FermixApplication
import io.tezra.fermix.session.Dialer
import io.tezra.fermix.transport.TransportException
import io.tezra.fermix.webSocketDialers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException

private const val TAG = "FermixDemo"

/**
 * The debug app's process (README, "The demo"): the app as it is, whose every daemon is dialled as [dialerFor]
 * says. A daemon of the demo's own pins is the demo, once its launcher entry (DemoEntry) started it in this process
 * ([startDemo]), and before that one no network answers, so no socket is ever opened to a demo address; every other
 * daemon is its pinned WebSocket, as in the release. Which it is is decided at each dial, not when the dialer is
 * made: a session keeps the dialer it opened with for its life, so one opened before the entry reaches the demo at
 * its next dial after it. The demo lives in [scope], the process's, and ends with it.
 */
class DemoApplication : FermixApplication() {
    private val scope = demoScope()
    private val real = webSocketDialers()

    /** The pins the demo's Fermixes name, digests alone: the same every run, as the seed is (demoPins). */
    private val demoPins: List<ByteArray> = demoPins(DEMO_SEED)

    /** A demo pin's dialer while the demo is not running: no network answers it. */
    private val notStarted =
        Dialer { throw TransportException.Unreachable(IOException("the demo runs once its launcher entry starts it")) }

    @Volatile
    private var running: DemoDaemon? = null

    override fun makeServices(): AppServices = AppServices(this, dialerFor = ::dialerFor)

    /** The dialer every session, pairing and connection test of the app dials a daemon of [port] and [pin] with. */
    fun dialerFor(
        port: Int,
        pin: ByteArray,
    ): Dialer {
        val ours = port == DEMO_PORT && demoPins.any { it.contentEquals(pin) }
        if (!ours) return real(port, pin)
        val kept = pin.copyOf()
        return Dialer { candidate -> demoDialer(port, kept).dial(candidate) }
    }

    /** The demo's dialer for a demo pin as this dial finds it: the demo once started, unreachable before. */
    private fun demoDialer(
        port: Int,
        pin: ByteArray,
    ): Dialer {
        val demo = running ?: return notStarted
        return checkNotNull(demo.dialerFor(port, pin)) { "the demo names a pin it does not answer" }
    }

    /** The demo for the rest of this process, started on the first call: DemoEntry's. */
    fun startDemo(): DemoDaemon =
        synchronized(this) {
            running ?: DemoDaemon(DEMO_SEED, scope).also { running = it }
        }

    /** The process ends, as only an emulated one says (FermixApplication.onTerminate): the demo ends with it. */
    override fun onTerminate() {
        scope.cancel()
        super.onTerminate()
    }
}

/** The demo's scope, on [dispatcher]: one coroutine's failure is logged and ends neither the others nor the demo. */
private fun demoScope(dispatcher: CoroutineDispatcher = Dispatchers.Default): CoroutineScope =
    CoroutineScope(
        SupervisorJob() + dispatcher +
            CoroutineExceptionHandler { _, fault -> Log.e(TAG, "the demo failed", fault) },
    )
