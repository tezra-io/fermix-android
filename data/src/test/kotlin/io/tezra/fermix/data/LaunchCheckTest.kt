package io.tezra.fermix.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The launch check (design sections 6.4 and 6.6, onboarding section 5 `data`): an app restored or moved
 * without its Keystore keys finds their aliases missing, and each such instance is dropped with its files,
 * its title kept for "Re-pair this Fermix" until the owner has seen it; an instance whose alias is there is
 * untouched.
 */
class LaunchCheckTest {
    @TempDir
    lateinit var directory: File

    private val root: File get() = File(directory, "instances")

    private val recordsFile: File get() = File(directory, "instances.json")

    private fun TestScope.open(): Pair<InstanceStore, ProfileDatabases> {
        val databases = ProfileDatabases(TestContext, root)
        val records = instanceDataStore(recordsFile, backgroundScope)
        return InstanceStore(records, databases) to databases
    }

    /** The records as the file holds them, read past the DataStore, as the next process would read them. */
    private suspend fun onDisk(): Instances = recordsFile.inputStream().use { InstancesSerializer.readFrom(it) }

    @Test
    fun `an instance whose key alias is gone is dropped with its files, and one whose alias is there is untouched`() =
        runTest {
            val (store, databases) = open()
            val lost = instance(gateway = 1, nickname = "Studio", keyAlias = "fermix.device.1.lost")
            val kept = instance(gateway = 3, host = "linux-box", keyAlias = "fermix.device.3.kept")
            store.upsert(lost)
            store.upsert(kept)
            databases.open(lost.id, "main")
            databases.open(kept.id, "main")
            // The Keystore, as the check asks it: one function, so this test hands in a map.
            val keystore = mapOf("fermix.device.3.kept" to true)
            val aliasExists = { alias: String -> keystore[alias] == true }

            assertEquals(listOf(lost), launchCheck(store, aliasExists))
            assertEquals(listOf(kept), store.instances.first())
            assertFalse(File(root, lost.id).exists(), "the lost instance's files are still there")
            assertTrue(File(root, kept.id).isDirectory, "the kept instance's files are gone")
            // A second launch finds nothing to drop.
            assertEquals(emptyList<Instance>(), launchCheck(store, aliasExists))
            assertEquals(listOf(kept), store.instances.first())
        }

    @Test
    fun `the Fermixes to re-pair are written with the drop, so a process that dies before showing them loses none`() =
        runTest {
            val (store, _) = open()
            val studio = instance(gateway = 1, nickname = "Studio", keyAlias = "fermix.device.1.lost")
            val linux = instance(gateway = 3, host = "linux-box", keyAlias = "fermix.device.3.lost")
            store.upsert(studio)
            store.upsert(linux)
            launchCheck(store) { false }
            val notices = listOf(RepairNotice(studio.id, "Studio"), RepairNotice(linux.id, "linux-box"))
            assertEquals(notices, store.repairNotices.first())
            assertEquals(notices, onDisk().repairNotices)
            store.dismissRepairNotices()
            assertEquals(emptyList<RepairNotice>(), store.repairNotices.first())
            assertEquals(emptyList<RepairNotice>(), onDisk().repairNotices)
        }

    @Test
    fun `a notice the owner removes goes alone, and an instance no notice names is refused`() =
        runTest {
            val (store, _) = open()
            val studio = instance(gateway = 1, nickname = "Studio", keyAlias = "fermix.device.1.lost")
            val linux = instance(gateway = 3, host = "linux-box", keyAlias = "fermix.device.3.lost")
            store.upsert(studio)
            store.upsert(linux)
            launchCheck(store) { false }
            store.dismissRepairNotice(studio.id)
            assertEquals(listOf(RepairNotice(linux.id, "linux-box")), store.repairNotices.first())
            assertEquals(listOf(RepairNotice(linux.id, "linux-box")), onDisk().repairNotices)
            assertThrows<IllegalArgumentException> { store.dismissRepairNotice(studio.id) }
        }

    @Test
    fun `two dropped Fermixes of one title keep a notice each, and pairing one again takes its own alone`() =
        runTest {
            val (store, _) = open()
            // The canon's production and dev daemons on one computer, both titled suj-mbp.
            val production = instance(gateway = 1, keyAlias = "fermix.device.1.lost")
            val dev = instance(gateway = 2, keyAlias = "fermix.device.2.lost")
            store.upsert(production)
            store.upsert(dev)
            launchCheck(store) { false }
            assertEquals(
                listOf(RepairNotice(production.id, "suj-mbp"), RepairNotice(dev.id, "suj-mbp")),
                store.repairNotices.first(),
            )
            store.upsert(production.copy(keyAlias = "fermix.device.1.new"))
            assertEquals(listOf(RepairNotice(dev.id, "suj-mbp")), store.repairNotices.first())
            assertEquals(listOf(RepairNotice(dev.id, "suj-mbp")), onDisk().repairNotices)
        }

    @Test
    fun `a dropped Fermix titled as a kept one keeps its notice until its own daemon comes back, by a merge too`() =
        runTest {
            val (store, _) = open()
            val kept = instance(gateway = 3, keyAlias = "fermix.device.3.kept")
            val lost = instance(gateway = 1, keyAlias = "fermix.device.1.lost")
            store.upsert(kept)
            store.upsert(lost)
            launchCheck(store) { it == kept.keyAlias }
            // The kept record is paired again: the notice of the other daemon of its title stays.
            store.upsert(kept.copy(keyAlias = "fermix.device.3.again"))
            assertEquals(listOf(RepairNotice(lost.id, "suj-mbp")), store.repairNotices.first())
            // "Pair again" on the kept row brings the lost daemon into it: its notice goes in the same write.
            store.merge(kept.id, lost.copy(keyAlias = "fermix.device.1.merged"))
            assertEquals(emptyList<RepairNotice>(), store.repairNotices.first())
            assertEquals(listOf(lost.id), onDisk().instances.map { it.id })
        }

    @Test
    fun `files no record names, left by a removal cut short, are deleted, and nothing else under the root`() =
        runTest {
            val (store, databases) = open()
            val kept = instance(gateway = 3, host = "linux-box")
            store.upsert(kept)
            databases.open(kept.id, "main")
            val orphan = instance(gateway = 1)
            databases.open(orphan.id, "main")
            val stranger = File(root, "not-an-instance").apply { mkdirs() }

            assertEquals(emptyList<Instance>(), launchCheck(store) { true })
            assertFalse(File(root, orphan.id).exists(), "the orphaned files are still there")
            assertTrue(File(root, kept.id).isDirectory, "the recorded instance's files are gone")
            assertTrue(stranger.isDirectory, "a directory that names no instance was deleted")
        }

    @Test
    fun `a Keystore that fails the question fails the check, and no record is dropped`() =
        runTest {
            val (store, _) = open()
            store.upsert(instance(gateway = 1))
            val failure = runCatching { launchCheck(store) { error("the Keystore is unavailable") } }.exceptionOrNull()
            assertEquals("the Keystore is unavailable", failure?.message)
            assertEquals(listOf(instance(gateway = 1)), store.instances.first())
        }
}
