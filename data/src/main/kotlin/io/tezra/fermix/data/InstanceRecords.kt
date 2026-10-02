package io.tezra.fermix.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import io.tezra.fermix.transport.Candidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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
 * The instance records' DataStore over [file], whose writes run in [scope]. DataStore allows one per file
 * in a process, so the app makes it once; the file belongs in credential-encrypted storage that no backup
 * or device transfer carries (design section 6.6), such as `Context.noBackupFilesDir`.
 */
fun instanceDataStore(
    file: File,
    scope: CoroutineScope,
): DataStore<Instances> =
    DataStoreFactory.create(
        serializer = InstancesSerializer,
        scope = scope,
        produceFile = { file },
    )

/**
 * The typed DataStore's codec: the records as JSON through kotlinx.serialization, which the wire already
 * uses, rather than protobuf, which would bring a second codec and its toolchain for one file. A file that
 * does not decode, or whose records break a rule, is a [CorruptionException], which DataStore hands to the
 * reader: the records name the Keystore keys, so nothing replaces them silently.
 */
object InstancesSerializer : Serializer<Instances> {
    override val defaultValue: Instances = Instances()

    override suspend fun readFrom(input: InputStream): Instances =
        try {
            RECORD_JSON.decodeFromString(
                serializer<Instances>(),
                input.readBytes().decodeToString(throwOnInvalidSequence = true),
            )
        } catch (refusal: IllegalArgumentException) {
            throw CorruptionException("the instance records do not decode", refusal)
        } catch (refusal: CharacterCodingException) {
            throw CorruptionException("the instance records are not UTF-8", refusal)
        }

    override suspend fun writeTo(
        t: Instances,
        output: OutputStream,
    ) {
        output.write(RECORD_JSON.encodeToString(serializer<Instances>(), t).encodeToByteArray())
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
