package io.tezra.fermix.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import io.tezra.fermix.transport.Candidate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import java.nio.charset.CharacterCodingException

/**
 * The records' JSON. Every field is written, defaults included, so a file says what it held whatever a
 * later build's defaults are, and a null is left out; a field a later build no longer has is ignored.
 */
private val RECORD_JSON =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

/**
 * The typed DataStore's codec: the records as JSON through kotlinx.serialization, which the wire already
 * uses, rather than protobuf, which would bring a second codec and its toolchain for one file. A file that
 * does not decode, or whose records break a rule, is a [CorruptionException], which DataStore hands to the
 * reader: the records name the Keystore keys, so nothing replaces them silently.
 */
internal object InstancesSerializer : OkioSerializer<Instances> {
    override val defaultValue: Instances = Instances()

    override suspend fun readFrom(source: BufferedSource): Instances =
        try {
            RECORD_JSON.decodeFromString(
                serializer<Instances>(),
                source.readByteArray().decodeToString(throwOnInvalidSequence = true),
            )
        } catch (refusal: IllegalArgumentException) {
            throw CorruptionException("the instance records do not decode", refusal)
        } catch (refusal: CharacterCodingException) {
            throw CorruptionException("the instance records are not UTF-8", refusal)
        }

    override suspend fun writeTo(
        t: Instances,
        sink: BufferedSink,
    ) {
        sink.write(RECORD_JSON.encodeToString(serializer<Instances>(), t).encodeToByteArray())
        // OkioStorage syncs the file as this returns, before it moves it in place: what the sink still buffers would
        // reach the file only after that sync (atomicDataStore).
        sink.emit()
    }
}

/**
 * Design section 13.1's six tints, which a record written before the owner's eight (2026-10-11) names, each to the new
 * tint of its hue, so every Fermix keeps the colour family the owner chose; Slate is one of the eight, and stays.
 */
private val LEGACY_TINT_NAMES: Map<String, String> =
    mapOf("Sage" to "Olive", "Clay" to "Coral", "Plum" to "Iris", "Ocean" to "Sky", "Sand" to "Amber")

/**
 * A record's tint as the file holds it: written as it is, and read as itself or, one of the six old names, as the new
 * one of its hue ([LEGACY_TINT_NAMES]). A file of the old names reads right every time and holds the new ones from its
 * next write, never a [CorruptionException], which would cost the phone every record. Any other name is read as it is,
 * for the record's own rule to refuse.
 */
internal object TintNameSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.tezra.fermix.data.TintName", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val stored = decoder.decodeString()
        return LEGACY_TINT_NAMES[stored] ?: stored
    }
}

/**
 * A candidate as a record holds it. core-transport's [Candidate] carries no serializer, so it is written
 * through this shape; its enums are written by their constant names.
 */
@Serializable
private class StoredCandidate(
    @SerialName("host") val host: String,
    @SerialName("scope") val scope: Candidate.Scope,
    @SerialName("kind") val kind: Candidate.Kind,
)

/** [Candidate] through [StoredCandidate]. */
internal object CandidateSerializer : KSerializer<Candidate> {
    private val stored = serializer<StoredCandidate>()

    override val descriptor: SerialDescriptor = stored.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Candidate,
    ) = encoder.encodeSerializableValue(stored, StoredCandidate(value.host, value.scope, value.kind))

    override fun deserialize(decoder: Decoder): Candidate {
        val candidate = decoder.decodeSerializableValue(stored)
        return Candidate(candidate.host, candidate.scope, candidate.kind)
    }
}
