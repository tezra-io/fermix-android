package io.tezra.fermix.noise

import java.security.KeyStore
import java.security.PrivateKey
import javax.crypto.KeyAgreement

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/**
 * The device key: an X25519 agree-only key in AndroidKeyStore, in the TEE, generated per pairing
 * attempt by the attest module (design sections 6.1 and 6.2). Its material never enters this
 * process; the Keystore runs each agreement. The device gate proves it on a real phone with a
 * freshly generated key; the JVM tests never reach it (design section 12.6).
 */
class KeystoreStaticKey private constructor(
    private val privateKey: PrivateKey,
    private val rawPublicKey: ByteArray,
) : StaticKey {
    override val publicKey: ByteArray
        get() = rawPublicKey.copyOf()

    override fun agree(peerPublicKey: ByteArray): ByteArray =
        x25519(KeyAgreement.getInstance(XDH, ANDROID_KEYSTORE), privateKey, peerPublicKey)

    companion object {
        /** Opens AndroidKeyStore and reads the key under [alias]; a missing key fails loud. */
        fun load(alias: String): KeystoreStaticKey {
            require(alias.isNotBlank()) { "a Keystore alias is required" }
            val store = KeyStore.getInstance(ANDROID_KEYSTORE)
            store.load(null)
            val privateKey = store.getKey(alias, null) as? PrivateKey
            val certificate = store.getCertificate(alias)
            checkNotNull(privateKey) { "AndroidKeyStore holds no private key under $alias" }
            checkNotNull(certificate) { "AndroidKeyStore holds no certificate under $alias" }
            return KeystoreStaticKey(privateKey, rawX25519PublicKey(certificate.publicKey))
        }
    }
}
