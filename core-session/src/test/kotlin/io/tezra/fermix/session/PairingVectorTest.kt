package io.tezra.fermix.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The vendored file, on the test classpath through core-session/build.gradle.kts, never copied. */
private const val VECTORS_RESOURCE = "/noise_vectors.json"

/**
 * The test daemon's responder and SAS, held to the vendored noise_vectors.json byte for byte: given a
 * vector's keys and its message 1, it writes the vector's message 2 and reaches its handshake hash, and
 * the SAS of that hash is the vector's. PairingTest then shows the phone's Verify SAS equals this
 * derivation of the daemon's hash, so the SAS a pairing shows is the contract's.
 */
class PairingVectorTest {
    @Test
    fun `the test responder answers each vector's message 1 with its message 2, hash and SAS`() {
        val vectors = loadVectors()
        assertEquals(listOf("ik", "ikpsk2"), vectors.map { it.text("pattern") })
        vectors.forEach { vector ->
            val messages = vector.getValue("handshake_messages").jsonArray.map { it.jsonObject }
            val static = SoftwareKey.of(vector.hex("resp_static_private"), vector.hex("resp_static_public"))
            val ephemeral = SoftwareKey.of(vector.hex("resp_ephemeral_private"), vector.hex("resp_ephemeral_public"))
            val psk = (vector.getValue("psk") as? JsonPrimitive)?.takeIf { it.isString }?.content?.hexToByteArray()
            val responded =
                IkResponder(static, psk, ephemeral).respond(messages[0].hex("wire"), messages[1].hex("payload"))

            val pattern = vector.text("pattern")
            assertArrayEquals(messages[1].hex("wire"), responded.second, pattern)
            assertArrayEquals(vector.hex("init_static_public"), responded.initiatorStatic, pattern)
            assertArrayEquals(vector.hex("handshake_hash"), responded.handshakeHash, pattern)
            assertEquals(vector.text("sas"), sasOf(responded.handshakeHash), pattern)
        }
    }
}

private fun loadVectors(): List<JsonObject> {
    val stream =
        checkNotNull(PairingVectorTest::class.java.getResourceAsStream(VECTORS_RESOURCE)) {
            "$VECTORS_RESOURCE is not on the test classpath; core-session/build.gradle.kts puts contracts/mobile there"
        }
    val root = stream.use { Json.parseToJsonElement(it.readBytes().decodeToString()) }.jsonObject
    return root.getValue("vectors").jsonArray.map { it.jsonObject }
}

private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.hex(name: String): ByteArray = text(name).hexToByteArray()
