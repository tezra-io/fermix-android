package io.tezra.fermix.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val DATABASE_FILE = "profile.db"
private const val MEDIA_DIRECTORY = "media"

/**
 * The one profile a daemon serves in this version, `main` (PROTOCOL.md: "`profile_id` is `main`, the one
 * profile"): every (instance, profile) key uses it until `profiles[]` lists more (design section 9.1).
 */
const val MAIN_PROFILE = "main"

/**
 * How long [ProfileDatabases.delete] waits for an instance's readers to let go of its files. A flow ends once
 * its dispatcher runs it after the instance is gone and its last query is over. A use is whatever block its
 * caller passes, which nothing but this cap bounds, so it holds a removal for as long as it runs. A reader
 * still there past the cap is stuck: the delete fails loud, and the files, which no record names any more,
 * stay for the next launch's check.
 */
const val RELEASE_WAIT_MILLIS = 10_000L

/**
 * [instanceId]'s files were deleted in this process (ProfileDatabases.delete), its record gone first: nothing
 * opens them again until a pairing brings its daemon back.
 */
class InstanceGone(
    val instanceId: String,
) : IllegalStateException("$instanceId was removed, and its files with it")

/**
 * Every (instance, profile)'s database and media directory under [root], keyed by both ids from day one
 * (design section 9.1): `<root>/<instance id>/<profile key>/profile.db` and `.../media/`. The profile key is
 * the SHA-256 of the profile id, since the wire holds the id to nothing but being non-empty and a file name
 * cannot take every string. [root] belongs in credential-encrypted storage that no backup or device transfer
 * carries (design section 6.6), such as under `Context.noBackupFilesDir`.
 *
 * A database is built once and kept open for the process, its queries run on [queries], and so is each media
 * cache, its uses stamped by [mediaClock], as a process makes one MediaCache per directory. The methods do
 * file I/O, so they run off the main thread.
 *
 * It orders its readers and a removal, its InstanceReaders keeping the order; a session, which holds its
 * database outside them ([open]), is ordered by the app's supervisor. Room ends no flow as its database closes:
 * one reading hangs on, one whose query comes after fails, and a suspend call after it is cancelled, which
 * cancels its caller with nothing reported. So every reader is
 * counted from its start to its end, each flow of [observe] and each call of [withDatabase] and
 * [withMediaCache], and [delete] first marks the instance gone, which ends its flows and refuses any reader
 * that comes after, [open] too, then waits for the count to reach zero, at most [RELEASE_WAIT_MILLIS], and only
 * then closes the databases and deletes the files. A pairing brings a removed instance back ([admit]).
 */
class ProfileDatabases(
    private val context: Context,
    private val root: File,
    private val queries: CoroutineDispatcher = Dispatchers.IO,
    private val mediaClock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val opened = HashMap<Pair<String, String>, ProfileDatabase>()
    private val caches = HashMap<Pair<String, String>, MediaCache>()
    private val readers = InstanceReaders()

    /**
     * [instanceId]'s database for [profileId], its directory made if it is not there; [InstanceGone] once the
     * instance is deleted in this process. No record is looked up: an id no removal here deleted opens as any
     * other, and the next launch's check deletes the files of one no record names. Whoever holds the database
     * outside [observe] and [withDatabase] is ordered against [delete] by other means: the app's SessionSupervisor
     * closes a session it keeps, its store and its announcer, its run and its requests ended (Session.close),
     * before its instance's files go.
     */
    fun open(
        instanceId: String,
        profileId: String,
    ): ProfileDatabase {
        val directory = profileDirectory(root, instanceId, profileId)
        return readers.ifPresent(instanceId) { database(instanceId, profileId, directory) }
            ?: throw InstanceGone(instanceId)
    }

    /**
     * A pairing brings [instanceId]'s daemon back, before its record is written: its files may be opened again,
     * whatever an earlier removal in this process deleted. One while that removal is still deleting fails loud.
     */
    fun admit(instanceId: String) {
        requireInstanceId(instanceId)
        readers.admit(instanceId)
    }

    /**
     * [query]'s flow over [instanceId]'s database for [profileId], read on [queries], as opening one makes its
     * folder, until the instance is deleted: the flow then ends, once its query has, and so does one collected
     * for an instance deleted already, with no value and no file made.
     */
    fun <T> observe(
        instanceId: String,
        profileId: String,
        query: (ProfileDatabase) -> Flow<T>,
    ): Flow<T> {
        val directory = profileDirectory(root, instanceId, profileId)
        return channelFlow {
            val database = readers.enter(instanceId) { database(instanceId, profileId, directory) }
            if (database == null) return@channelFlow
            try {
                readers.untilGone(instanceId) { query(database).collect { send(it) } }
            } finally {
                readers.leave(instanceId)
            }
        }.flowOn(queries)
    }

    /**
     * [block] over [instanceId]'s database for [profileId], on [queries], which a [delete] waits for;
     * [InstanceGone] once the instance is deleted.
     */
    suspend fun <T> withDatabase(
        instanceId: String,
        profileId: String,
        block: suspend (ProfileDatabase) -> T,
    ): T {
        val directory = profileDirectory(root, instanceId, profileId)
        return using(instanceId, { database(instanceId, profileId, directory) }, block)
    }

    /**
     * [block] over [instanceId]'s media cache for [profileId], the same one for the process (MediaCache), on
     * [queries], which a [delete] waits for; [InstanceGone] once the instance is deleted.
     */
    suspend fun <T> withMediaCache(
        instanceId: String,
        profileId: String,
        block: (MediaCache) -> T,
    ): T {
        val directory = mediaDirectory(instanceId, profileId)
        val cache = {
            synchronized(lock) { caches.getOrPut(instanceId to profileId) { MediaCache(directory, mediaClock) } }
        }
        return using(instanceId, cache, block)
    }

    /** Where [instanceId]'s media for [profileId] are cached: the directory of its [withMediaCache]'s MediaCache. */
    internal fun mediaDirectory(
        instanceId: String,
        profileId: String,
    ): File = File(profileDirectory(root, instanceId, profileId), MEDIA_DIRECTORY)

    /**
     * Deletes [instanceId]'s files, each profile's database and media, once the record that named them is gone:
     * the instance is marked gone, which ends its readers' flows and refuses new readers; once every reader has
     * ended, at most [RELEASE_WAIT_MILLIS], its databases close and the files go. A reader still there then
     * fails the delete, and the files stay.
     */
    suspend fun delete(instanceId: String) {
        requireInstanceId(instanceId)
        readers.remove(instanceId) { closeAndDelete(instanceId) }
    }

    /** Deletes the files of every instance not in [keep]: what a removal that ended early left behind. */
    internal suspend fun deleteAllExcept(keep: Set<String>) {
        if (!root.exists()) return
        val listed = root.listFiles() ?: throw IOException("could not list $root")
        listed.filter { it.isDirectory && SHA256_HEX.matches(it.name) && it.name !in keep }.forEach { delete(it.name) }
    }

    /** [block] over what [take] opens for [instanceId], counted as a reader; [InstanceGone] once it is deleted. */
    private suspend fun <R : Any, T> using(
        instanceId: String,
        take: () -> R,
        block: suspend (R) -> T,
    ): T =
        withContext(queries) {
            val taken = readers.enter(instanceId, take) ?: throw InstanceGone(instanceId)
            try {
                block(taken)
            } finally {
                readers.leave(instanceId)
            }
        }

    /** Closes [instanceId]'s databases, lets its caches go, and deletes every file it has. */
    private fun closeAndDelete(instanceId: String) {
        val closing =
            synchronized(lock) {
                caches.keys.removeAll { it.first == instanceId }
                val keys = opened.keys.filter { it.first == instanceId }
                keys.map { key -> checkNotNull(opened.remove(key)) }
            }
        closing.forEach { it.close() }
        val directory = File(root, instanceId)
        if (directory.exists() && !directory.deleteRecursively()) throw IOException("could not delete $directory")
    }

    /** [instanceId]'s database for [profileId] in [directory], built at its first open and kept open. */
    private fun database(
        instanceId: String,
        profileId: String,
        directory: File,
    ): ProfileDatabase =
        synchronized(lock) {
            opened.getOrPut(instanceId to profileId) {
                makeDirectory(directory)
                Room
                    .databaseBuilder(context, ProfileDatabase::class.java, File(directory, DATABASE_FILE).path)
                    .buildProfileDatabase(queries)
            }
        }
}

/** [instanceId]'s directory for [profileId] under [root]: the profile's key is the SHA-256 of its id. */
private fun profileDirectory(
    root: File,
    instanceId: String,
    profileId: String,
): File {
    requireInstanceId(instanceId)
    require(profileId.isNotEmpty()) { "a profile's id is empty" }
    return File(File(root, instanceId), sha256Hex(profileId.encodeToByteArray()))
}

private fun requireInstanceId(instanceId: String) {
    require(SHA256_HEX.matches(instanceId)) { "an instance's id is a SHA-256 in lowercase hex" }
}
