package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Platform
import io.tezra.fermix.protocol.RequestOutcome
import io.tezra.fermix.protocol.RequestState
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val TOKEN = "fcm-token-of-this-phone"

/** A reconciliation of a session opened without the full pull, as most are. */
private val NOT_IN_FULL = SessionEvent.Reconciled(pulledInFull = false)

/**
 * Design section 10's registration, as one session carries it: `push_register{platform:"android", token}` and
 * `push_unregister` go once on a connection that is up and reconciled, never queued; each connection says when
 * its reconciliation is done ([SessionEvent.Reconciled]), the `hello` the app registers at. And
 * `onDeletedMessages`'s full pull: an empty cache whose session is told to pull in full pulls every row from
 * the first, not the newest page.
 */
class PushRegistrationTest {
    @Test
    fun `a reconciled connection carries push_register and push_unregister, once each`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()
            assertTrue(NOT_IN_FULL in harness.events)

            assertTrue(harness.session.registerPush(TOKEN))
            val register = connection.expect<ClientEvent.PushRegister>()
            assertEquals(ClientEvent.PushRegister(platform = Platform.ANDROID, token = TOKEN), register)
            assertTrue(harness.session.unregisterPush())
            assertEquals(ClientEvent.PushUnregister, connection.expect<ClientEvent.PushUnregister>())
        }

    @Test
    fun `nothing goes while the reconciliation runs, as its steps read an unnamed error as their own`() =
        runTest {
            val harness = Harness(this)
            harness.store.items += OutboxItem(msg("m1"))
            harness.open()
            val connection = harness.daemon.accept()
            connection.connect()
            connection.expect<ClientEvent.RequestStatus>()
            harness.settle()
            assertFalse(NOT_IN_FULL in harness.events)
            assertFalse(harness.session.registerPush(TOKEN))
            assertFalse(harness.session.unregisterPush())

            // Running: the daemon took it, so it leaves the outbox and the drain sends nothing.
            connection.send(ServerEvent.RequestStatusPage(listOf(RequestOutcome("m1", RequestState.RUNNING))))
            harness.settle()
            assertTrue(NOT_IN_FULL in harness.events)
            assertTrue(harness.session.registerPush(TOKEN))
            connection.expect<ClientEvent.PushRegister>()
        }

    @Test
    fun `with no connection up nothing is sent, and the session says so`() =
        runTest {
            val harness = Harness(this)
            harness.network.value = NetworkFacts.NONE
            harness.open()
            harness.settle()

            assertFalse(harness.session.registerPush(TOKEN))
            assertFalse(harness.session.unregisterPush())
            assertEquals(0, harness.daemon.dials)
        }

    @Test
    fun `each connection says once that it has reconciled`() =
        runTest {
            val harness = Harness(this)
            val first = harness.connect()
            first.close(NORMAL_CLOSURE, LIFETIME_REASON)
            harness.daemon.accept().connect()
            harness.settle()
            assertEquals(2, harness.events.count { it == NOT_IN_FULL })
        }

    @Test
    fun `an empty cache told to pull in full pulls from the first row, not the newest page, and says so`() =
        runTest {
            val harness = Harness(this)
            harness.open(fullPull = true)
            val connection = harness.daemon.accept()
            connection.connect(HELLO_ACK.copy(historyHeadSeq = 120uL))
            val pull = connection.expect<ClientEvent.HistoryPull>()
            assertEquals(ClientEvent.HistoryPull(PROFILE, afterSeq = 0uL, limit = 200), pull)
            harness.settle()
            assertTrue(SessionEvent.Reconciled(pulledInFull = true) in harness.events)
        }
}
