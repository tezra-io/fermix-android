package io.tezra.fermix.attest

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

/** The challenge a pairing's key is attested with (design section 6.2, check 4). */
class AttestationChallengeTest {
    @Test
    fun `the challenge is SHA-256 of the label and the secret`() {
        // Computed by hand: (printf 'fermix-mobile-attest-v1'; head -c 32 /dev/zero | tr '\0' U) | sha256sum
        val expected = "8e8cf8c05e5937c2d8893e313d7fe1094e5dc538b49216f9b75a1cfaa2c13a10".hexToByteArray()
        assertArrayEquals(expected, AttestationChallenge.of(ByteArray(32) { 0x55 }))
    }

    @Test
    fun `the vendored link's secret gives its own challenge`() {
        // Computed by hand, with S the secret below:
        // (printf 'fermix-mobile-attest-v1'; printf S | base64 -d) | sha256sum
        val secret = Base64.getDecoder().decode("Pl6aUuM5THj6NjJ1Ror1ocjnpkh5G4gYUtdbsptL0z8=")
        val expected = "0624dad290fb5702afb951daf73dff2916c0380375f43d3acbec5cc030cf230b".hexToByteArray()
        assertArrayEquals(expected, AttestationChallenge.of(secret))
    }

    @Test
    fun `a secret that is not 32 bytes is refused`() {
        assertThrows<IllegalArgumentException> { AttestationChallenge.of(ByteArray(31)) }
        assertThrows<IllegalArgumentException> { AttestationChallenge.of(ByteArray(33)) }
    }
}
