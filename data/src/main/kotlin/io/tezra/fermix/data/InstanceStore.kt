package io.tezra.fermix.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * This phone's paired daemons (design section 9.1), in the Chats list's order, with each one's files in
 * [databases] and its avatar's photo in [pictures]. Every change is one DataStore write; the files of a record that
 * left go after it, on [io], once [databases] has ended every reader of them (ProfileDatabases.delete), so a removal
 * cut short leaves files no record names, which [launchCheck] deletes. A pairing's write lets its daemon's files be
 * opened again first (ProfileDatabases.admit), as an earlier removal of the same daemon in this process refuses them.
 * Every read, once or following, is taken under the write lock ([lockedReads]), so none started during a write
 * keeps the records from before it once the write has ended, and every write runs on the store's own thread
 * ([locked]), so none waits for its caller's. The DataStore is the store's alone, made by it, so no code outside it
 * reads that `data`; the module's tests hand in one of their own.
 */
class InstanceStore internal constructor(
    private val records: DataStore<Instances>,
    private val databases: ProfileDatabases,
    val pictures: AvatarPictures,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * The records in [file], their writes run in [scope], each replacing the file in one rename (atomicDataStore),
     * and the avatars' photos in `avatars` beside it. DataStore allows one per file in a process, so the app makes the
     * store once; the file belongs in credential-encrypted storage that no backup or device transfer carries (design
     * section 6.6), such as `Context.noBackupFilesDir`.
     */
    constructor(file: File, scope: CoroutineScope, databases: ProfileDatabases) :
        this(atomicDataStore(file, InstancesSerializer, scope), databases, AvatarPictures(avatarsBeside(file)))

    private val kept: Flow<Instances> = lockedReads(records)

    val instances: Flow<List<Instance>> = kept.map { it.instances }

    /**
     * The instances [launchCheck] dropped, which the app shows as "Re-pair this Fermix" until a pairing brings
     * the same daemon back ([upsert], [merge]) or the owner removes the notice ([dismissRepairNotice]).
     */
    val repairNotices: Flow<List<RepairNotice>> = kept.map { it.repairNotices }

    /**
     * Records [paired] on `pair_approved` (design section 6.1). The same daemon paired again replaces its
     * record in place, which keeps the owner's nickname and tint and switches the key alias; a new daemon
     * is added last. Returns the record replaced, whose key alias the caller then deletes: a pairing always
     * brings a new alias, and one that does not is refused (InstanceRules.kt's planUpsert), so the alias
     * handed back is never the live one. A nickname [paired] brings is held to [rename]'s rule, which the
     * Paired screen asks [nicknameRefusal] beforehand; a refusal of either kind writes nothing. A change to a
     * record is [update]. The daemon's "Re-pair this Fermix" notice, if a launch dropped it, goes in the same
     * write.
     */
    suspend fun upsert(paired: Instance): Instance? {
        databases.admit(paired.id)
        var replaced: Instance? = null
        records.locked { current ->
            val plan = planUpsert(current.instances, paired)
            replaced = plan.replaced
            current.copy(instances = plan.instances, repairNotices = current.repairNotices.without(paired.id))
        }
        return replaced
    }

    /**
     * Changes [id]'s record by [change] in one write: what the daemon reports after pairing (`hello_ack`'s
     * host, label, profile, candidates, caps and push platforms) and this phone's settings (notifications,
     * the last `push_register`, a full pull due). [change] runs inside the write, so it is pure and quick. What
     * a pairing set (the gateway key, the TLS pin, the device id, the key alias, the push salt) and the owner's
     * nickname and tint are refused, and nothing is written: a pairing is [upsert], a nickname [rename]. False,
     * and nothing written, once no record is [id]: a removal may take it while a change is on its way, from a
     * session's event, a push or the system's word.
     */
    suspend fun update(
        id: String,
        change: (Instance) -> Instance,
    ): Boolean {
        var present = false
        records.locked { current ->
            present = current.instances.any { it.id == id }
            if (present) current.copy(instances = updated(current.instances, id, change)) else current
        }
        return present
    }

    /**
     * "Pair again" on the row [oldId] of a daemon that was reinstalled (design section 9.2): [paired], of the
     * row's profile, takes the row's place, nickname, tint and picture, and the old daemon's databases and media are
     * deleted. The picture's file is moved to [paired]'s id before the record that names it is written, so whatever
     * reads that record, the conversation's icon among them, finds it (Task 23's review); a merge the records refuse
     * moves nothing, asked of them first, and one refused as it is written, the records changed meanwhile, moves the
     * picture back. Returns the old record, whose key alias the caller then deletes, so [paired] under that alias is
     * refused, as is a nickname it brings for a row that had none when another row is titled with it; a refusal
     * writes and deletes nothing. A "Re-pair this Fermix" notice of [paired]'s daemon goes in the same write.
     */
    suspend fun merge(
        oldId: String,
        paired: Instance,
    ): Instance {
        databases.admit(paired.id)
        val moving = oldId != paired.id
        if (moving) {
            planMerge(kept.first().instances, oldId, paired)
            withContext(io) { pictures.move(oldId, paired.id) }
        }
        val old =
            try {
                records.writeMerge(oldId, paired)
            } catch (refused: IllegalArgumentException) {
                if (moving) withContext(NonCancellable + io) { pictures.move(paired.id, oldId) }
                throw refused
            }
        if (moving) deleteFilesOf(old.id, databases, pictures, io)
        return old
    }

    /**
     * Names [id]'s row [nickname], trimmed, or resets it to the daemon's own name with null (design section
     * 13.7). A refused nickname changes nothing, and the refusal is returned.
     */
    suspend fun rename(
        id: String,
        nickname: String?,
    ): NicknameRefusal? {
        val trimmed = nickname?.trim()
        var refusal: NicknameRefusal? = null
        records.locked { current ->
            require(current.instances.any { it.id == id }) { "no record is $id" }
            refusal = trimmed?.let { nicknameRefusal(it, id, current.instances) }
            if (refusal == null) current.copy(instances = renamed(current.instances, id, trimmed)) else current
        }
        return refusal
    }

    /**
     * The owner's [tint] for [id]'s avatar, one of [TINT_NAMES] (design section 13.1), any other refused before the
     * write. False, and nothing written, once no record is [id].
     */
    suspend fun setTint(
        id: String,
        tint: String,
    ): Boolean {
        require(tint in TINT_NAMES) { "tint '$tint' is not one of $TINT_NAMES" }
        return records.changeOwned(id) { it.copy(tint = tint) }
    }

    /**
     * When the owner set [id]'s photo, its file already written by [pictures], or none once the photo is removed, whose
     * file then goes. False, and nothing written, once no record is [id]: a removal took it, and its files with it,
     * and a photo whose write ended after that goes too, here, rather than wait for the launch check's sweep.
     */
    suspend fun setPicture(
        id: String,
        at: Long?,
    ): Boolean {
        val present = records.changeOwned(id) { it.copy(pictureAt = at) }
        if (!present || at == null) withContext(io) { pictures.delete(id) }
        return present
    }

    /** Removes [id]'s record, then its databases, media and picture. */
    suspend fun remove(id: String) {
        records.locked { current ->
            require(current.instances.any { it.id == id }) { "no record is $id" }
            current.copy(instances = current.instances.filter { it.id != id })
        }
        deleteFilesOf(id, databases, pictures, io)
    }

    /** Moves [id]'s row to [toIndex]: "Move to top" is 0, and the Chats list's manual order (section 9.4). */
    suspend fun reorder(
        id: String,
        toIndex: Int,
    ) {
        records.locked { current -> current.copy(instances = moved(current.instances, id, toIndex)) }
    }

    /** The owner has seen "Re-pair this Fermix": the notices go. */
    suspend fun dismissRepairNotices() {
        records.locked { current -> current.copy(repairNotices = emptyList()) }
    }

    /** The owner removed the "Re-pair this Fermix" row of the dropped instance [id]: its notice goes. */
    suspend fun dismissRepairNotice(id: String) {
        records.locked { current ->
            require(current.repairNotices.any { it.id == id }) { "no repair notice names $id" }
            current.copy(repairNotices = current.repairNotices.without(id))
        }
    }

    /**
     * The launch check's write ([launchCheck]): removes every record whose key alias is in [missingAliases] and notes
     * each one, by its id and its title, among the repair notices, in one write, and deletes their files; then deletes
     * the files and the picture of every instance no record names, and each recorded one's staged uploads its outbox no
     * longer names (StagedUploads). Returns the records dropped.
     */
    internal suspend fun dropAndSweep(missingAliases: Set<String>): List<Instance> {
        var dropped = emptyList<Instance>()
        records.locked { current ->
            val (gone, kept) = current.instances.partition { it.keyAlias in missingAliases }
            dropped = gone
            val noticed = gone.map { RepairNotice(it.id, it.title) }
            current.copy(instances = kept, repairNotices = current.repairNotices + noticed)
        }
        dropped.forEach { deleteFilesOf(it.id, databases, pictures, io) }
        val ids =
            instances
                .first()
                .map { it.id }
                .toSet()
        withContext(io) {
            databases.deleteAllExcept(ids)
            pictures.deleteAllExcept(ids)
            ids.forEach { databases.sweepStagedUploads(it, MAIN_PROFILE) }
        }
        return dropped
    }
}

/** A merge's one write: [paired] in [oldId]'s row, refused as [planMerge] says. Returns the old record. */
private suspend fun DataStore<Instances>.writeMerge(
    oldId: String,
    paired: Instance,
): Instance {
    var replaced: Instance? = null
    locked { current ->
        val plan = planMerge(current.instances, oldId, paired)
        replaced = plan.replaced
        current.copy(instances = plan.instances, repairNotices = current.repairNotices.without(paired.id))
    }
    return checkNotNull(replaced) { "the merge replaced no record" }
}

/** Deletes [id]'s databases and media, once [databases] has ended every reader of them, then its picture, on [io]. */
private suspend fun deleteFilesOf(
    id: String,
    databases: ProfileDatabases,
    pictures: AvatarPictures,
    io: CoroutineDispatcher,
) = withContext(io) {
    databases.delete(id)
    pictures.delete(id)
}

/**
 * [id]'s record changed by [change], what only the owner sets, in one write of these records; false, and nothing
 * written, once no record is [id].
 */
private suspend fun DataStore<Instances>.changeOwned(
    id: String,
    change: (Instance) -> Instance,
): Boolean {
    var present = false
    locked { current ->
        present = current.instances.any { it.id == id }
        val changed = current.instances.map { if (it.id == id) change(it) else it }
        if (present) current.copy(instances = changed) else current
    }
    return present
}

/** The avatars' directory beside [records], the records file: credential-encrypted as it is, and never backed up. */
private fun avatarsBeside(records: File): File {
    val parent = requireNotNull(records.absoluteFile.parentFile) { "$records has no directory" }
    return File(parent, AVATARS_DIRECTORY)
}

/** The notices without [id]'s. */
private fun List<RepairNotice>.without(id: String): List<RepairNotice> = filter { it.id != id }
