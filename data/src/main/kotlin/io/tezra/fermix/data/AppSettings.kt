package io.tezra.fermix.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException

/** The settings' JSON, written whole as the records' is (InstanceRecords.kt). */
private val SETTINGS_JSON =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

/** A chat by its keys: an instance's id, `sha256(gateway_pk)` in lowercase hex, and a profile's id. */
@Serializable
data class ChatRef(
    @SerialName("instance_id") val instanceId: String,
    @SerialName("profile_id") val profileId: String,
) {
    init {
        require(SHA256_HEX.matches(instanceId)) { "an instance's id is a SHA-256 in lowercase hex" }
        require(profileId.isNotEmpty()) { "a profile's id is empty" }
    }
}

/**
 * The app-wide settings (design section 9.1, "App-wide: the biometric lock"): whether [appLock] gates the app
 * (section 13.7), and [lastChat], the chat open when the owner last left the app, which returning to the app
 * restores (section 13.4), none when the Chats list was on top.
 */
@Serializable
data class AppSettings(
    @SerialName("app_lock") val appLock: Boolean = false,
    @SerialName("last_chat") val lastChat: ChatRef? = null,
)

/**
 * The settings' DataStore over [file], whose writes run in [scope]; one per file in a process, so the app makes
 * it once, in credential-encrypted storage that no backup carries, as the records are.
 */
fun appSettingsDataStore(
    file: File,
    scope: CoroutineScope,
): DataStore<AppSettings> =
    DataStoreFactory.create(serializer = AppSettingsSerializer, scope = scope, produceFile = { file })

/**
 * The settings as JSON. A file that does not decode is a [CorruptionException], never replaced by the
 * defaults: a default would turn the app lock off without the owner's word.
 */
object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings =
        try {
            SETTINGS_JSON.decodeFromString(
                serializer<AppSettings>(),
                input.readBytes().decodeToString(throwOnInvalidSequence = true),
            )
        } catch (refusal: IllegalArgumentException) {
            throw CorruptionException("the app settings do not decode", refusal)
        } catch (refusal: CharacterCodingException) {
            throw CorruptionException("the app settings are not UTF-8", refusal)
        }

    override suspend fun writeTo(
        t: AppSettings,
        output: OutputStream,
    ) {
        output.write(SETTINGS_JSON.encodeToString(serializer<AppSettings>(), t).encodeToByteArray())
    }
}

/** The app-wide settings, each change one write. */
class AppSettingsStore(
    private val store: DataStore<AppSettings>,
) {
    val settings: Flow<AppSettings> = store.data

    suspend fun setAppLock(on: Boolean) {
        store.updateData { it.copy(appLock = on) }
    }

    /** The chat on top as the owner leaves it, or none when the Chats list is. */
    suspend fun setLastChat(chat: ChatRef?) {
        store.updateData { it.copy(lastChat = chat) }
    }
}
