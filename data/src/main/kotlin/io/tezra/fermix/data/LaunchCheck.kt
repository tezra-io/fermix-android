package io.tezra.fermix.data

import kotlinx.coroutines.flow.first

/**
 * The launch-time check (design sections 6.4 and 6.6, onboarding section 5 `data`). The device keys never
 * leave the phone's Keystore, so an app whose data was restored or moved without them finds their aliases
 * missing. Every instance whose key alias [aliasExists] does not find is dropped with its databases and
 * media, which only that key could reach, and returned; its id and title are kept among
 * [InstanceStore.repairNotices] in the same write, so the app shows "Re-pair this Fermix" with it even after a
 * process death. Files that no record names any more, left by a removal cut short, are deleted too, and so is
 * each staged upload its outbox no longer names (StagedUploads). Run it at launch, before any session opens
 * and before anything shows, since a database is kept only while its record is and a chat stages its uploads.
 *
 * [aliasExists] is the one Keystore question this module asks, injected so that the JVM tests pass a map. It
 * is asked before the write, never inside it, so the write holds no Keystore call; a record paired meanwhile
 * holds a new alias, which is not among those found missing, and stays.
 */
suspend fun launchCheck(
    store: InstanceStore,
    aliasExists: (String) -> Boolean,
): List<Instance> {
    val missing =
        store.instances
            .first()
            .map { it.keyAlias }
            .filterNot(aliasExists)
            .toSet()
    val dropped = store.dropForRepair(missing)
    store.deleteUnrecordedFiles()
    return dropped
}
