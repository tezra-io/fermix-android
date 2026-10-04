package io.tezra.fermix.push

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The JVM half of the device gate for push (design section 12.6), on the vendored push_vectors.json itself:
 * its one case, protocol v1's APNs payload, pins what protocol v2 keeps for FCM (design section 7: "the
 * derivation is platform-neutral and the vector file pins it"): the agreement from the phone's side, the push
 * key, and the AEAD. The vectors' fixed private keys run through the JDK's XDH here, never the Keystore. The
 * FCM case per kind is engine stage D1's export, not vendored yet (ProvisionalFcmCasesTest).
 */
class PushVectorTest {
    private val vector = pushVector()

    @Test
    fun `the vendored file is the v1 APNs vector alone, with no FCM case yet`() {
        assertEquals(setOf("source", "reference", "kdf", "aead", "payload", "vector"), pushVectorsJson().keys)
        assertEquals("Fermix mobile APNs push payload (fx)", pushVectorsJson()["source"].toString().trim('"'))
    }

    @Test
    fun `the phone's agreement is the vector's shared secret, from the device's side`() {
        val device = SoftwareKey(vector.hex("device_static_private"))
        assertArrayEquals(vector.hex("device_static_public"), device.publicKey)
        assertArrayEquals(vector.hex("shared_secret"), device.agree(vector.hex("gateway_static_public")))
    }

    @Test
    fun `the push key is the vector's`() {
        val key = PushKeys.derive(vector.hex("shared_secret"), vector.hex("apns_key_salt"))
        assertArrayEquals(vector.hex("push_key"), key)
    }

    @Test
    fun `the sealed bytes open to the vector's plaintext under its key and nonce`() {
        val opened = PushCipher.open(vector.hex("push_key"), vector.hex("nonce"), vector.hex("sealed"))
        assertEquals(vector.text("inner_plaintext"), opened?.decodeToString())
    }

    @Test
    fun `the payload's n and c are the vector's nonce and sealed bytes in base64`() {
        val fx = pushVector().payload()["fx"].toString()
        val n = Regex("\"n\":\"([^\"]+)\"").find(fx)!!.groupValues[1]
        val c = Regex("\"c\":\"([^\"]+)\"").find(fx)!!.groupValues[1]
        assertArrayEquals(vector.hex("nonce"), Base64.getDecoder().decode(n))
        assertArrayEquals(vector.hex("sealed"), Base64.getDecoder().decode(c))
    }

    @Test
    fun `any other key, a changed byte or another nonce does not open it`() {
        val key = vector.hex("push_key")
        val nonce = vector.hex("nonce")
        val sealed = vector.hex("sealed")
        assertNull(PushCipher.open(key.copyOf().also { it[0] = (it[0] + 1).toByte() }, nonce, sealed))
        assertNull(PushCipher.open(key, nonce.copyOf().also { it[11] = (it[11] + 1).toByte() }, sealed))
        assertNull(PushCipher.open(key, nonce, sealed.copyOf().also { it[3] = (it[3] + 1).toByte() }))
    }

    @Test
    fun `protocol v1's plaintext is no typed one, with no bucket and no kind`() {
        val opened = checkNotNull(PushCipher.open(vector.hex("push_key"), vector.hex("nonce"), vector.hex("sealed")))
        assertEquals(PlaintextRead.Unreadable("padding"), readPushPlaintext(opened))
    }
}
