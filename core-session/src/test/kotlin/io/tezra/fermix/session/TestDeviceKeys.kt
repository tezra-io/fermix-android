package io.tezra.fermix.session

import io.tezra.fermix.attest.AttestedKey
import io.tezra.fermix.attest.DeviceKeyFacade
import io.tezra.fermix.demo.SoftwareKey
import io.tezra.fermix.noise.StaticKey

private const val SEQUENCE = 0x30
private const val SHORT_FORM_LIMIT = 0x80
private const val ONE_BYTE_LENGTH = 0x81
private const val TWO_BYTE_LENGTH = 0x82
private const val BYTE_LIMIT = 0x100
private const val BITS_PER_BYTE = 8

/** The SubjectPublicKeyInfo of an id-X25519 key, before its 32 raw bytes. */
private const val X25519_SPKI_PREFIX = "302a300506032b656e032100"

/** An issuer's bytes past its SEQUENCE header: long enough that its length takes two bytes. */
private const val ISSUER_CONTENT_BYTES = 300

/**
 * The Keystore as a pairing test sees it: software X25519 keys by alias, each "attested" by a chain of a
 * leaf carrying its key and one issuer, shaped as attest's Chain checks and no more, since the daemon
 * verifies the rest. [log] records every generate and delete in order, so a test can place each against
 * the ceremony's other steps; [fault] makes generate fail as a Keystore without the hardware does,
 * [foreignLeaf] makes it return a chain whose leaf carries another key, and [extraCertificates] go on the
 * chain after its issuer.
 */
internal class SoftwareDeviceKeys : DeviceKeyFacade {
    private val keys = mutableMapOf<String, SoftwareKey>()
    val log = mutableListOf<String>()
    val challenges = mutableListOf<ByteArray>()
    var fault: Exception? = null
    var foreignLeaf = false
    var extraCertificates: List<ByteArray> = emptyList()

    /** The aliases generated, in order. */
    val generated: List<String> get() = log.filter { it.startsWith(GENERATE) }.map { it.removePrefix(GENERATE) }

    /** A key this phone holds from an earlier pairing. */
    fun hold(alias: String) {
        keys[alias] = SoftwareKey.generate()
    }

    override fun generate(
        alias: String,
        challenge: ByteArray,
    ): AttestedKey {
        check(alias !in keys) { "$alias is generated twice" }
        fault?.let { throw it }
        val key = SoftwareKey.generate()
        keys[alias] = key
        log += GENERATE + alias
        challenges += challenge.copyOf()
        val leafKey = if (foreignLeaf) SoftwareKey.generate().publicKey else key.publicKey
        return AttestedKey(alias, listOf(leafCertificate(leafKey), issuerCertificate()) + extraCertificates)
    }

    override fun delete(alias: String) {
        keys.remove(alias)
        log += DELETE + alias
    }

    override fun exists(alias: String): Boolean = alias in keys

    override fun staticKey(alias: String): StaticKey = checkNotNull(keys[alias]) { "no key $alias" }

    companion object {
        const val GENERATE = "generate "
        const val DELETE = "delete "
    }
}

/**
 * A leaf certificate's shape around [publicKey]: a TBSCertificate with its version, then a serial, the
 * signature algorithm, issuer, validity and subject left empty, and the id-X25519 SubjectPublicKeyInfo;
 * an empty signature algorithm and an empty signature.
 */
internal fun leafCertificate(publicKey: ByteArray): ByteArray {
    val empty = der(SEQUENCE)
    val version = der(0xa0, der(0x02, byteArrayOf(2)))
    val spki = X25519_SPKI_PREFIX.hexToByteArray() + publicKey
    val tbs = der(SEQUENCE, version, der(0x02, byteArrayOf(1)), empty, empty, empty, empty, spki)
    return der(SEQUENCE, tbs, empty, der(0x03, byteArrayOf(0)))
}

/** An issuer: one DER SEQUENCE, all the phone checks of a certificate past the leaf, of [contentBytes]. */
internal fun issuerCertificate(contentBytes: Int = ISSUER_CONTENT_BYTES): ByteArray =
    der(SEQUENCE, der(0x04, ByteArray(contentBytes) { 3 }))

private fun der(
    tag: Int,
    vararg content: ByteArray,
): ByteArray {
    val body = content.fold(ByteArray(0)) { joined, part -> joined + part }
    val length =
        when {
            body.size < SHORT_FORM_LIMIT -> byteArrayOf(body.size.toByte())
            body.size < BYTE_LIMIT -> byteArrayOf(ONE_BYTE_LENGTH.toByte(), body.size.toByte())
            else -> byteArrayOf(TWO_BYTE_LENGTH.toByte(), (body.size shr BITS_PER_BYTE).toByte(), body.size.toByte())
        }
    return byteArrayOf(tag.toByte()) + length + body
}
