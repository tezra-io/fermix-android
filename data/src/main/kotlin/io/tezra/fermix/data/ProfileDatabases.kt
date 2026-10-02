package io.tezra.fermix.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException

private const val DATABASE_FILE = "profile.db"
private const val MEDIA_DIRECTORY = "media"

/**
 * Every (instance, profile)'s database and media directory under [root], keyed by both ids from day one
 * (design section 9.1): `<root>/<instance id>/<profile key>/profile.db` and `.../media/`. The profile key is
 * the SHA-256 of the profile id, since the wire holds the id to nothing but being non-empty and a file name
 * cannot take every string. [root] belongs in credential-encrypted storage that no backup or device transfer
 * carries (design section 6.6), such as under `Context.noBackupFilesDir`.
 *
 * A database is built once and kept open for the process, its queries run on [queries]; [delete] closes an
 * instance's before it removes its files. The methods do file I/O, so they run off the main thread.
 */
class ProfileDatabases(
    private val context: Context,
    private val root: File,
    private val queries: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val opened = HashMap<Pair<String, String>, ProfileDatabase>()

    /** [instanceId]'s database for [profileId], its directory made if it is not there. */
    fun open(
        instanceId: String,
        profileId: String,
    ): ProfileDatabase {
        val directory = profileDirectory(instanceId, profileId)
        synchronized(lock) {
            return opened.getOrPut(instanceId to profileId) {
                makeDirectory(directory)
                Room
                    .databaseBuilder(
                        context,
                        ProfileDatabase::class.java,
                        File(directory, DATABASE_FILE).path,
                    ).buildProfileDatabase(queries)
            }
        }
    }

    /** Where [instanceId]'s media for [profileId] are cached: a MediaCache's directory. */
    fun mediaDirectory(
        instanceId: String,
        profileId: String,
    ): File = File(profileDirectory(instanceId, profileId), MEDIA_DIRECTORY)

    /** Closes [instanceId]'s databases and deletes every file it has, each profile's. */
    fun delete(instanceId: String) {
        requireInstanceId(instanceId)
        val closing =
            synchronized(lock) {
                val keys = opened.keys.filter { it.first == instanceId }
                keys.map { key -> checkNotNull(opened.remove(key)) }
            }
        closing.forEach { it.close() }
        val directory = File(root, instanceId)
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("could not delete $directory")
    }

    /** Deletes the files of every instance not in [keep]: what a removal that ended early left behind. */
    internal fun deleteAllExcept(keep: Set<String>) {
        if (!root.exists()) return
        val listed = root.listFiles() ?: throw IOException("could not list $root")
        listed.filter { it.isDirectory && SHA256_HEX.matches(it.name) && it.name !in keep }.forEach { delete(it.name) }
    }

    private fun profileDirectory(
        instanceId: String,
        profileId: String,
    ): File {
        requireInstanceId(instanceId)
        require(profileId.isNotEmpty()) { "a profile's id is empty" }
        return File(File(root, instanceId), sha256Hex(profileId.encodeToByteArray()))
    }
}

private fun requireInstanceId(instanceId: String) {
    require(SHA256_HEX.matches(instanceId)) { "an instance's id is a SHA-256 in lowercase hex" }
}
