package io.tezra.fermix

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.push.REGISTRATION_MAX_AGE_MS
import io.tezra.fermix.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

private const val TOKEN = "fcm-token-of-this-phone"
private const val T0 = 1_790_000_000_000L

/**
 * This phone's push registration (design section 10, "Registration"), over sessions that never connect,
 * whose sends are recorded: `push_register` once a connection has reconciled, while notifications can
 * show, renewed after 7 days and on a new token; `push_unregister` once they cannot (the permission taken
 * or the channel blocked, seen on coming into sight, or the switch turned off), which a new token does not
 * cancel; the token never in a record.
 *
 * On Robolectric, for the records' file and Android's notification manager, whose answers decide whether
 * notifications can show, as the app's services read them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PushRegistrationsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val daemon = PairedDaemon(1, "studio").record

    private var nowMs = T0
    private var token: String? = TOKEN
    private val sent = mutableListOf<String>()
    private val logged = mutableListOf<String>()

    private class Rig(
        val store: InstanceStore,
        val sessions: MutableStateFlow<Map<String, Session>>,
        val registrations: PushRegistrations,
    )

    private suspend fun TestScope.rig(record: Instance = daemon): Rig {
        val databases = ProfileDatabases(context, File(folder.root, "profiles"))
        val store = InstanceStore(File(folder.root, "instances.json"), backgroundScope, databases)
        store.upsert(record)
        val sessions = MutableStateFlow(mapOf(record.id to idleSession(backgroundScope)))
        val posted = PostedNotifications(context)
        val parts =
            RegistrationParts(
                store,
                { sessions.value },
                canShow = { posted.canShow(conversationId(it.id, MAIN_PROFILE)) },
                token = { token },
                now = { nowMs },
                log = { message, _ -> logged += message },
                register = { _, token -> sent.add("register $token") },
                unregister = { sent.add("unregister") },
            )
        return Rig(store, sessions, PushRegistrations(parts))
    }

    private suspend fun Rig.reconciled(pulledInFull: Boolean = false) =
        registrations.reconciled(daemon.id, checkNotNull(sessions.value[daemon.id]), pulledInFull)

    private suspend fun Rig.record(): Instance =
        store.instances
            .first()
            .single()

    private suspend fun Rig.registeredAt(): Long? =
        store.instances
            .first()
            .single()
            .fcmRegisteredAt

    @Test
    fun `a reconciled connection registers once, and the record keeps the time and never the token`() =
        runTest {
            val rig = rig()
            rig.reconciled()
            nowMs += 1_000L
            rig.reconciled()
            assertEquals(listOf("register $TOKEN"), sent)
            assertEquals(T0, rig.registeredAt())
            assertFalse(
                TOKEN in
                    rig.store.instances
                        .first()
                        .toString(),
            )
        }

    @Test
    fun `without POST_NOTIFICATIONS, or with the instance's channel blocked, nothing is registered`() =
        runTest {
            val rig = rig()
            shadowOf(manager).setNotificationsEnabled(false)
            rig.reconciled()
            shadowOf(manager).setNotificationsEnabled(true)
            val blocked =
                NotificationChannel(
                    conversationId(daemon.id, MAIN_PROFILE),
                    "studio",
                    NotificationManager.IMPORTANCE_NONE,
                )
            manager.createNotificationChannel(blocked)
            rig.reconciled()
            assertEquals(emptyList<String>(), sent)
            assertNull(rig.registeredAt())
        }

    @Test
    fun `a permission taken while away unregisters as the app comes into sight, and its time goes`() =
        runTest {
            val rig = rig()
            rig.reconciled()
            shadowOf(manager).setNotificationsEnabled(false)
            rig.registrations.refresh()
            rig.registrations.refresh()
            assertEquals(listOf("register $TOKEN", "unregister"), sent)
            assertNull(rig.registeredAt())
        }

    @Test
    fun `a registration 7 days old is sent again at the next connection`() =
        runTest {
            val rig = rig()
            rig.reconciled()
            nowMs = T0 + REGISTRATION_MAX_AGE_MS - 1
            rig.reconciled()
            nowMs = T0 + REGISTRATION_MAX_AGE_MS
            rig.reconciled()
            assertEquals(listOf("register $TOKEN", "register $TOKEN"), sent)
            assertEquals(T0 + REGISTRATION_MAX_AGE_MS, rig.registeredAt())
        }

    @Test
    fun `a new token clears every record's time and registers each live connection with it`() =
        runTest {
            val rig = rig()
            rig.reconciled()
            token = "fcm-token-rotated"
            rig.sessions.value = emptyMap()
            rig.registrations.newToken()
            assertNull(rig.registeredAt())
            assertEquals(listOf("register $TOKEN"), sent)

            rig.sessions.value = mapOf(daemon.id to idleSession(backgroundScope))
            rig.registrations.newToken()
            assertEquals(listOf("register $TOKEN", "register fcm-token-rotated"), sent)
            assertEquals(T0, rig.registeredAt())
        }

    @Test
    fun `a new token leaves the time of a daemon still owed its push_unregister, which goes at its next connection`() =
        runTest {
            val rig = rig()
            rig.reconciled()
            // The switch goes off with no connection up, so the unregister waits for the next.
            rig.sessions.value = emptyMap()
            rig.store.changed(daemon.id) { it.copy(notificationsEnabled = false) }
            rig.registrations.apply(rig.record(), on = false)
            token = "fcm-token-rotated"
            rig.registrations.newToken()
            assertEquals(T0, rig.registeredAt())

            rig.sessions.value = mapOf(daemon.id to idleSession(backgroundScope))
            rig.reconciled()
            assertEquals(listOf("register $TOKEN", "unregister"), sent)
            assertNull(rig.registeredAt())
        }

    @Test
    fun `the Notifications switch registers and unregisters at once over a live connection`() =
        runTest {
            val rig = rig(daemon.copy(notificationsEnabled = false))
            rig.store.changed(daemon.id) { it.copy(notificationsEnabled = true) }
            rig.registrations.apply(
                rig.store.instances
                    .first()
                    .single(),
                on = true,
            )
            rig.store.changed(daemon.id) { it.copy(notificationsEnabled = false) }
            rig.registrations.apply(
                rig.store.instances
                    .first()
                    .single(),
                on = false,
            )
            assertEquals(listOf("register $TOKEN", "unregister"), sent)
            assertNull(rig.registeredAt())
        }

    @Test
    fun `with no token yet nothing is sent until FCM answers, and a daemon not pushing through FCM is left alone`() =
        runTest {
            val rig = rig()
            token = null
            rig.reconciled()
            assertEquals(listOf("no push token for ${daemon.id} yet: FCM is asked, and its answer registers"), logged)
            assertNull(rig.registeredAt())
            token = TOKEN
            rig.registrations.refresh()
            assertEquals(listOf("register $TOKEN"), sent)
            sent.clear()

            rig.store.changed(daemon.id) { it.copy(fcmRegisteredAt = null, pushPlatforms = emptyList()) }
            rig.reconciled()
            assertEquals(emptyList<String>(), sent)
        }

    @Test
    fun `the full pull FCM's dropped messages asked for is done by the session opened to make it, and only by it`() =
        runTest {
            val rig = rig(daemon.copy(historyPullDue = true))
            // A session opened before the flag was set pulls as always, so the flag waits for the next.
            rig.reconciled(pulledInFull = false)
            assertTrue(rig.record().historyPullDue)
            rig.reconciled(pulledInFull = true)
            assertFalse(rig.record().historyPullDue)
        }

    @Test
    fun `a quick double toggle acts on the switch as the record was read back, and throws nothing`() =
        runTest {
            val rig = rig()
            rig.reconciled()
            // The first tap's call reads the record after the second tap wrote it off.
            rig.store.changed(daemon.id) { it.copy(notificationsEnabled = false) }
            rig.registrations.apply(rig.record(), on = true)
            assertEquals(listOf("register $TOKEN", "unregister"), sent)
            assertNull(rig.registeredAt())
        }
}
