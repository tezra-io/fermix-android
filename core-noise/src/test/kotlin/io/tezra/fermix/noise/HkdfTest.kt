package io.tezra.fermix.noise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * RFC 5869's HKDF-SHA256 with one block of output, the push key's derivation (design section 10;
 * PROTOCOL.md "Push notifications"). The first block of an RFC 5869 output does not depend on the length
 * asked for, so the RFC's own test cases pin it by their first 32 bytes; push's tests pin it again by the
 * vendored push vector's `push_key`.
 */
class HkdfTest {
    @Test
    fun `RFC 5869 test case 1 gives its output's first block`() {
        val okm =
            hkdfSha256(
                salt = "000102030405060708090a0b0c".hexToByteArray(),
                inputKeyMaterial = ByteArray(22) { 0x0b },
                info = "f0f1f2f3f4f5f6f7f8f9".hexToByteArray(),
            )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", okm.toHexString())
    }

    @Test
    fun `RFC 5869 test case 2 gives its output's first block`() {
        val okm =
            hkdfSha256(
                salt = ByteArray(80) { (0x60 + it).toByte() },
                inputKeyMaterial = ByteArray(80) { it.toByte() },
                info = ByteArray(80) { (0xb0 + it).toByte() },
            )
        assertEquals("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c", okm.toHexString())
    }

    @Test
    fun `an empty salt is refused, as the push key always has its 32 bytes`() {
        assertThrows<IllegalArgumentException> {
            hkdfSha256(salt = ByteArray(0), inputKeyMaterial = ByteArray(32), info = ByteArray(1))
        }
    }
}
