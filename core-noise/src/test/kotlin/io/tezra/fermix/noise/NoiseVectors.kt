package io.tezra.fermix.noise

/** The vendored file, on the test classpath through core-noise/build.gradle.kts, never copied. */
private const val VECTORS_RESOURCE = "/noise_vectors.json"

/** One transport frame of a vector: the plaintext, its ciphertext, and the nonce it was sealed at. */
class TransportFrame(
    fields: Map<String, Any?>,
) {
    val plaintext = fields.hex("plaintext")
    val ciphertext = fields.hex("ciphertext")
    val nonce = fields.whole("nonce").toULong()
}

/** One handshake message of a vector: the payload it carries and the bytes on the wire. */
class HandshakeMessage(
    fields: Map<String, Any?>,
) {
    val payload = fields.hex("payload")
    val wire = fields.hex("wire")
}

/** The rekey case of a vector: the direction, its nonce and key after the rekey, and one frame. */
class RekeyCase(
    fields: Map<String, Any?>,
) {
    val direction = fields.text("direction")
    val frame = TransportFrame(fields)
    val key = fields.hex("key")
}

/**
 * One vector of contracts/mobile/noise_vectors.json: every data field it carries. A handshake
 * message's `note` is prose and is not read.
 */
class NoiseVector(
    fields: Map<String, Any?>,
) {
    val pattern = fields.text("pattern")
    val initStaticPrivate = fields.hex("init_static_private")
    val initStaticPublic = fields.hex("init_static_public")
    val respStaticPrivate = fields.hex("resp_static_private")
    val respStaticPublic = fields.hex("resp_static_public")
    val initEphemeralPrivate = fields.hex("init_ephemeral_private")
    val initEphemeralPublic = fields.hex("init_ephemeral_public")
    val respEphemeralPrivate = fields.hex("resp_ephemeral_private")
    val respEphemeralPublic = fields.hex("resp_ephemeral_public")
    val psk = (fields.getValue("psk") as String?)?.hexToByteArray()
    val first: HandshakeMessage
    val second: HandshakeMessage
    val handshakeHash = fields.hex("handshake_hash")
    val sas = fields.text("sas")
    val transport = fields.list("transport_messages").map(::TransportFrame)
    val rekey = RekeyCase(fields.member("rekey"))

    init {
        val messages = fields.list("handshake_messages").map(::HandshakeMessage)
        check(messages.size == 2) { "an IK vector has two handshake messages, $pattern has ${messages.size}" }
        first = messages[0]
        second = messages[1]
    }

    /** The parameterized tests are named by pattern. */
    override fun toString() = pattern
}

/** Reads every vector from the vendored file on the test classpath. */
internal fun loadNoiseVectors(): List<NoiseVector> = loadVectorFile().list("vectors").map(::NoiseVector)

/** The file's `suite`: the protocol names its vectors run, as one brace pattern, Noise_{IK,IKpsk2}_.... */
internal fun loadVectorSuite(): String = loadVectorFile().text("suite")

/**
 * The vendored file's top-level object. Besides `vectors` and `suite` it carries `source`,
 * `reference`, `sas` and `rekey`, prose about the vectors, which no test reads.
 */
private fun loadVectorFile(): Map<String, Any?> {
    val stream =
        checkNotNull(NoiseVector::class.java.getResourceAsStream(VECTORS_RESOURCE)) {
            "$VECTORS_RESOURCE is not on the test classpath; core-noise/build.gradle.kts puts contracts/mobile there"
        }
    val text = stream.use { it.readBytes().decodeToString() }
    val root = JsonReader(text).read() as? Map<*, *> ?: error("$VECTORS_RESOURCE is not a JSON object")
    return root.typed()
}

private fun Map<*, *>.typed(): Map<String, Any?> = entries.associate { (name, value) -> name as String to value }

private fun Map<String, Any?>.text(name: String): String = getValue(name) as? String ?: error("$name is not a string")

private fun Map<String, Any?>.hex(name: String): ByteArray = text(name).hexToByteArray()

private fun Map<String, Any?>.whole(name: String): Long =
    getValue(name) as? Long ?: error("$name is not a whole number")

private fun Map<String, Any?>.member(name: String): Map<String, Any?> =
    (getValue(name) as? Map<*, *> ?: error("$name is not an object")).typed()

private fun Map<String, Any?>.list(name: String): List<Map<String, Any?>> {
    val elements = getValue(name) as? List<*> ?: error("$name is not an array")
    return elements.map { element -> (element as? Map<*, *> ?: error("$name holds a non-object")).typed() }
}
