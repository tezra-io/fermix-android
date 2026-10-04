package io.tezra.fermix

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.attest.AttestedKey
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.data.InstanceGone
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.instanceDataStore
import io.tezra.fermix.instance.TestOutcome
import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.session.Announcement
import io.tezra.fermix.session.Announcer
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import io.tezra.fermix.transport.WebSocketConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

private val LAN = Candidate("192.168.1.24", Candidate.Scope.LAN, Candidate.Kind.IP)
private val TAILNET = Candidate("100.101.42.7", Candidate.Scope.TAILNET, Candidate.Kind.IP)

/** The Keystore as the opener asks it: the aliases it [holds], and each alias it was [asked] for. */
private class FakeKeys(
    private val holds: Set<String>,
) : DeviceKeyFacade {
    val asked = mutableListOf<String>()

    override fun generate(
        alias: String,
        challenge: ByteArray,
    ): AttestedKey = error("the opener makes no key")

    override fun delete(alias: String) = error("the opener deletes no key")

    override fun exists(alias: String): Boolean = alias in holds

    override fun staticKey(alias: String): StaticKey {
        asked += alias
        check(alias in holds) { "the Keystore holds no key under $alias" }
        return HeldKey
    }
}

private object HeldKey : StaticKey {
    override val publicKey: ByteArray get() = ByteArray(32) { 8 }

    override fun agree(peerPublicKey: ByteArray): ByteArray = error("these sessions never handshake")
}

/** A socket a test's race hands back, which says whether it was closed. */
private class Socket : AutoCloseable {
    var closed = false

    override fun close() {
        closed = true
    }
}

/**
 * How the app opens a session and tests a connection (AppSessions): each instance's session with its own
 * Keystore key and none without a key or a route, racing first the candidate its last `hello` went over, and
 * pulling in full once FCM dropped pushes for the phone; and
 * "Test connection"'s race (design section 13.7), which names a candidate only for its own attempt's failure,
 * never for the winner's cancel.
 *
 * On Robolectric, as MainActivityTest: the opener opens the instance's database, and the app's tests load
 * the bundled SQLite library once for the JVM, into Robolectric's classloader. The plain Application stands
 * in for the app's, whose services these tests do not need.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppSessionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val databases by lazy { ProfileDatabases(ApplicationProvider.getApplicationContext(), folder.root) }

    private fun opener(keys: DeviceKeyFacade) =
        AppSessions(
            databases,
            keys,
            WebSocketConnector(),
            MutableStateFlow(NetworkFacts.NONE),
            announcer = { _, _ -> Announcer { Announcement.NOT_ANNOUNCED } },
        ) { "0.1.0" }

    @Test
    fun `a session opens with its record's own key alias, and none without the key or a route`() =
        runTest {
            val paired = record(1)
            val keys = FakeKeys(holds = setOf(paired.keyAlias))
            val opener = opener(keys)
            opener.open(paired, backgroundScope).close()
            assertEquals(listOf(paired.keyAlias), keys.asked)

            val lost = record(2)
            assertThrows(SessionUnavailable::class.java) { opener.open(lost, backgroundScope) }
            assertEquals(listOf(paired.keyAlias, lost.keyAlias), keys.asked)
            val routeless = paired.copy(candidates = emptyList())
            assertThrows(SessionUnavailable::class.java) { opener.open(routeless, backgroundScope) }
        }

    @Test
    fun `a record whose pushes FCM dropped opens a session that pulls its history in full, and only such a record`() =
        runTest {
            val paired = record(1)
            val opener = opener(FakeKeys(holds = setOf(paired.keyAlias)))
            assertTrue(opener.sessionParts(paired.copy(historyPullDue = true)).fullPull)
            assertFalse(opener.sessionParts(paired).fullPull)
        }

    @Test
    fun `the candidate the last hello went over is kept on the record, and the next process races it first`() =
        runTest {
            val file = File(folder.root, "instances.json")
            val process = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val store = InstanceStore(instanceDataStore(file, process), databases)
            val paired = record(1).copy(candidates = listOf(TAILNET, LAN))
            store.upsert(paired)
            SessionEvents(
                store,
                databases,
                ChatFolds(TestClock) {
                    0uL
                },
                NoAlerts,
                testNotifications(databases),
                quietRegistrations(store),
            ).reached(paired.id, LAN)
            // The process ends, and the next one reads the records from their file.
            checkNotNull(process.coroutineContext[Job]).cancelAndJoin()
            val kept = InstanceStore(instanceDataStore(file, backgroundScope), databases).instances.first().single()
            assertEquals(LAN, kept.lastCandidate)
            val session = opener(FakeKeys(holds = setOf(kept.keyAlias))).open(kept, backgroundScope)
            assertEquals(LAN, session.lastSuccessful.value)
            session.close()
        }

    @Test
    fun `a removed instance has no session, and its files are not made again`() =
        runTest {
            val removed = record(1)
            val opener = opener(FakeKeys(holds = setOf(removed.keyAlias)))
            databases.open(removed.id, MAIN_PROFILE)
            databases.delete(removed.id)
            val refusal = assertThrows(SessionUnavailable::class.java) { opener.open(removed, backgroundScope) }
            assertTrue(refusal.cause is InstanceGone)
            assertFalse(File(folder.root, removed.id).exists())
        }

    @Test
    fun `a test names no candidate the winner cancelled, and closes the winner's socket`() =
        runTest {
            val won = Socket()
            // The tailnet address goes first and hangs until the LAN one, tried 250 ms on, answers.
            val outcome = raceOnce(listOf(LAN, TAILNET)) { if (it == TAILNET) awaitCancellation() else won }
            val reached = outcome as TestOutcome.Reached
            assertEquals(LAN, reached.candidate)
            assertEquals(emptySet<Candidate>(), reached.failed)
            assertTrue(won.closed)
        }

    @Test
    fun `a test names the candidate whose own attempt failed`() =
        runTest {
            val outcome =
                raceOnce(listOf(LAN, TAILNET)) {
                    if (it ==
                        TAILNET
                    ) {
                        throw IOException("refused")
                    } else {
                        Socket()
                    }
                }
            val reached = outcome as TestOutcome.Reached
            assertEquals(LAN, reached.candidate)
            assertEquals(setOf(TAILNET), reached.failed)
        }

    @Test
    fun `a test that hears from no candidate in time reaches none, and blames none`() =
        runTest {
            val outcome = raceOnce<Socket>(listOf(LAN, TAILNET)) { awaitCancellation() }
            assertEquals(TestOutcome.NotReached(failed = emptySet()), outcome)
            assertEquals(TEST_TIMEOUT_MILLIS, currentTime)
        }
}
