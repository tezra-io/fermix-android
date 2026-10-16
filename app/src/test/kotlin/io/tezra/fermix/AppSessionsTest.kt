package io.tezra.fermix

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.attest.AttestedKey
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceGone
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.MAIN_PROFILE
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.instance.TestOutcome
import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.protocol.PairingLink
import io.tezra.fermix.session.Announcement
import io.tezra.fermix.session.Announcer
import io.tezra.fermix.session.Dialer
import io.tezra.fermix.transport.Candidate
import io.tezra.fermix.transport.NetworkFacts
import io.tezra.fermix.transport.TransportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
import java.net.URLEncoder
import java.util.Base64

private val LAN = Candidate.Host("192.168.1.24", Candidate.Scope.LAN, Candidate.Kind.IP)
private val TAILNET = Candidate.Host("100.101.42.7", Candidate.Scope.TAILNET, Candidate.Kind.IP)

/** A daemon's iroh route (M62). */
private val ROUTE =
    Candidate.Route(
        "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664",
        listOf(Candidate.Relay("relay-a.example.net", 7842)),
        "374b4c8f103627f7",
    )

/** A session's `hello` revision where the test needs none. */
private val NO_REVISION: suspend () -> String? = { null }

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

/** A version-2 pairing link of [record]'s daemon, as its pairing window writes it, form-encoded. */
private fun pairingLinkText(record: Instance): String {
    val parameters =
        listOf(
            "v" to "2",
            "candidates" to record.candidates.joinToString(",", "[", "]") { "\"${it.host}\"" },
            "port" to "${record.port}",
            "tls_fp" to record.tlsFp,
            "gateway_pk" to record.gatewayPk,
            "secret" to Base64.getEncoder().encodeToString(ByteArray(32) { 5 }),
            "name" to record.host,
            "profile" to record.profile,
        )
    return "fermix://pair?" +
        parameters.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
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

    private fun opener(
        keys: DeviceKeyFacade,
        dialerFor: (Int, ByteArray) -> Dialer = webSocketDialers(),
    ) = AppSessions(
        databases,
        keys,
        dialerFor,
        MutableStateFlow(NetworkFacts.NONE),
        announcer = { _, _ -> Announcer { Announcement.NOT_ANNOUNCED } },
    ) { "0.1.0" }

    @Test
    fun `a session opens with its record's own key alias, and none without the key or a route`() =
        runTest {
            val paired = record(1)
            val keys = FakeKeys(holds = setOf(paired.keyAlias))
            val opener = opener(keys)
            opener.open(paired, backgroundScope, NO_REVISION).close()
            assertEquals(listOf(paired.keyAlias), keys.asked)

            val lost = record(2)
            assertThrows(SessionUnavailable::class.java) { opener.open(lost, backgroundScope, NO_REVISION) }
            assertEquals(listOf(paired.keyAlias, lost.keyAlias), keys.asked)
            val routeless = paired.copy(candidates = emptyList())
            assertThrows(SessionUnavailable::class.java) { opener.open(routeless, backgroundScope, NO_REVISION) }
        }

    @Test
    fun `a record with only its route opens a session, which races the route and names the stored revision`() =
        runTest {
            val routed = record(1).copy(candidates = emptyList(), route = ROUTE)
            val opener = opener(FakeKeys(holds = setOf(routed.keyAlias)))
            opener.open(routed, backgroundScope, NO_REVISION).close()
            val parts = opener.sessionParts(routed) { ROUTE.revision }
            assertEquals(ROUTE.revision, parts.relayListRev())
        }

    @Test
    fun `a pairing's session names the revision of the route stored for the link's daemon`() =
        runTest {
            val paired = record(1)
            val asked = mutableListOf<String>()
            val link = PairingLink.parse(pairingLinkText(paired))
            val opener = opener(FakeKeys(holds = emptySet()))
            val pairing =
                opener.pairingParts(link, StandardTestDispatcher(testScheduler), backgroundScope) { instanceId ->
                    asked += instanceId
                    ROUTE.revision
                }
            assertEquals(ROUTE.revision, pairing.relayListRev())
            assertEquals(listOf(paired.id), asked)
        }

    @Test
    fun `a connection test races the record's hosts and its route`() =
        runTest {
            val paired = record(1).copy(route = ROUTE)
            val refusing = Dialer { throw TransportException.Unreachable(IOException("no daemon here")) }
            val opener = opener(FakeKeys(holds = setOf(paired.keyAlias))) { _, _ -> refusing }
            assertEquals(TestOutcome.NotReached(failed = (paired.candidates + ROUTE).toSet()), opener.test(paired))
        }

    @Test
    fun `a record whose pushes FCM dropped opens a session that pulls its history in full, and only such a record`() =
        runTest {
            val paired = record(1)
            val opener = opener(FakeKeys(holds = setOf(paired.keyAlias)))
            assertTrue(opener.sessionParts(paired.copy(historyPullDue = true), NO_REVISION).fullPull)
            assertFalse(opener.sessionParts(paired, NO_REVISION).fullPull)
        }

    @Test
    fun `the candidate the last hello went over is kept on the record, and the next process races it first`() =
        runTest {
            val file = File(folder.root, "instances.json")
            val process = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val store = InstanceStore(file, process, databases)
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
            val kept = InstanceStore(file, backgroundScope, databases).instances.first().single()
            assertEquals(LAN, kept.lastCandidate)
            val session = opener(FakeKeys(holds = setOf(kept.keyAlias))).open(kept, backgroundScope, NO_REVISION)
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
            val refusal =
                assertThrows(SessionUnavailable::class.java) { opener.open(removed, backgroundScope, NO_REVISION) }
            assertTrue(refusal.cause is InstanceGone)
            assertFalse(File(folder.root, removed.id).exists())
        }

    @Test
    fun `a session, a pairing and a connection test dial what the app's dialers make for a port and pin`() =
        runTest {
            val paired = record(1)
            val asked = mutableListOf<Pair<Int, String>>()
            val refusing = Dialer { throw TransportException.Unreachable(IOException("no daemon here")) }
            val opener =
                opener(FakeKeys(holds = setOf(paired.keyAlias))) { port, pin ->
                    asked += port to pin.toHexString()
                    refusing
                }
            val pin = paired.tlsFingerprint().toHexString()

            assertSame(refusing, opener.sessionParts(paired, NO_REVISION).dialer)
            assertEquals(TestOutcome.NotReached(failed = paired.candidates.toSet()), opener.test(paired))
            val link = PairingLink.parse(pairingLinkText(paired))
            val pairing = opener.pairingParts(link, StandardTestDispatcher(testScheduler), backgroundScope) { null }
            assertSame(refusing, pairing.dialerFor(7, ByteArray(32)))
            assertEquals(listOf(paired.port to pin, paired.port to pin, 7 to ByteArray(32).toHexString()), asked)
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
