package io.tezra.fermix.noise

/** SHA-256's output, and so one block of HKDF-SHA256's. */
private const val BLOCK_BYTES = 32

/**
 * RFC 5869's HKDF-SHA256 with one block of output, 32 bytes: PRK = HMAC(salt, ikm), then
 * T(1) = HMAC(PRK, info ‖ 0x01). It is the push key's derivation, `HKDF-SHA256(salt: push_salt, ikm:
 * X25519(...), info: "fermix-push-v1", L: 32)` (design section 10; PROTOCOL.md "Push notifications"),
 * beside Noise's own HKDF ([hkdf]), which takes no info. The salt is required: the push key's is always
 * 32 bytes, and HMAC takes no empty key. The caller owns the output; the PRK is zeroed here.
 */
fun hkdfSha256(
    salt: ByteArray,
    inputKeyMaterial: ByteArray,
    info: ByteArray,
): ByteArray {
    require(salt.isNotEmpty()) { "an HKDF salt has bytes" }
    val pseudorandomKey = hmacSha256(salt, inputKeyMaterial)
    try {
        val block = hmacSha256(pseudorandomKey, info + 1.toByte())
        check(block.size == BLOCK_BYTES) { "HMAC-SHA256 gave ${block.size} bytes" }
        return block
    } finally {
        pseudorandomKey.fill(0)
    }
}
