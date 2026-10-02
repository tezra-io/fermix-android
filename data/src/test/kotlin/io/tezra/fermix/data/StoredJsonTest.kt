package io.tezra.fermix.data

import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.protocol.Frame
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.ServerEvent
import io.tezra.fermix.protocol.encodeClientEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Storage writes a model as the wire does (Stored.kt): a stored request or history row is, byte for byte, the
 * JSON core-protocol's codec puts on the wire with the envelope's `v` and `seq` taken out. STORED_JSON repeats
 * the wire codec's settings, which core-protocol keeps internal, so a change there that storage did not follow
 * fails here.
 */
class StoredJsonTest {
    @Test
    fun `every msg and command of the vendored fixtures is stored as the wire's codec writes it`() {
        val requests =
            vendored("fixtures/client_events.jsonl")
                .lines()
                .filter { it.isNotEmpty() }
                .map { Json.parseToJsonElement(it).jsonObject }
                .filter { it.getValue("t").jsonPrimitive.content in OUTBOXED }
        assertTrue(requests.size >= OUTBOXED.size, "the fixtures hold ${requests.size} requests")
        requests.forEach { header ->
            val request = STORED_JSON.decodeFromString(serializer<ClientEvent>(), withoutEnvelope(header))
            val v = header.getValue("v").jsonPrimitive.int
            assertStoredAsWire(v, request)
        }
    }

    @Test
    fun `a request with protocol v2's retry_of is stored as the wire's codec writes it at v2`() {
        assertStoredAsWire(2, ClientEvent.Msg("client-9", "main", "again", listOf("attach-1"), retryOf = "client-1"))
    }

    @Test
    fun `every history row core-protocol decodes from the vendored fixtures is stored as it came on the wire`() {
        val decoded = fixtureServerEvents().filterIsInstance<ServerEvent.HistoryPage>().flatMap { it.messages }
        val wire =
            vendored("fixtures/server_events.jsonl")
                .lines()
                .filter { it.isNotEmpty() }
                .map { Json.parseToJsonElement(it).jsonObject }
                .filter { it.getValue("t").jsonPrimitive.content == "history_page" }
                .flatMap { it.getValue("messages").jsonArray }
        assertEquals(wire.size, decoded.size)
        assertTrue(decoded.isNotEmpty(), "the fixtures hold no history row")
        decoded.zip(wire).forEach { (row, onWire) ->
            assertEquals(onWire.toString(), STORED_JSON.encodeToString(serializer<HistoryMessage>(), row))
        }
    }

    @Test
    fun `a field a later protocol adds to a stored row or request is ignored on reading it, as the wire ignores it`() {
        val page = fixtureServerEvents().filterIsInstance<ServerEvent.HistoryPage>().first()
        val row = page.messages.first()
        val widenedRow = widened(STORED_JSON.encodeToString(serializer<HistoryMessage>(), row))
        assertEquals(row, STORED_JSON.decodeFromString(serializer<HistoryMessage>(), widenedRow))
        val request = ClientEvent.Msg("client-9", "main", "hello", emptyList())
        val widenedRequest = widened(STORED_JSON.encodeToString(serializer<ClientEvent>(), request))
        assertEquals(request, STORED_JSON.decodeFromString(serializer<ClientEvent>(), widenedRequest))
    }

    /** [stored] with a field no model of this build knows. */
    private fun widened(stored: String): String {
        val fields = Json.parseToJsonElement(stored).jsonObject
        return JsonObject(fields + ("from_a_later_protocol" to JsonPrimitive(true))).toString()
    }

    /** [request] stored, against core-protocol's frame of it at [v], whose header is the wire's text. */
    private fun assertStoredAsWire(
        v: Int,
        request: ClientEvent,
    ) {
        val frame = Frame.decode(encodeClientEvent(v, 1uL, request))
        val header = Json.parseToJsonElement(frame.header.decodeToString()).jsonObject
        assertEquals(withoutEnvelope(header), STORED_JSON.encodeToString(serializer<ClientEvent>(), request))
    }

    private fun withoutEnvelope(header: JsonObject): String =
        JsonObject(header.filterKeys { it != "v" && it != "seq" }).toString()

    private companion object {
        /** The client events an outbox holds. */
        val OUTBOXED = setOf("msg", "command")
    }
}
