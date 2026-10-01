package io.tezra.fermix.noise

import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import javax.crypto.KeyAgreement

/** The X25519 base point, u = 9: agreeing with it yields a private key's own public key. */
private fun basePoint(): ByteArray = ByteArray(KEY_BYTES).also { it[0] = 9 }

/**
 * A software X25519 key built from a vector's fixed private bytes with the JDK's XDH. Test material
 * only: the phone's static key is a Keystore key and never imported (design section 12.6), and its
 * ephemerals are generated fresh. Here one class stands in for the phone's static key, its
 * ephemeral, and the daemon's two keys.
 */
internal class SoftwareX25519Key(
    privateBytes: ByteArray,
) : StaticKey {
    private val privateKey: PrivateKey =
        KeyFactory.getInstance("XDH").generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, privateBytes))

    override val publicKey: ByteArray = agree(basePoint())

    override fun agree(peerPublicKey: ByteArray): ByteArray =
        x25519(KeyAgreement.getInstance("XDH"), privateKey, peerPublicKey)

    /** The key as the JCA pair the handshake takes for its ephemeral. */
    fun keyPair(): KeyPair = KeyPair(x25519PublicKey(publicKey), privateKey)
}
