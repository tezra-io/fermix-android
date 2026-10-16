package io.tezra.fermix.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `hello.relay_list_rev`: the revision of the relay list the phone holds, 16 lowercase hex digits, protocol v2's,
 * absent with none (PROTOCOL.md "Remote reach"). The export's v2 hello carries no `last_mutation_seq`, so a v2
 * hello without one is read, and one with it still is.
 */
class HelloRevisionTest {
    private fun hello(
        rev: String?,
        v: Int = 2,
    ) = ClientEvent.Hello("device-1", "1.0.0", 0uL, v, relayListRev = rev)

    @Test
    fun `a hello names the stored revision after protocol_v, and none when it holds none`() {
        val named = headerOf(encodeClientEvent(2, 1uL, hello("374b4c8f103627f7")))
        assertEquals(
            """{"v":2,"t":"hello","seq":1,"device_id":"device-1","app_version":"1.0.0","last_server_seq":0,""" +
                """"protocol_v":2,"relay_list_rev":"374b4c8f103627f7"}""",
            named,
        )
        val none = decodeClientEvent(encodeClientEvent(2, 1uL, hello(null))).event as ClientEvent.Hello
        assertNull(none.relayListRev)
    }

    @Test
    fun `a revision that is not 16 lowercase hex digits is refused both ways`() {
        listOf("374b4c8f103627f", "374B4C8F103627F7", "374b4c8f103627f7a", "").forEach { rev ->
            val encoded = assertThrows<ProtocolException.InvalidField>(rev) { encodeClientEvent(2, 1uL, hello(rev)) }
            assertEquals("relay_list_rev", encoded.field)
            val header =
                """{"v":2,"t":"hello","seq":1,"device_id":"d","app_version":"1","last_server_seq":0,"protocol_v":2,""" +
                    """"relay_list_rev":"$rev"}"""
            val decoded = assertThrows<ProtocolException.InvalidField>(rev) { decodeClientEvent(frameOf(header)) }
            assertEquals("relay_list_rev", decoded.field)
        }
    }

    @Test
    fun `protocol v1 carries no revision`() {
        val refusal =
            assertThrows<ProtocolException.FieldNotInVersion> {
                encodeClientEvent(1, 1uL, hello("374b4c8f103627f7", v = 1))
            }
        assertEquals("relay_list_rev", refusal.field)
    }

    @Test
    fun `a v2 hello reads with or without last_mutation_seq`() {
        val bare = decodeClientEvent(encodeClientEvent(2, 1uL, hello(null))).event as ClientEvent.Hello
        assertNull(bare.lastMutationSeq)
        val withSeq = ClientEvent.Hello("device-1", "1.0.0", 0uL, 2, lastMutationSeq = 4uL)
        assertEquals(withSeq, decodeClientEvent(encodeClientEvent(2, 1uL, withSeq)).event)
    }
}
