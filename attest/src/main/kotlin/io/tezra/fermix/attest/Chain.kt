package io.tezra.fermix.attest

/**
 * The most certificates a pairing's chain holds, and the most bytes of DER it holds back to back as
 * `pair_request`'s raw tail (design section 7, the `pair_request.attestation` row). core-protocol holds
 * `pair_request` to the same bounds when it encodes one.
 */
const val MAX_CHAIN_CERTIFICATES = 6
const val MAX_CHAIN_BYTES = 16_384

private const val KEY_BYTES = 32

/** The SubjectPublicKeyInfo of an id-X25519 (1.3.101.110) key, before its 32 raw bytes (design section 6.1). */
private const val X25519_SPKI_PREFIX = "302a300506032b656e032100"

private const val SEQUENCE = 0x30

/** A TBSCertificate's `[0] EXPLICIT Version`, absent from a version 1 certificate. */
private const val EXPLICIT_VERSION = 0xa0

/** After the version: serialNumber, signature, issuer, validity, subject, then subjectPublicKeyInfo. */
private const val KEY_FIELD = 5

/** A TBSCertificate's fields at most (RFC 5280 section 4.1): ten. */
private const val MAX_TBS_FIELDS = 10

private const val BYTE_MASK = 0xff
private const val BITS_PER_BYTE = 8

/** A tag number of 31 says the tag goes on in the bytes after; certificates use none. */
private const val TAG_NUMBER_MASK = 0x1f

/** A length under this is its own byte; from it on, the byte counts the length's bytes. */
private const val SHORT_FORM_LIMIT = 0x80

/** Two bytes of length reach 65,535, past any chain the daemon takes. */
private const val MAX_LENGTH_BYTES = 2

/** A tag and a length byte. */
private const val MIN_HEADER_BYTES = 2

/**
 * What the phone checks of its attestation chain before any socket opens, so that a chain the daemon
 * would refuse for its shape alone is a local, typed error (design section 6.2). The daemon verifies
 * the rest: the signatures, the roots, the KeyMint facts, the challenge, the boot state and revocation.
 */
object Chain {
    /**
     * Throws a [ChainShapeException] unless [chain], leaf first, holds one to six certificates of at most
     * 16 KiB together, each one DER SEQUENCE, and the leaf's SubjectPublicKeyInfo is id-X25519 with
     * [publicKey], the key the handshake will authenticate (design section 6.2, check 1).
     */
    fun validateShape(
        chain: List<ByteArray>,
        publicKey: ByteArray,
    ) {
        require(publicKey.size == KEY_BYTES) { "an X25519 public key is $KEY_BYTES bytes, not ${publicKey.size}" }
        requireBounds(chain)
        chain.forEachIndexed { index, certificate -> requireOneSequence(certificate, index) }
        val leafKey = subjectPublicKeyInfo(chain.first(), 0)
        if (!leafKey.contentEquals(X25519_SPKI_PREFIX.hexToByteArray() + publicKey)) {
            throw ChainShapeException.LeafKeyMismatch()
        }
    }
}

/** Every refusal of [Chain.validateShape], each its own type. */
sealed class ChainShapeException(
    message: String,
) : Exception(message) {
    class CertificateCount(
        val count: Int,
    ) : ChainShapeException("a chain of $count certificates; one to $MAX_CHAIN_CERTIFICATES are sent")

    class TooLarge(
        val bytes: Long,
    ) : ChainShapeException("a chain of $bytes bytes is past the bound of $MAX_CHAIN_BYTES")

    /** Certificate [index], the leaf being 0, is not one DER certificate. */
    class NotDer(
        val index: Int,
    ) : ChainShapeException("certificate $index of the chain is not one DER certificate")

    class LeafKeyMismatch :
        ChainShapeException("the leaf's SubjectPublicKeyInfo is not id-X25519 with the device key's $KEY_BYTES bytes")
}

/** Refuses a chain of no certificate or more than six, or of more than 16 KiB together. */
private fun requireBounds(chain: List<ByteArray>) {
    if (chain.size !in 1..MAX_CHAIN_CERTIFICATES) throw ChainShapeException.CertificateCount(chain.size)
    val bytes = chain.sumOf { it.size.toLong() }
    if (bytes > MAX_CHAIN_BYTES) throw ChainShapeException.TooLarge(bytes)
}

/** Refuses certificate [index] unless it is one DER SEQUENCE spanning all of its bytes. */
private fun requireOneSequence(
    certificate: ByteArray,
    index: Int,
) {
    val reader = DerReader(certificate, index)
    val whole = reader.element(0, certificate.size)
    reader.need(whole.tag == SEQUENCE && whole.end == certificate.size)
}

/**
 * The SubjectPublicKeyInfo of [certificate], certificate [index] of a chain, as its whole DER: the field
 * after the subject in its TBSCertificate. Internal, so a test can read it from real certificates.
 */
internal fun subjectPublicKeyInfo(
    certificate: ByteArray,
    index: Int,
): ByteArray {
    val reader = DerReader(certificate, index)
    val whole = reader.element(0, certificate.size)
    val tbs = reader.element(whole.contentStart, whole.end)
    reader.need(whole.tag == SEQUENCE && tbs.tag == SEQUENCE)
    val fields = reader.children(tbs, MAX_TBS_FIELDS)
    val unversioned = if (fields.firstOrNull()?.tag == EXPLICIT_VERSION) fields.drop(1) else fields
    val key = unversioned.getOrNull(KEY_FIELD)?.takeIf { it.tag == SEQUENCE } ?: throw ChainShapeException.NotDer(index)
    return certificate.copyOfRange(key.start, key.end)
}

/** One DER element: its [tag], from [start], its content from [contentStart] up to [end]. */
private class DerElement(
    val tag: Int,
    val start: Int,
    val contentStart: Int,
    val end: Int,
)

/**
 * Reads the DER of certificate [index] of a chain: single-byte tags and definite lengths in their shortest
 * form, of at most two bytes. Whatever is not that is [ChainShapeException.NotDer].
 */
private class DerReader(
    private val bytes: ByteArray,
    private val index: Int,
) {
    /** The element at [at], which must end by [limit]. */
    fun element(
        at: Int,
        limit: Int,
    ): DerElement {
        need(at >= 0 && limit - at >= MIN_HEADER_BYTES)
        val tag = byteAt(at)
        need(tag and TAG_NUMBER_MASK != TAG_NUMBER_MASK)
        val lengthAt = at + MIN_HEADER_BYTES
        val first = byteAt(at + 1)
        val lengthBytes = if (first < SHORT_FORM_LIMIT) 0 else first - SHORT_FORM_LIMIT
        need(lengthBytes <= MAX_LENGTH_BYTES && first != SHORT_FORM_LIMIT && limit - lengthAt >= lengthBytes)
        val length = if (lengthBytes == 0) first else longLength(lengthAt, lengthBytes)
        val contentStart = lengthAt + lengthBytes
        need(length <= limit - contentStart)
        return DerElement(tag, at, contentStart, contentStart + length)
    }

    /** The elements inside [parent], at most [max], which must fill it exactly. */
    fun children(
        parent: DerElement,
        max: Int,
    ): List<DerElement> {
        val found = ArrayList<DerElement>()
        var at = parent.contentStart
        repeat(max) {
            if (at == parent.end) return found
            val child = element(at, parent.end)
            found += child
            at = child.end
        }
        need(at == parent.end)
        return found
    }

    fun need(holds: Boolean) {
        if (!holds) throw ChainShapeException.NotDer(index)
    }

    /** A length in [count] bytes from [at], refused unless no shorter form could carry it. */
    private fun longLength(
        at: Int,
        count: Int,
    ): Int {
        val length = (0 until count).fold(0) { value, offset -> (value shl BITS_PER_BYTE) or byteAt(at + offset) }
        need(length >= maxOf(SHORT_FORM_LIMIT, 1 shl (BITS_PER_BYTE * (count - 1))))
        return length
    }

    private fun byteAt(at: Int): Int = bytes[at].toInt() and BYTE_MASK
}
