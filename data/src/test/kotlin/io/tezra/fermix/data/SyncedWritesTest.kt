package io.tezra.fermix.data

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [delegate], whose files opened to be written note in [synced] how many bytes each holds as it is synced to the
 * disk, which DataStore's OkioStorage does once per write, before it moves the written file in place.
 */
private class SyncsSeen(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    val synced = mutableListOf<Long>()

    override fun openReadWrite(
        file: Path,
        mustCreate: Boolean,
        mustExist: Boolean,
    ): FileHandle = SyncSeen(super.openReadWrite(file, mustCreate, mustExist), synced)
}

/** [inner], which adds its size to [synced] each time it is synced. */
private class SyncSeen(
    private val inner: FileHandle,
    private val synced: MutableList<Long>,
) : FileHandle(inner.readWrite) {
    override fun protectedRead(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ): Int = inner.read(fileOffset, array, arrayOffset, byteCount)

    override fun protectedWrite(
        fileOffset: Long,
        array: ByteArray,
        arrayOffset: Int,
        byteCount: Int,
    ) = inner.write(fileOffset, array, arrayOffset, byteCount)

    override fun protectedFlush() {
        synced.add(inner.size())
        inner.flush()
    }

    override fun protectedResize(size: Long) = inner.resize(size)

    override fun protectedSize(): Long = inner.size()

    override fun protectedClose() = inner.close()
}

/** [file]'s size after each of [writes], made one by one to a store of [serializer]'s over [disk]. */
private suspend fun <T> sizesAfter(
    file: File,
    serializer: OkioSerializer<T>,
    disk: FileSystem,
    scope: CoroutineScope,
    writes: List<(T) -> T>,
): List<Long> {
    val store = DataStoreFactory.create(OkioStorage(disk, serializer) { file.absoluteFile.toOkioPath() }, scope = scope)
    return writes.map { change ->
        store.updateData { change(it) }
        file.length()
    }
}

/**
 * A write of the records or the settings is whole on the disk before it takes the old file's place: OkioStorage
 * syncs the written file as the serializer returns, before it moves the file in place, and a byte the serializer
 * leaves in Okio's buffer reaches the file only after that sync, so a serializer that does not emit what it wrote
 * has an empty file synced, and a power cut after the move can leave an empty or a partial file in its place.
 */
class SyncedWritesTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `each write of the records is all in its file when the file is synced`() =
        runTest {
            val disk = SyncsSeen(FileSystem.SYSTEM)
            val writes: List<(Instances) -> Instances> =
                listOf(
                    { Instances(listOf(instance(gateway = 1))) },
                    { it.copy(instances = it.instances + instance(gateway = 3, host = "linux-box")) },
                )
            val file = File(directory, "instances.json")
            val sizes = sizesAfter(file, InstancesSerializer, disk, backgroundScope, writes)
            assertTrue(sizes.size == writes.size && sizes.all { it > 0 }, "$sizes")
            assertEquals(sizes, disk.synced)
        }

    @Test
    fun `each write of the settings is all in its file when the file is synced`() =
        runTest {
            val disk = SyncsSeen(FileSystem.SYSTEM)
            val writes: List<(AppSettings) -> AppSettings> =
                listOf(
                    { it.copy(appLock = true) },
                    { it.copy(lastChat = ChatRef(idOf(key(1)), MAIN_PROFILE)) },
                )
            val file = File(directory, "settings.json")
            val sizes = sizesAfter(file, AppSettingsSerializer, disk, backgroundScope, writes)
            assertTrue(sizes.size == writes.size && sizes.all { it > 0 }, "$sizes")
            assertEquals(sizes, disk.synced)
        }
}
