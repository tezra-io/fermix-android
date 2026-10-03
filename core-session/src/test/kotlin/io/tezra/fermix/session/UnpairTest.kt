package io.tezra.fermix.session

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * "Unpair from {host}…" (design section 13.7): the phone's own `unpair`, which PROTOCOL.md answers with
 * close `4003`, sent once on a connection that is up and never queued for a later one.
 */
class UnpairTest {
    @Test
    fun `unpair asks the daemon to forget this phone, whose 4003 ends the session as revoked`() =
        runTest {
            val harness = Harness(this)
            val connection = harness.connect()

            assertTrue(harness.session.unpair())
            connection.expect<ClientEvent.Unpair>()
            connection.close(4003, "device revoked")
            assertEquals(SessionState.Revoked, harness.stateWhen { it is SessionState.Ended })
        }

    @Test
    fun `unpair with no connection up sends nothing, and says so`() =
        runTest {
            val harness = Harness(this)
            harness.network.value = NetworkFacts.NONE
            harness.open()
            harness.settle()

            assertFalse(harness.session.unpair())
            assertEquals(0, harness.daemon.dials)
        }

    @Test
    fun `an ended session refuses unpair`() =
        runTest {
            val harness = Harness(this)
            harness.open()
            harness.session.close()

            assertThrows<IllegalStateException> { harness.session.unpair() }
        }
}
