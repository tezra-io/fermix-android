package io.tezra.fermix.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * A DataStore over [file], its writes run in [scope], each of which puts the written file in place of the old one
 * in one rename: OkioStorage moves its scratch file with Okio's atomicMove, `Files.move` with `ATOMIC_MOVE`, which is
 * rename(2). DataStore 1.2.1's own file storage moves it with `Files.move` without `ATOMIC_MOVE` on Android 8 and
 * later (FileMoves.android.kt), and Android's `Files.move` then deletes the file before it renames the scratch one
 * over it (libcore's UnixCopyFile.move), so on every write there is an instant with no file: a read of the file then
 * finds none and answers with the serializer's default, no paired Fermix or the app lock off, and a process that dies
 * then leaves no file, which the next launch reads the same way. OkioStorage syncs the scratch file as the
 * serializer's `writeTo` returns, before the move, so a serializer emits what it wrote before it returns: what its sink
 * still buffers reaches the file only after that sync, and a power cut after the move could then leave an empty or a
 * partial file in the old one's place (SyncedWritesTest). One per file in a process, as DataStore allows.
 */
internal fun <T> atomicDataStore(
    file: File,
    serializer: OkioSerializer<T>,
    scope: CoroutineScope,
): DataStore<T> =
    DataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, serializer) { file.absoluteFile.toOkioPath() },
        scope = scope,
    )

/**
 * [transform] applied to this store's data in one write under its lock, run [inPlace], on the store's own thread,
 * never in the caller's context, where DataStore 1.2.1 runs a transform while it holds the lock: every read waits
 * for that lock too ([lockedReads]), so a write from the main thread would keep each read waiting for the main
 * thread, and a main thread that blocks on a read meanwhile never gets it, as a Robolectric test's did. A transform
 * is pure and quick, and one that hands back what it was given writes nothing. [inPlace] is a parameter because
 * detekt's InjectDispatcher takes no dispatcher named in a function's body; no caller passes another.
 */
internal suspend fun <T> DataStore<T>.locked(
    inPlace: CoroutineDispatcher = Dispatchers.Unconfined,
    transform: (T) -> T,
): T = withContext(inPlace) { updateData { transform(it) } }

/**
 * [store]'s data as the writes before each read left it: every value is read under the store's write lock
 * ([locked], handing back what it was given); `data` only says when to read again. DataStore's own `data` is never
 * read for its values: one that starts while a write is under way, after the write has raised the store's version
 * and before its cache holds the written data, takes neither the lock nor the cache but reads the file, stamps what
 * it read with the new version and then drops the written data as no newer, so it shows the old data until the next
 * write (ReadsDuringWritesTest).
 */
internal fun <T> lockedReads(store: DataStore<T>): Flow<T> =
    store.data.map { store.locked { held -> held } }.distinctUntilChanged()
