package io.tezra.fermix

import android.app.Notification
import android.os.Parcel
import io.tezra.fermix.attest.AttestedKey
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.data.Instance
import io.tezra.fermix.noise.StaticKey
import io.tezra.fermix.protocol.PushPlatform
import io.tezra.fermix.push.PADDED_PLAINTEXT_BYTES
import io.tezra.fermix.push.PushKeys
import io.tezra.fermix.transport.Candidate
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.XECPrivateKeySpec
import java.util.Base64
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val KEY_BYTES = 32
private const val NONCE_BYTES = 12
private const val XDH = "XDH"

/**
 * The JDK's provider of X25519, named because Robolectric puts Conscrypt first, as Android does, and
 * Conscrypt's XDH takes only keys of its own.
 */
private const val SUN_EC = "SunEC"

/** The DER prefix of an X25519 SubjectPublicKeyInfo, before the raw 32 bytes. */
private const val X25519_SPKI_PREFIX = "302a300506032b656e032100"

private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/**
 * A software X25519 key whose private bytes are all [fill], through the JDK's XDH: test material only. The
 * phone's key is a Keystore key, never imported (design section 12.6); the device test generates one there.
 */
internal class SoftKey(
    fill: Int,
) : StaticKey {
    private val privateKey: PrivateKey =
        KeyFactory
            .getInstance(XDH, SUN_EC)
            .generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, ByteArray(KEY_BYTES) { fill.toByte() }))

    /** Agreeing with the base point, u = 9, gives the key's own public key. */
    override val publicKey: ByteArray = agree(ByteArray(KEY_BYTES).also { it[0] = 9 })

    override fun agree(peerPublicKey: ByteArray): ByteArray {
        val spki = X509EncodedKeySpec(X25519_SPKI_PREFIX.hexToByteArray() + peerPublicKey)
        val agreement = KeyAgreement.getInstance(XDH, SUN_EC)
        agreement.init(privateKey)
        agreement.doPhase(KeyFactory.getInstance(XDH, SUN_EC).generatePublic(spki), true)
        return agreement.generateSecret()
    }
}

/**
 * The device keys as software keys by alias; an alias with none is a key the Keystore lost. Each agreement is
 * counted, by alias, in [agreed]: what a push cost the Keystore.
 */
internal class SoftKeys(
    private val byAlias: Map<String, StaticKey>,
) : DeviceKeyFacade {
    val agreed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun generate(
        alias: String,
        challenge: ByteArray,
    ): AttestedKey = error("no key is generated in these tests")

    override fun delete(alias: String): Unit = error("no key is deleted in these tests")

    override fun exists(alias: String): Boolean = alias in byAlias

    override fun staticKey(alias: String): StaticKey {
        val key = checkNotNull(byAlias[alias]) { "no key under $alias" }
        return object : StaticKey {
            override val publicKey: ByteArray get() = key.publicKey

            override fun agree(peerPublicKey: ByteArray): ByteArray {
                agreed += alias
                return key.agree(peerPublicKey)
            }
        }
    }
}

/**
 * A daemon paired with this phone: its static key ([gateway]), the device key the phone holds for it
 * ([device]), and its record, named [label], notifications on, its daemon pushing through FCM.
 */
internal class PairedDaemon(
    number: Int,
    label: String,
) {
    val gateway = SoftKey(0x10 + number)
    val device = SoftKey(0x40 + number)
    private val salt = ByteArray(KEY_BYTES) { (0x70 + number).toByte() }
    val record: Instance =
        Instance(
            gatewayPk = base64(gateway.publicKey),
            tlsFp = "ab".repeat(KEY_BYTES),
            host = label,
            profile = "fermix",
            label = label,
            tint = "Slate",
            candidates = listOf(Candidate("100.101.102.$number", Candidate.Scope.TAILNET, Candidate.Kind.IP)),
            port = 4031,
            deviceId = "device-$number",
            keyAlias = "fermix.device.$number.0102030405060708",
            pushSalt = base64(salt),
            pushPlatforms = listOf(PushPlatform.FCM),
            notificationsEnabled = true,
        )

    /**
     * What this daemon sends to push [json] (design section 10, "Message"): the JSON padded to 2,048 bytes,
     * sealed under the push key of X25519(gateway, device) and the salt, as FCM's `data` map.
     */
    fun push(json: String): Map<String, String> {
        val key = PushKeys.derive(gateway.agree(device.publicKey), salt)
        val nonce = ByteArray(NONCE_BYTES) { (it + 1).toByte() }
        val bytes = json.encodeToByteArray()
        check(bytes.size < PADDED_PLAINTEXT_BYTES) { "the JSON does not fit the bucket" }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        val sealed = cipher.doFinal((bytes + 0x80.toByte()).copyOf(PADDED_PLAINTEXT_BYTES))
        return mapOf("v" to "2", "n" to base64(nonce), "c" to base64(sealed))
    }
}

/**
 * Every string the notification carries, its extras, ticker and public version among them, as Android writes it
 * to the system: its parcel, read as UTF-16, as a phone writes a string, and byte for byte, as Robolectric's
 * parcel holds one in modified UTF-8. Each test that looks for a word missing here first finds it present.
 */
internal fun Notification.written(): String {
    val parcel = Parcel.obtain()
    try {
        writeToParcel(parcel, 0)
        val bytes = parcel.marshall()
        return bytes.toString(Charsets.UTF_16LE) + bytes.toString(Charsets.ISO_8859_1)
    } finally {
        parcel.recycle()
    }
}

/** A row's push plaintext, protocol v2's (design section 7). */
internal fun messageJson(
    seq: Int,
    preview: String?,
): String {
    val words = preview?.let { "\"$it\"" } ?: "null"
    return """{"kind":"message","profile_id":"main","server_seq":$seq,"preview_text":$words}"""
}
