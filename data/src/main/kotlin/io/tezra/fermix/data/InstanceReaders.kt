package io.tezra.fermix.data

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The order between an instance's readers and the deletion of its files, kept for ProfileDatabases, which
 * opens what they read: the instances deleted in this process, which nothing opens until a pairing admits
 * them again, and how many readers each instance has, each counted from its start to its end. A removal
 * marks its instance gone, which ends the flows reading it ([untilGone]) and refuses every reader after it,
 * waits for the count to reach zero, and only then deletes ([remove]).
 */
internal class InstanceReaders {
    private val lock = Any()

    /** The instances deleted, or being deleted, in this process. */
    private val gone = MutableStateFlow<Set<String>>(emptySet())

    /** The instances being deleted now, whose pairing [admit] refuses until that is done. */
    private val deleting = HashSet<String>()

    /** How many readers each instance has now; one with none is not in the map. */
    private val counts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** What [open] opens, under the lock, none once [instanceId] is gone. */
    fun <R : Any> ifPresent(
        instanceId: String,
        open: () -> R,
    ): R? = synchronized(lock) { if (instanceId in gone.value) null else open() }

    /** [ifPresent], its caller counted as a reader of [instanceId] until it calls [leave]. */
    fun <R : Any> enter(
        instanceId: String,
        open: () -> R,
    ): R? =
        synchronized(lock) {
            val opened = ifPresent(instanceId, open) ?: return null
            counts.update { all -> all + (instanceId to (all[instanceId] ?: 0) + 1) }
            opened
        }

    fun leave(instanceId: String) {
        synchronized(lock) {
            counts.update { all ->
                val left = checkNotNull(all[instanceId]) { "$instanceId has no reader to leave" } - 1
                if (left == 0) all - instanceId else all + (instanceId to left)
            }
        }
    }

    /** A pairing brings [instanceId] back: nothing refuses it any more. One while it is being deleted fails loud. */
    fun admit(instanceId: String) {
        synchronized(lock) {
            check(instanceId !in deleting) { "$instanceId's files are being deleted" }
            gone.update { it - instanceId }
        }
    }

    /**
     * Runs [read] until it ends or [instanceId] is gone, which cancels it; returns only once [read] has ended,
     * whatever ends it, so its last query is over.
     */
    suspend fun untilGone(
        instanceId: String,
        read: suspend () -> Unit,
    ) = coroutineScope {
        val reading = launch { read() }
        val ending = launch { gone.first { instanceId in it }.also { reading.cancel() } }
        reading.join()
        ending.cancel()
    }

    /**
     * Marks [instanceId] gone, waits for its readers to end, at most [RELEASE_WAIT_MILLIS], and then runs
     * [deletion]. A reader still there then fails it loud, and [deletion] does not run.
     */
    suspend fun remove(
        instanceId: String,
        deletion: () -> Unit,
    ) {
        synchronized(lock) {
            check(deleting.add(instanceId)) { "$instanceId's files are being deleted already" }
            gone.update { it + instanceId }
        }
        try {
            val released = withTimeoutOrNull(RELEASE_WAIT_MILLIS) { counts.first { instanceId !in it } }
            checkNotNull(released) {
                "$instanceId keeps ${counts.value[instanceId]} readers past $RELEASE_WAIT_MILLIS ms; its files stay"
            }
            deletion()
        } finally {
            synchronized(lock) { deleting.remove(instanceId) }
        }
    }
}
