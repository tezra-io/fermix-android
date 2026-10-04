package io.tezra.fermix.data

import io.tezra.fermix.protocol.AttachKind
import io.tezra.fermix.protocol.ClientEvent
import io.tezra.fermix.session.OutboxAttachment
import io.tezra.fermix.session.OutboxItem
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A chat's files beside its profile's database (design sections 8.5 and 13.6): a send its session never took lets
 * go of the staged files no outbox item names, and keeps one an item does; the voice draft's file sits in the
 * profile's directory, so it outlives the chat and goes with the instance.
 */
class ChatFilesTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `releasing a send's staged files deletes one no item names and keeps one an item names`() =
        runTest {
            val databases = ProfileDatabases(TestContext, File(directory, "instances"))
            val record = instance(gateway = 3)
            val database = databases.open(record.id, MAIN_PROFILE)
            val staged = databases.stagedUploads(record.id, MAIN_PROFILE)
            val named = staged.stage(File(directory, "picked").apply { writeBytes(byteArrayOf(1)) }, "a1")
            val orphan = staged.stage(File(directory, "left").apply { writeBytes(byteArrayOf(2)) }, "a2")
            val photo = OutboxAttachment("a1", AttachKind.IMAGE, "image/jpeg", 1L, "cd".repeat(32), null, named.path)
            val request = ClientEvent.Msg("m1", MAIN_PROFILE, "", listOf("a1"))
            RoomSessionStore(database, staged).enqueue(OutboxItem(request, attachments = listOf(photo)))

            val paths = listOf(named.path, orphan.path)
            assertEquals(Use.Ran(Unit), databases.releaseStagedUploads(record.id, MAIN_PROFILE, paths))
            assertTrue(named.isFile, "a file the outbox names was released")
            assertFalse(orphan.exists(), "a file no item names is still there")
        }

    @Test
    fun `the voice draft's file is the profile's, and goes with its instance`() =
        runTest {
            val databases = ProfileDatabases(TestContext, File(directory, "instances"))
            val record = instance(gateway = 3)
            val draft = (databases.voiceDraft(record.id, MAIN_PROFILE) as Use.Ran).value
            val profile = checkNotNull(draft.parentFile)
            assertTrue(profile.isDirectory, "the draft's directory was not made")
            assertEquals(File(File(directory, "instances"), record.id), profile.parentFile)
            draft.writeBytes(byteArrayOf(1))
            assertEquals(draft, (databases.voiceDraft(record.id, MAIN_PROFILE) as Use.Ran).value)
            databases.delete(record.id)
            assertFalse(draft.exists(), "the draft outlived its instance")
            assertEquals(Use.Gone, databases.voiceDraft(record.id, MAIN_PROFILE))
        }
}
