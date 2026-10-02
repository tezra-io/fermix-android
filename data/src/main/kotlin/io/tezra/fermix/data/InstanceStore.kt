package io.tezra.fermix.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * This phone's paired daemons (design section 9.1), in the Chats list's order, with each one's files in
 * [databases]. Every change is one DataStore write; the files of a record that left go after it, on [io],
 * so a removal cut short leaves files no record names, which [launchCheck] deletes.
 */
class InstanceStore(
    private val records: DataStore<Instances>,
    private val databases: ProfileDatabases,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val instances: Flow<List<Instance>> = records.data.map { it.instances }

    /** The titles [launchCheck] dropped, which the app shows as "Re-pair this Fermix" until dismissed. */
    val repairNotices: Flow<List<String>> = records.data.map { it.repairNotices }

    /**
     * Records [paired] on `pair_approved` (design section 6.1). The same daemon paired again replaces its
     * record in place, which keeps the owner's nickname and tint and switches the key alias; a new daemon
     * is added last. Returns the record replaced, whose key alias the caller then deletes: a pairing always
     * brings a new alias, and one that does not is refused (InstanceRules.kt's planUpsert), so the alias
     * handed back is never the live one. A nickname [paired] brings is held to [rename]'s rule, which the
     * Paired screen asks [nicknameRefusal] beforehand; a refusal of either kind writes nothing. A change to a
     * record is [update].
     */
    suspend fun upsert(paired: Instance): Instance? {
        var replaced: Instance? = null
        records.updateData { current ->
            val plan = planUpsert(current.instances, paired)
            replaced = plan.replaced
            current.copy(instances = plan.instances)
        }
        return replaced
    }

    /**
     * Changes [id]'s record by [change] in one write: what the daemon reports after pairing (`hello_ack`'s
     * host, label, profile, candidates, caps and push platforms) and this phone's settings (notifications,
     * the last `push_register`). [change] runs inside the write, so it is pure and quick. What a pairing set
     * (the gateway key, the TLS pin, the device id, the key alias, the push salt) and the owner's nickname and
     * tint are refused, and nothing is written: a pairing is [upsert], a nickname [rename].
     */
    suspend fun update(
        id: String,
        change: (Instance) -> Instance,
    ) {
        records.updateData { current -> current.copy(instances = updated(current.instances, id, change)) }
    }

    /**
     * "Pair again" on the row [oldId] of a daemon that was reinstalled (design section 9.2): [paired], of the
     * row's profile, takes the row's place, nickname and tint, and the old daemon's databases and media are
     * deleted. Returns the old record, whose key alias the caller then deletes, so [paired] under that alias
     * is refused, as is a nickname it brings for a row that had none when another row is titled with it; a
     * refusal writes and deletes nothing.
     */
    suspend fun merge(
        oldId: String,
        paired: Instance,
    ): Instance {
        var replaced: Instance? = null
        records.updateData { current ->
            val plan = planMerge(current.instances, oldId, paired)
            replaced = plan.replaced
            current.copy(instances = plan.instances)
        }
        val old = checkNotNull(replaced) { "the merge replaced no record" }
        if (old.id != paired.id) deleteFiles(old.id)
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
        records.updateData { current ->
            require(current.instances.any { it.id == id }) { "no record is $id" }
            refusal = trimmed?.let { nicknameRefusal(it, id, current.instances) }
            if (refusal == null) current.copy(instances = renamed(current.instances, id, trimmed)) else current
        }
        return refusal
    }

    /** Removes [id]'s record, then its databases and media. */
    suspend fun remove(id: String) {
        records.updateData { current ->
            require(current.instances.any { it.id == id }) { "no record is $id" }
            current.copy(instances = current.instances.filter { it.id != id })
        }
        deleteFiles(id)
    }

    /** Moves [id]'s row to [toIndex]: "Move to top" is 0, and the Chats list's manual order (section 9.4). */
    suspend fun reorder(
        id: String,
        toIndex: Int,
    ) {
        records.updateData { current -> current.copy(instances = moved(current.instances, id, toIndex)) }
    }

    /** The owner has seen "Re-pair this Fermix": the notices go. */
    suspend fun dismissRepairNotices() {
        records.updateData { current -> current.copy(repairNotices = emptyList()) }
    }

    /**
     * Removes every record whose key alias is in [missingAliases] and notes each one's title among the repair
     * notices, in one write; then deletes their files, and returns them.
     */
    internal suspend fun dropForRepair(missingAliases: Set<String>): List<Instance> {
        var dropped = emptyList<Instance>()
        records.updateData { current ->
            val (gone, kept) = current.instances.partition { it.keyAlias in missingAliases }
            dropped = gone
            current.copy(instances = kept, repairNotices = current.repairNotices + gone.map { it.title })
        }
        dropped.forEach { deleteFiles(it.id) }
        return dropped
    }

    /** Deletes the files of every instance no record names. */
    internal suspend fun deleteUnrecordedFiles() {
        val ids =
            records.data
                .first()
                .instances
                .map { it.id }
                .toSet()
        withContext(io) { databases.deleteAllExcept(ids) }
    }

    private suspend fun deleteFiles(id: String) = withContext(io) { databases.delete(id) }
}
