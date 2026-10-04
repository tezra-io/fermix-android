package io.tezra.fermix.push

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

private val NONCE = ByteArray(NONCE_BYTES) { (0x10 + it).toByte() }
private val SEALED = ByteArray(SEALED_BYTES) { it.toByte() }

private fun refusal(data: Map<String, String>): String {
    val read = PushEnvelope.read(data)
    assertTrue(read is EnvelopeRead.Refused, "$read")
    return (read as EnvelopeRead.Refused).reason
}

/**
 * An FCM message's `data` (design section 10, "Message"), bounded before anything opens it: `v` exactly "2",
 * `n` the base64 of 12 bytes, `c` of the 2,048-byte bucket and its 16-byte tag, the whole map at most 4,096
 * bytes. A refusal names the field, never its value.
 */
class PushEnvelopeTest {
    @Test
    fun `a message of the daemon's shape is read, its nonce and sealed bytes as sent`() {
        val read = PushEnvelope.read(fcmData(NONCE, SEALED))
        val envelope = (read as EnvelopeRead.Read).envelope
        assertArrayEquals(NONCE, envelope.nonce())
        assertArrayEquals(SEALED, envelope.sealed())
    }

    @Test
    fun `the sealed bytes are the bucket and its tag, 2,752 characters of base64`() {
        assertEquals(2_064, SEALED_BYTES)
        assertEquals(2_752, Base64.getEncoder().encodeToString(SEALED).length)
    }

    @Test
    fun `a version other than exactly 2 is refused`() {
        for (v in listOf("1", "3", "02", " 2", "2.0", "")) {
            assertEquals("v", refusal(fcmData(NONCE, SEALED) + ("v" to v)), v)
        }
        assertEquals("v", refusal(fcmData(NONCE, SEALED) - "v"))
    }

    @Test
    fun `a nonce of another length, or not plain base64, is refused`() {
        val encoder = Base64.getEncoder()
        val wrong =
            listOf(
                encoder.encodeToString(ByteArray(11)),
                encoder.encodeToString(ByteArray(13)),
                Base64.getUrlEncoder().encodeToString(ByteArray(12) { -1 }),
                encoder.encodeToString(NONCE).replaceRange(0, 1, "!"),
                "",
            )
        for (n in wrong) assertEquals("n", refusal(fcmData(NONCE, SEALED) + ("n" to n)), n)
        assertEquals("n", refusal(fcmData(NONCE, SEALED) - "n"))
    }

    @Test
    fun `a ciphertext of another length, as a smaller or larger bucket would be, is refused`() {
        val encoder = Base64.getEncoder()
        for (size in listOf(SEALED_BYTES - 3, SEALED_BYTES + 3, 72, 0)) {
            assertEquals("c", refusal(fcmData(NONCE, SEALED) + ("c" to encoder.encodeToString(ByteArray(size)))))
        }
        assertEquals("c", refusal(fcmData(NONCE, SEALED) - "c"))
    }

    @Test
    fun `a map past 4,096 bytes is refused before any field is read`() {
        val padded = fcmData(NONCE, SEALED) + ("x" to "y".repeat(MAX_DATA_BYTES))
        assertEquals("size", refusal(padded))
        // A map at the bound, with an extra key the daemon may add one day, is read.
        val spare = MAX_DATA_BYTES - fcmData(NONCE, SEALED).entries.sumOf { it.key.length + it.value.length } - 1
        assertTrue(PushEnvelope.read(fcmData(NONCE, SEALED) + ("x" to "y".repeat(spare))) is EnvelopeRead.Read)
    }

    @Test
    fun `the vendored APNs payload, protocol v1's unpadded plaintext, is no FCM message`() {
        val fx = pushVector().payload()["fx"].toString()
        val n = Regex("\"n\":\"([^\"]+)\"").find(fx)!!.groupValues[1]
        val c = Regex("\"c\":\"([^\"]+)\"").find(fx)!!.groupValues[1]
        assertEquals("c", refusal(mapOf("v" to "2", "n" to n, "c" to c)))
    }
}
