package io.tezra.fermix.attest

import java.security.MessageDigest
import kotlin.random.Random

/** Every device key's alias starts so (design section 6.1). */
const val DEVICE_KEY_ALIAS_PREFIX = "fermix.device."

/** The bytes of `sha256(gateway_pk)` an alias names its daemon by. */
private const val DAEMON_PREFIX_BYTES = 8

/** The random bytes an alias names its attempt by (design section 6.1). */
private const val ATTEMPT_BYTES = 8

private const val GATEWAY_KEY_BYTES = 32

/**
 * Design section 6.1's alias, one per pairing attempt: `fermix.device.<gateway_pk sha256 prefix>.<random
 * 8 bytes>`, the prefix the first 8 bytes of the digest, both in lowercase hex. The prefix keeps one
 * daemon's keys together in the Keystore, and the random part keeps a second attempt at the same daemon
 * from touching the first one's key, so a stray scan of a paired daemon's code never replaces a working
 * key before its approval.
 */
object AliasNames {
    /** A fresh alias for an attempt at the daemon whose static key is [gatewayPk], from [random]. */
    fun next(
        gatewayPk: ByteArray,
        random: Random,
    ): String {
        require(gatewayPk.size == GATEWAY_KEY_BYTES) { "gateway_pk is $GATEWAY_KEY_BYTES bytes, not ${gatewayPk.size}" }
        val daemon = MessageDigest.getInstance("SHA-256").digest(gatewayPk).copyOf(DAEMON_PREFIX_BYTES)
        return DEVICE_KEY_ALIAS_PREFIX + daemon.toHexString() + "." + random.nextBytes(ATTEMPT_BYTES).toHexString()
    }
}
