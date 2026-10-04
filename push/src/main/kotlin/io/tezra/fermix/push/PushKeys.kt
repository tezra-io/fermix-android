package io.tezra.fermix.push

import io.tezra.fermix.noise.hkdfSha256

/** The HKDF info of every push key (design section 10; PROTOCOL.md "Push notifications"). */
private val PUSH_INFO = "fermix-push-v1".encodeToByteArray()

/** An X25519 output, a salt and a push key: 32 bytes each. */
private const val KEY_BYTES = 32

/**
 * The push key of one pairing: `HKDF-SHA256(salt: push_salt, ikm: X25519(device, gateway), info:
 * "fermix-push-v1", L: 32)`. The daemon derives the same key from its side. The vendored push vector pins it
 * (its salt is still named `apns_key_salt`, design section 7: the derivation is platform-neutral).
 */
object PushKeys {
    /** The key from [sharedSecret], the phone's agreement with the daemon's static key, and [pushSalt]. */
    fun derive(
        sharedSecret: ByteArray,
        pushSalt: ByteArray,
    ): ByteArray {
        require(sharedSecret.size == KEY_BYTES) { "an X25519 output is $KEY_BYTES bytes, not ${sharedSecret.size}" }
        require(pushSalt.size == KEY_BYTES) { "push_salt is $KEY_BYTES bytes, not ${pushSalt.size}" }
        return hkdfSha256(salt = pushSalt, inputKeyMaterial = sharedSecret, info = PUSH_INFO)
    }
}
