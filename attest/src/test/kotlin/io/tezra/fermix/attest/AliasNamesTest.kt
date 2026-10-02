package io.tezra.fermix.attest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import kotlin.random.Random

/** Design section 6.1's alias per pairing attempt: `fermix.device.<gateway_pk sha256 prefix>.<random 8 bytes>`. */
class AliasNamesTest {
    // The vendored pairing link's gateway_pk.
    private val gatewayKey = Base64.getDecoder().decode("+j3tPK2Tk+8Li739Mty47DdwxBYZBDBldWj+PYOQR0o=")

    @Test
    fun `an alias names the daemon by its key's digest and the attempt by 8 random bytes`() {
        val alias = AliasNames.next(gatewayKey, Random(51))
        val parts = alias.split('.')
        assertEquals(listOf("fermix", "device"), parts.take(2))
        assertEquals(4, parts.size, alias)
        // printf '+j3tPK2Tk+8Li739Mty47DdwxBYZBDBldWj+PYOQR0o=' | base64 -d | sha256sum, its first 8 bytes.
        assertEquals("94bb3de9e6296cee", parts[2])
        assertEquals(Random(51).nextBytes(8).toHexString(), parts[3])
    }

    @Test
    fun `the prefix is 16 hex digits and the attempt part 16 more`() {
        val alias = AliasNames.next(gatewayKey, Random(7))
        val prefix = alias.split('.')[2]
        val attempt = alias.split('.')[3]
        assertEquals(16, prefix.length)
        assertEquals(16, attempt.length)
        assertTrue(Regex("[0-9a-f]{16}").matches(attempt), attempt)
    }

    @Test
    fun `two attempts at one daemon share the prefix and differ in the rest`() {
        val first = AliasNames.next(gatewayKey, Random(1))
        val second = AliasNames.next(gatewayKey, Random(2))
        assertNotEquals(first, second)
        assertEquals(first.substringBeforeLast('.'), second.substringBeforeLast('.'))
        val aliases = List(1_000) { AliasNames.next(gatewayKey, Random(it)) }
        assertEquals(aliases.size, aliases.toSet().size)
    }

    @Test
    fun `another daemon's key gives another prefix`() {
        val other = ByteArray(32) { 1 }
        assertNotEquals(
            AliasNames.next(gatewayKey, Random(3)).split('.')[2],
            AliasNames.next(other, Random(3)).split('.')[2],
        )
    }

    @Test
    fun `a gateway key that is not 32 bytes is refused`() {
        assertThrows<IllegalArgumentException> { AliasNames.next(ByteArray(31), Random(1)) }
    }
}
