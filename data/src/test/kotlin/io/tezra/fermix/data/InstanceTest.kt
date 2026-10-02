package io.tezra.fermix.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The instance record's guards (design section 9.1): what a record holds is checked when it is made. */
class InstanceTest {
    private val record = instance(gateway = 1)

    @Test
    fun `the id is the gateway key's SHA-256, and the keys come back as the bytes they are`() {
        assertEquals(idOf(key(1)), record.id)
        assertArrayEquals(key(1), record.gatewayPublicKey())
        assertArrayEquals(key(2), record.tlsFingerprint())
        assertArrayEquals(key(0x73), record.pushSaltBytes())
    }

    @Test
    fun `a key that is not 32 bytes of canonical base64 is refused`() {
        val shortKey = base64(ByteArray(31))
        val unpadded = base64(key(1)).trimEnd('=')
        // 43 characters carry 258 bits, of which 32 bytes use 256: canonical base64 leaves the last two 0.
        // The key's last character is E, 000100; F, 000101, decodes to the same bytes with a stray bit.
        val stray = base64(key(1)).replaceRange(42, 43, "F")
        listOf(shortKey, unpadded, stray, "").forEach { text ->
            assertThrows<IllegalArgumentException> { record.copy(gatewayPk = text) }
            assertThrows<IllegalArgumentException> { record.copy(pushSalt = text) }
        }
    }

    @Test
    fun `a tls fingerprint that is not 64 lowercase hex digits is refused`() {
        listOf("ab".repeat(31), "AB".repeat(32), "zz".repeat(32), "").forEach { text ->
            assertThrows<IllegalArgumentException> { record.copy(tlsFp = text) }
        }
    }

    @Test
    fun `a blank host, profile, label, device id, key alias or tint is refused`() {
        assertThrows<IllegalArgumentException> { record.copy(host = " ") }
        assertThrows<IllegalArgumentException> { record.copy(profile = "") }
        assertThrows<IllegalArgumentException> { record.copy(label = "") }
        assertThrows<IllegalArgumentException> { record.copy(deviceId = "") }
        assertThrows<IllegalArgumentException> { record.copy(keyAlias = "") }
        assertThrows<IllegalArgumentException> { record.copy(tint = "") }
    }

    @Test
    fun `a tint is a name, the design's own, which the UI maps`() {
        listOf("Ocean", "Sand").forEach { name -> assertEquals(name, record.copy(tint = name).tint) }
        listOf("ocean", "3", "Ocean blue", "Purple").forEach { name ->
            assertThrows<IllegalArgumentException> { record.copy(tint = name) }
        }
    }

    @Test
    fun `a port outside 1 to 65535 is refused`() {
        assertEquals(1, record.copy(port = 1).port)
        assertEquals(65_535, record.copy(port = 65_535).port)
        assertThrows<IllegalArgumentException> { record.copy(port = 0) }
        assertThrows<IllegalArgumentException> { record.copy(port = 65_536) }
    }

    @Test
    fun `a nickname is 1 to 40 characters with no space around it`() {
        assertEquals("x".repeat(40), record.copy(nickname = "x".repeat(40)).nickname)
        assertThrows<IllegalArgumentException> { record.copy(nickname = "x".repeat(41)) }
        assertThrows<IllegalArgumentException> { record.copy(nickname = " ") }
        assertThrows<IllegalArgumentException> { record.copy(nickname = " Dev") }
    }

    @Test
    fun `more candidates than the wire carries are refused`() {
        assertThrows<IllegalArgumentException> { record.copy(candidates = List(17) { TAILNET_CANDIDATE }) }
    }

    @Test
    fun `a record's title is its nickname, or else the daemon's label`() {
        assertEquals("suj-mbp", record.title)
        assertEquals("Dev", record.copy(nickname = "Dev").title)
    }
}
