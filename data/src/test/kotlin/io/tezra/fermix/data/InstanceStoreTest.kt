package io.tezra.fermix.data

import androidx.datastore.core.CorruptionException
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The instance records in their DataStore, with each instance's files beside them (design sections 9.1
 * and 9.2): what an upsert merges, a rename and a reorder keep, and a remove or a merge deletes.
 */
class InstanceStoreTest {
    @TempDir
    lateinit var directory: File

    private val root: File get() = File(directory, "instances")

    private fun TestScope.open(): Pair<InstanceStore, ProfileDatabases> {
        val databases = ProfileDatabases(TestContext, root)
        val records = instanceDataStore(File(directory, "instances.json"), backgroundScope)
        return InstanceStore(records, databases) to databases
    }

    /** Gives [instance] a cached row and a cached blob, so that its files exist. */
    private suspend fun fill(
        databases: ProfileDatabases,
        instance: Instance,
    ) {
        val row = HistoryMessage(1uL, "user", "hello", "2026-10-01T09:00:00Z", emptyList())
        databases.open(instance.id, PROFILE).timeline().persist(TimelineRow.Message(row))
        MediaCache(databases.mediaDirectory(instance.id, PROFILE), clock = { 0L }).put(BLOB, BLOB_SHA256)
    }

    @Test
    fun `an upsert adds the record, and the flow shows it`() =
        runTest {
            val (store, _) = open()
            assertNull(store.upsert(instance(gateway = 1)))
            assertNull(store.upsert(instance(gateway = 3, host = "linux-box")))
            assertEquals(
                listOf(instance(gateway = 1), instance(gateway = 3, host = "linux-box")),
                store.instances.first(),
            )
        }

    @Test
    fun `the same daemon paired again keeps its row, nickname, tint and files, and hands back the old record`() =
        runTest {
            val (store, databases) = open()
            val first = instance(gateway = 1, nickname = "Studio", tint = "Plum")
            store.upsert(first)
            fill(databases, first)
            val again = instance(gateway = 1, tint = "Ocean", keyAlias = "fermix.device.1.ffffffff")
            assertEquals(first, store.upsert(again))
            assertEquals(listOf(again.copy(nickname = "Studio", tint = "Plum")), store.instances.first())
            assertTrue(File(databases.mediaDirectory(first.id, PROFILE), BLOB_SHA256).isFile)
        }

    @Test
    fun `a reinstalled daemon merges into the row paired again, and the old row's databases and media are deleted`() =
        runTest {
            val (store, databases) = open()
            val old = instance(gateway = 1, nickname = "Studio", tint = "Plum")
            val other = instance(gateway = 3, host = "linux-box")
            store.upsert(old)
            store.upsert(other)
            fill(databases, old)
            fill(databases, other)
            val reinstalled = instance(gateway = 7, tint = "Ocean")
            assertEquals(old, store.merge(old.id, reinstalled))
            assertEquals(listOf(reinstalled.copy(nickname = "Studio", tint = "Plum"), other), store.instances.first())
            assertFalse(File(root, old.id).exists(), "the old row's files are still there")
            assertNull(databases.open(old.id, PROFILE).timeline().row(1uL))
            assertTrue(File(databases.mediaDirectory(other.id, PROFILE), BLOB_SHA256).isFile)
            assertTrue(databases.open(other.id, PROFILE).timeline().row(1uL) != null)
        }

    @Test
    fun `a pairing whose nickname another row is titled with is refused, and nothing is written or deleted`() =
        runTest {
            val (store, databases) = open()
            val mac = instance(gateway = 1, nickname = "Studio")
            val linux = instance(gateway = 3, host = "linux-box")
            store.upsert(mac)
            store.upsert(linux)
            fill(databases, linux)
            assertThrows<IllegalArgumentException> { store.upsert(instance(gateway = 5, nickname = "studio")) }
            val reinstalled = instance(gateway = 7, host = "linux-box", nickname = "Studio")
            assertThrows<IllegalArgumentException> { store.merge(linux.id, reinstalled) }
            assertEquals(listOf(mac, linux), store.instances.first())
            assertTrue(File(databases.mediaDirectory(linux.id, PROFILE), BLOB_SHA256).isFile)
        }

    @Test
    fun `a remove deletes the record, its databases and its media, and no other instance's`() =
        runTest {
            val (store, databases) = open()
            val removed = instance(gateway = 1)
            val kept = instance(gateway = 3, host = "linux-box")
            store.upsert(removed)
            store.upsert(kept)
            fill(databases, removed)
            fill(databases, kept)
            store.remove(removed.id)
            assertEquals(listOf(kept), store.instances.first())
            assertFalse(File(root, removed.id).exists(), "the removed instance's files are still there")
            assertTrue(File(databases.mediaDirectory(kept.id, PROFILE), BLOB_SHA256).isFile)
            assertThrows<IllegalArgumentException> { store.remove(removed.id) }
        }

    @Test
    fun `a rename keeps the nickname, a refused one changes nothing, and a reset clears it`() =
        runTest {
            val (store, _) = open()
            val mac = instance(gateway = 1, nickname = "Studio")
            val linux = instance(gateway = 3, host = "linux-box")
            store.upsert(mac)
            store.upsert(linux)
            assertNull(store.rename(linux.id, " Box "))
            assertEquals(NicknameRefusal.TAKEN, store.rename(linux.id, "studio"))
            assertEquals(NicknameRefusal.TOO_LONG, store.rename(linux.id, "x".repeat(41)))
            assertEquals(listOf(mac, linux.copy(nickname = "Box")), store.instances.first())
            assertNull(store.rename(mac.id, null))
            assertEquals(listOf(mac.copy(nickname = null), linux.copy(nickname = "Box")), store.instances.first())
            assertThrows<IllegalArgumentException> { store.rename("0".repeat(64), "Box") }
        }

    @Test
    fun `an update writes what hello_ack reports in place, and a refused one changes nothing`() =
        runTest {
            val (store, _) = open()
            val mac = instance(gateway = 1, nickname = "Studio")
            val linux = instance(gateway = 3, host = "linux-box")
            store.upsert(mac)
            store.upsert(linux)
            store.update(mac.id) { it.copy(host = "suj-mini", label = "Suj's Mini", notificationsEnabled = false) }
            val expected = mac.copy(host = "suj-mini", label = "Suj's Mini", notificationsEnabled = false)
            assertEquals(listOf(expected, linux), store.instances.first())
            assertThrows<IllegalArgumentException> { store.update(mac.id) { it.copy(keyAlias = "fermix.device.1.x") } }
            assertThrows<IllegalArgumentException> { store.update(mac.id) { it.copy(nickname = "Box") } }
            assertEquals(listOf(expected, linux), store.instances.first())
        }

    @Test
    fun `a records file that does not decode, or holds a record that breaks a rule, is reported and kept as it is`() =
        runTest {
            val written = ByteArrayOutputStream()
            InstancesSerializer.writeTo(Instances(listOf(instance(gateway = 1))), written)
            val valid = written.toByteArray().decodeToString()
            val badKey = valid.replace(base64(key(1)), base64(ByteArray(KEY_BYTES - 1)))
            assertTrue(badKey != valid, "the planted record still holds its gateway key")
            val files =
                listOf("{not json".encodeToByteArray(), badKey.encodeToByteArray(), byteArrayOf(0xC3.toByte(), 0x28))
            files.forEachIndexed { index, bytes ->
                val file = File(directory, "corrupt-$index.json").apply { writeBytes(bytes) }
                val store = InstanceStore(instanceDataStore(file, backgroundScope), ProfileDatabases(TestContext, root))
                val failure = runCatching { store.instances.first() }.exceptionOrNull()
                assertInstanceOf(CorruptionException::class.java, failure, "file $index")
                assertArrayEquals(bytes, file.readBytes(), "file $index was rewritten")
            }
        }

    @Test
    fun `move to top keeps the new order`() =
        runTest {
            val (store, _) = open()
            val first = instance(gateway = 1)
            val second = instance(gateway = 3, host = "linux-box")
            store.upsert(first)
            store.upsert(second)
            store.reorder(second.id, 0)
            assertEquals(listOf(second, first), store.instances.first())
        }

    private companion object {
        const val PROFILE = "main"
        val BLOB = "a cached image".encodeToByteArray()
        val BLOB_SHA256 = idOf(BLOB)
    }
}
