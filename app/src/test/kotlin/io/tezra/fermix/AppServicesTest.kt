package io.tezra.fermix

import android.app.Application
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager
import kotlin.coroutines.CoroutineContext

/** How long, in real time, a closed store may take to refuse a write; a write it holds instead is never refused. */
private const val REFUSAL_MILLIS = 10_000L

/**
 * A main thread that runs nothing it is handed, as Robolectric's runs nothing its test left it once the test has
 * ended; [handed] counts what it was handed.
 */
private class StalledMain : CoroutineDispatcher() {
    val handed = MutableStateFlow(0)

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        handed.update { it + 1 }
    }
}

/**
 * What [write] threw within [REFUSAL_MILLIS] of real time, which [realTime] keeps, where the test's own dispatcher
 * would skip the wait, or the timeout's own exception when it was held.
 */
private suspend fun thrownBy(
    realTime: CoroutineDispatcher = Dispatchers.Default,
    write: suspend () -> Unit,
): Throwable? =
    withContext(realTime) {
        runCatching { withTimeout(REFUSAL_MILLIS) { write() } }.exceptionOrNull()
    }

/** Whether [thrown] is the refusal of a closed store: a cancellation, and not the one a held write times out with. */
private fun refusal(thrown: Throwable?): Boolean =
    thrown is CancellationException && thrown !is TimeoutCancellationException

/**
 * What the app's services hand the screens (AppServices): "Unpair…" on the Chats list and "Unpair from
 * {host}…" on the Instance screen ask the daemon to forget the phone (design section 13.7), and a trust
 * screen's "Remove", which the daemon has done already, does not. And how they end: started or not, at the
 * application's end, and when the system will not release the network watch.
 *
 * On Robolectric, for the services' files; the plain Application stands in for the app's, so a test's services are
 * the only ones, except in the test of the app's own application, whose services are its. The first test's are never
 * started, so no session opens but those it hands over; the others' are started to be closed, which ends their stores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppServicesTest {
    @Test
    fun `Unpair asks the daemon to forget the phone from the list and the Instance screen, and Remove does not`() =
        runTest {
            val asked = mutableListOf<Session>()
            // No connection is up, so `unpair` never goes out and nothing waits for the daemon's close.
            val noneUp: suspend (Session) -> Boolean = { session ->
                asked += session
                false
            }
            val services = AppServices(ApplicationProvider.getApplicationContext(), sendUnpair = noneUp)
            val id = record(1).id

            suspend fun held(): Session =
                idleSession(backgroundScope).also { services.supervisor.adopt(id, it) { null } }

            try {
                val listed = held()
                services.chatsParts().unpair(id)
                val detailed = held()
                services.instanceParts().unpair(id)
                held()
                services.chatsParts().remove(id)
                assertEquals(listOf(listed, detailed), asked)
            } finally {
                // Services never started end all the same.
                services.close()
            }
        }

    @Test
    @Config(application = FermixApplication::class)
    fun `the app's application ends its services as it ends, and an end that comes again does nothing`() =
        runTest {
            val app: FermixApplication = ApplicationProvider.getApplicationContext()
            app.onTerminate()
            // Robolectric ends the application again as the test ends, as this does first.
            app.onTerminate()
            val refused = thrownBy { app.services.instances.upsert(record(1)) }
            assertTrue("$refused", refusal(refused))
            val unset = thrownBy { app.services.settings.setAppLock(true) }
            assertTrue("$unset", refusal(unset))
        }

    @Test
    fun `services whose network watch the system will not release still end their stores, and say why`() =
        runTest {
            val context: Application = ApplicationProvider.getApplicationContext()
            val services = AppServices(context)
            services.start()
            // The system forgets the watch's callbacks, and refuses to release one it never had, as a phone does.
            ShadowConnectivityManager.reset()
            shadowOf(context.getSystemService(ConnectivityManager::class.java)).setStrictUnregistration(true)
            assertThrows(IllegalArgumentException::class.java) { services.close() }
            val refused = thrownBy { services.instances.upsert(record(1)) }
            assertTrue("$refused", refusal(refused))
            val unset = thrownBy { services.settings.setAppLock(true) }
            assertTrue("$unset", refusal(unset))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `services closed refuse a write while the app lock's setting still waits for the main thread`() =
        runTest {
            // The setting handed to the lock gate waits for good on a main thread that runs nothing, and the
            // services' scope, which it runs in, never ends; the stores, in a scope of their own, end all the same.
            val main = StalledMain()
            Dispatchers.setMain(main)
            try {
                val services = AppServices(ApplicationProvider.getApplicationContext())
                services.start()
                main.handed.first { it > 0 }
                services.close()
                val refused = thrownBy { services.instances.upsert(record(1)) }
                assertTrue("$refused", refusal(refused))
                val unset = thrownBy { services.settings.setAppLock(true) }
                assertTrue("$unset", refusal(unset))
            } finally {
                Dispatchers.resetMain()
            }
        }
}
