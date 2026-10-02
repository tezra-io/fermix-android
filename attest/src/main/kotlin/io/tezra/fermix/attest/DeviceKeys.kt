package io.tezra.fermix.attest

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.tezra.fermix.noise.KeystoreStaticKey
import io.tezra.fermix.noise.StaticKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.spec.ECGenParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/** AndroidKeyStore's X25519 generator from API 33: EC on the curve [X25519_CURVE] (design section 6.1). */
private const val XDH = "XDH"
private const val X25519_CURVE = "x25519"

/** The challenge's bytes: a SHA-256 (AttestationChallenge). */
private const val CHALLENGE_BYTES = 32

/**
 * The device keys in AndroidKeyStore (design section 6.1; onboarding section 5, `attest`): the only place
 * the app generates or deletes one, and every call refuses an alias that is no device key's before it
 * reaches the Keystore, so no other entry of the app's is ever touched here. A key is X25519 with the
 * purpose AGREE_KEY alone, in the TEE, never StrongBox, which refuses X25519; it needs neither an unlocked
 * device nor the user's authentication, so a push decrypts on a locked phone after its first unlock. None
 * of this but the refusals runs on the JVM: the device gate proves it on a real phone with a freshly
 * generated key (design section 12.6, README).
 */
class DeviceKeys : DeviceKeyFacade {
    override fun generate(
        alias: String,
        challenge: ByteArray,
    ): AttestedKey {
        require(alias.startsWith(DEVICE_KEY_ALIAS_PREFIX)) { "$alias is not a device key's alias" }
        require(challenge.size == CHALLENGE_BYTES) { "an attestation challenge is $CHALLENGE_BYTES bytes" }
        check(!exists(alias)) { "AndroidKeyStore already holds $alias; every attempt has a fresh alias" }
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec(X25519_CURVE))
                .setAttestationChallenge(challenge)
                .build()
        // A clean-up that fails too is attached to the generation's fault, which stays the one thrown.
        return runCatching { attestedKey(alias, spec) }
            .onFailure { fault -> runCatching { delete(alias) }.exceptionOrNull()?.let(fault::addSuppressed) }
            .getOrThrow()
    }

    override fun delete(alias: String) {
        require(alias.startsWith(DEVICE_KEY_ALIAS_PREFIX)) { "$alias is not a device key's alias" }
        keyStore().deleteEntry(alias)
    }

    override fun exists(alias: String): Boolean {
        require(alias.startsWith(DEVICE_KEY_ALIAS_PREFIX)) { "$alias is not a device key's alias" }
        return keyStore().containsAlias(alias)
    }

    override fun staticKey(alias: String): StaticKey {
        require(alias.startsWith(DEVICE_KEY_ALIAS_PREFIX)) { "$alias is not a device key's alias" }
        return KeystoreStaticKey.load(alias)
    }

    /** Generates [alias]'s key under [spec] and reads its chain back. */
    private fun attestedKey(
        alias: String,
        spec: KeyGenParameterSpec,
    ): AttestedKey {
        val generator = KeyPairGenerator.getInstance(XDH, ANDROID_KEYSTORE)
        generator.initialize(spec)
        generator.generateKeyPair()
        return AttestedKey(alias, chainOf(alias))
    }

    /**
     * [alias]'s attestation chain, leaf first, each certificate's DER. A key generated with a challenge
     * and no chain is the Keystore's fault, thrown as such.
     */
    private fun chainOf(alias: String): List<ByteArray> {
        val chain =
            keyStore().getCertificateChain(alias)
                ?: throw KeyStoreException("AndroidKeyStore holds no certificate chain under $alias")
        return chain.map { it.encoded }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
}
