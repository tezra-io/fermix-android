package io.tezra.fermix

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.session.Session
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the app's services hand the screens (AppServices): "Unpair…" on the Chats list and "Unpair from
 * {host}…" on the Instance screen ask the daemon to forget the phone (design section 13.7), and a trust
 * screen's "Remove", which the daemon has done already, does not.
 *
 * On Robolectric, for the services' files; the plain Application stands in for the app's, so these services
 * are the only ones, and they are never started, so no session opens but those the test hands over.
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

            val listed = held()
            services.chatsParts().unpair(id)
            val detailed = held()
            services.instanceParts().unpair(id)
            held()
            services.chatsParts().remove(id)
            assertEquals(listOf(listed, detailed), asked)
        }
}
