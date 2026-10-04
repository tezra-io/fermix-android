package io.tezra.fermix.push

import io.tezra.fermix.noise.StaticKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The vendored file, read from the test resources, which are contracts/mobile itself. */
internal fun pushVectorsJson(): JsonObject {
    val stream =
        checkNotNull(PushEnvelopeTest::class.java.getResourceAsStream("/push_vectors.json")) {
            "contracts/mobile/push_vectors.json is not on the test classpath"
        }
    return stream.use { Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
}

/** The one vector of the vendored file, by its field names. */
internal class PushVector(
    private val fields: JsonObject,
) {
    fun hex(name: String): ByteArray = text(name).hexToByteArray()

    fun text(name: String): String = checkNotNull(fields[name]) { "the vector has no $name" }.jsonPrimitive.content

    fun payload(): JsonObject = checkNotNull(fields["payload"]).jsonObject
}

internal fun pushVector(): PushVector = PushVector(checkNotNull(pushVectorsJson()["vector"]).jsonObject)

/** The X25519 base point, u = 9: agreeing with it yields a private key's own public key. */
private fun basePoint(): ByteArray = ByteArray(32).also { it[0] = 9 }

/**
 * A software X25519 key from a vector's fixed private bytes, through the JDK's XDH: test material only. The
 * phone's key is a Keystore key, never imported (design section 12.6), and the device test generates one.
 */
internal class SoftwareKey(
    privateBytes: ByteArray,
) : StaticKey {
    private val privateKey: PrivateKey =
        KeyFactory.getInstance("XDH").generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, privateBytes))

    override val publicKey: ByteArray = agree(basePoint())

    override fun agree(peerPublicKey: ByteArray): ByteArray {
        val peer =
            KeyFactory.getInstance("XDH").generatePublic(
                java.security.spec.X509EncodedKeySpec("302a300506032b656e032100".hexToByteArray() + peerPublicKey),
            )
        val agreement = KeyAgreement.getInstance("XDH")
        agreement.init(privateKey)
        agreement.doPhase(peer, true)
        return agreement.generateSecret()
    }
}

/** What the gateway does to send a push: ChaCha20-Poly1305 over [plaintext] under [key] and [nonce]. */
internal fun seal(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
    return cipher.doFinal(plaintext)
}

/** [json] padded as design section 10 pins it: the JSON, one 0x80 byte, then zeros to 2,048 bytes. */
internal fun padded(json: String): ByteArray {
    val bytes = json.encodeToByteArray()
    check(bytes.size < PADDED_PLAINTEXT_BYTES) { "the JSON does not fit the bucket" }
    return (bytes + 0x80.toByte()).copyOf(PADDED_PLAINTEXT_BYTES)
}

/** An FCM `data` map as the daemon sends it (design section 10, "Message"). */
internal fun fcmData(
    nonce: ByteArray,
    sealed: ByteArray,
): Map<String, String> =
    mapOf(
        "v" to "2",
        "n" to Base64.getEncoder().encodeToString(nonce),
        "c" to Base64.getEncoder().encodeToString(sealed),
    )
