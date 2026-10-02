package io.tezra.fermix.attest

// Certificates assembled byte by byte, since the JDK has no public API that builds one, and none can
// sign for an X25519 key: an X.509 v3 certificate's whole shape around the key it carries, with a
// signature no test verifies. CertificateFactory reads them as X.509 (ChainTest), so the shape is real.

private const val SEQUENCE = 0x30
private const val SHORT_FORM_LIMIT = 0x80
private const val ONE_BYTE_LENGTH = 0x81
private const val TWO_BYTE_LENGTH = 0x82
private const val BYTE_LIMIT = 0x100
private const val BITS_PER_BYTE = 8

/** ecdsa-with-SHA256, 1.2.840.10045.4.3.2, as the Keystore's attestation certificates are signed. */
private val ECDSA_WITH_SHA256 = der(0x06, "2a8648ce3d040302".hexToByteArray())

/** An extension's OID: the Android key attestation extension, 1.3.6.1.4.1.11129.2.1.17. */
private val KEY_ATTESTATION_OID = der(0x06, "2b06010401d679020111".hexToByteArray())

/** The SubjectPublicKeyInfo of an id-X25519 key, before its 32 raw bytes. */
private const val X25519_SPKI_PREFIX = "302a300506032b656e032100"

/** One DER element: [tag], its length in DER's definite form, then [content] back to back. */
internal fun der(
    tag: Int,
    vararg content: ByteArray,
): ByteArray {
    val body = content.fold(ByteArray(0)) { joined, part -> joined + part }
    return byteArrayOf(tag.toByte()) + derLength(body.size) + body
}

private fun derLength(size: Int): ByteArray =
    when {
        size < SHORT_FORM_LIMIT -> byteArrayOf(size.toByte())
        size < BYTE_LIMIT -> byteArrayOf(ONE_BYTE_LENGTH.toByte(), size.toByte())
        else -> byteArrayOf(TWO_BYTE_LENGTH.toByte(), (size shr BITS_PER_BYTE).toByte(), size.toByte())
    }

internal fun x25519Spki(publicKey: ByteArray): ByteArray = X25519_SPKI_PREFIX.hexToByteArray() + publicKey

/**
 * An X.509 v3 certificate carrying [spki], its one extension holding [extensionBytes] bytes, as the
 * leaf's key attestation extension does, so a test can set the certificate's size.
 */
internal fun certificate(
    spki: ByteArray,
    extensionBytes: Int = 16,
): ByteArray {
    val name =
        der(
            SEQUENCE,
            der(0x31, der(SEQUENCE, der(0x06, "550403".hexToByteArray()), der(0x0c, "Key".encodeToByteArray()))),
        )
    val validity =
        der(SEQUENCE, der(0x17, "260101000000Z".encodeToByteArray()), der(0x17, "360101000000Z".encodeToByteArray()))
    val extension = der(SEQUENCE, KEY_ATTESTATION_OID, der(0x04, ByteArray(extensionBytes) { 7 }))
    val tbs =
        der(
            SEQUENCE,
            der(0xa0, der(0x02, byteArrayOf(2))),
            der(0x02, byteArrayOf(1)),
            der(SEQUENCE, ECDSA_WITH_SHA256),
            name,
            validity,
            name,
            spki,
            der(0xa3, der(SEQUENCE, extension)),
        )
    return der(SEQUENCE, tbs, der(SEQUENCE, ECDSA_WITH_SHA256), der(0x03, byteArrayOf(0), ByteArray(8) { 1 }))
}

/** [certificate] of the X25519 key [publicKey]: a leaf as the Keystore attests it. */
internal fun leafOf(
    publicKey: ByteArray,
    extensionBytes: Int = 16,
): ByteArray = certificate(x25519Spki(publicKey), extensionBytes)
