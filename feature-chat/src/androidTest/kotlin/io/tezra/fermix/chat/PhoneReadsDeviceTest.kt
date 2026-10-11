package io.tezra.fermix.chat

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A file the test plants where the app keeps its records (AppServices: `noBackupFilesDir`), and what it holds. */
private const val PLANTED_NAME = "planted-records.pb"
private val PLANTED = byteArrayOf(9, 8, 7, 6)
private val PLANTED_SIZE = PLANTED.size.toLong()

/** A copy of a blob as Open leaves one under the cache's shared directory, which the chat's FileProvider serves. */
private const val SHARED_COPY = "shared/0f0f0f0f0f0f0f0f/$PLANTED_NAME"

/** A small photo's edge, and a name ART's path calls throw on: a lone surrogate. */
private const val PHOTO_EDGE = 8
private const val LONE_SURROGATE_NAME = "x\uD800.pdf"

/** Another app's image: a photo the test puts in the phone's media store and takes away (galleryImage). */
private const val GALLERY_NAME = "fermix-reads-test.png"

/**
 * What the chat reads of the phone's files on a device (design section 8.5, "Sources"): an item lands only from the
 * source that may hand it, a file the chat made under its cache from the camera alone and another app's content URI
 * from Photos, Files, Paste and the keyboard alone; past that check, a `file:` URI is read only where the chat made
 * the file, under its cache, and a content URI of the app's own provider never, whichever read asks, a camera's
 * capture as it lands, a send's copy and the tray's thumbnail; each refusal is a SecurityException that names no
 * path, and nothing is copied. A file of the chat's own under its cache is read by each, and a path with a lone
 * surrogate is weighed, never thrown on.
 */
class PhoneReadsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pipeline = PhoneMedia(context, { message, error -> Log.w("PhoneReadsDeviceTest", message, error) })
    private val planted = File(context.noBackupFilesDir, PLANTED_NAME)
    private val directory = File(context.cacheDir, "reads-test")
    private val sibling = File(checkNotNull(context.cacheDir.parentFile), "${context.cacheDir.name}2")
    private val shared = File(context.cacheDir, SHARED_COPY)
    private val link = File(directory, "out")

    @Before
    fun plant() {
        end()
        planted.writeBytes(PLANTED)
        listOf(directory, sibling).forEach { check(it.mkdirs()) { "$it could not be made" } }
        File(sibling, PLANTED_NAME).writeBytes(PLANTED)
        val copies = checkNotNull(shared.parentFile)
        check(copies.isDirectory || copies.mkdirs()) { "$copies could not be made" }
        shared.writeBytes(PLANTED)
        Files.createSymbolicLink(link.toPath(), context.noBackupFilesDir.toPath())
    }

    @After
    fun end() {
        // The link goes first, by itself: deleteRecursively would follow it into no_backup/.
        link.delete()
        planted.delete()
        listOf(directory, sibling, checkNotNull(shared.parentFile)).forEach { it.deleteRecursively() }
    }

    /**
     * Files outside the cache as `file:` URIs: the app's records, the same through a link the cache holds, and a
     * file in a sibling whose name begins as the cache's does.
     */
    private fun outsideFiles(): List<String> =
        listOf(planted, File(link, PLANTED_NAME), File(sibling, PLANTED_NAME)).map {
            Uri.fromFile(it).toString()
        }

    /** The chat's own provider's content URI of a shared copy, as is and with a `0@` prefix. */
    private fun ownProvider(): List<String> {
        val own = FileProvider.getUriForFile(context, filesAuthority(context), shared)
        return listOf(own, own.buildUpon().encodedAuthority("0@${own.encodedAuthority}").build()).map { it.toString() }
    }

    private fun picked(
        uri: String,
        kind: PickedKind = PickedKind.FILE,
    ): Picked = Picked("p1", uri, kind, "application/octet-stream", PLANTED_NAME, PLANTED_SIZE, PickedFrom.PHOTOS)

    /** [read] refused with a SecurityException that names no path. */
    private fun assertRefused(
        uri: String,
        read: () -> Unit,
    ) {
        val refused = assertThrows("$uri was read", SecurityException::class.java, read)
        val words = refused.message.orEmpty()
        listOf(PLANTED_NAME, "no_backup", sibling.name, "reads-test", "0f0f").forEach {
            assertFalse("a refusal names no path: $words", it in words)
        }
    }

    @Test
    fun a_cameras_capture_outside_the_cache_is_refused_as_it_lands() {
        for (uri in outsideFiles() + ownProvider()) {
            assertRefused(uri) { runBlocking { pipeline.describe(uri, PickedFrom.CAMERA) } }
        }
        assertArrayEquals(PLANTED, planted.readBytes())
    }

    @Test
    fun an_item_lands_only_from_the_source_that_may_hand_it() {
        val made = Uri.fromFile(shared).toString()
        for (from in listOf(PickedFrom.PASTE, PickedFrom.KEYBOARD, PickedFrom.PHOTOS, PickedFrom.FILES)) {
            assertRefused(made) { runBlocking { pipeline.describe(made, from) } }
        }
        assertNotNull("the camera's own lands", runBlocking { pipeline.describe(made, PickedFrom.CAMERA) })
        val gallery = galleryImage(context.contentResolver, GALLERY_NAME)
        try {
            val foreign = gallery.toString()
            assertRefused(foreign) { runBlocking { pipeline.describe(foreign, PickedFrom.CAMERA) } }
            assertNotNull("another app's lands", runBlocking { pipeline.describe(foreign, PickedFrom.PHOTOS) })
        } finally {
            context.contentResolver.delete(gallery, null, null)
        }
    }

    @Test
    fun a_send_copies_nothing_from_outside_the_cache_or_from_the_apps_own_provider() {
        val uris = outsideFiles() + ownProvider()
        uris.forEachIndexed { at, uri ->
            val into = File(directory, "prepared-$at")
            assertRefused(uri) { runBlocking { pipeline.prepare(picked(uri), asFile = true, into, SEND_LIMIT_BYTES) } }
            val image = picked(uri, PickedKind.IMAGE)
            assertRefused(uri) { runBlocking { pipeline.prepare(image, asFile = false, into, SEND_LIMIT_BYTES) } }
            assertRefused(uri) { runBlocking { pipeline.copyAtMost(picked(uri), into, LANDING_MAX_BYTES) } }
            assertFalse("$uri was copied", into.exists())
        }
    }

    @Test
    fun the_trays_thumbnail_reads_nothing_from_outside_the_cache_or_from_the_apps_own_provider() {
        for (uri in outsideFiles() + ownProvider()) {
            assertRefused(uri) { runBlocking { trayThumbnail(context, picked(uri, PickedKind.IMAGE)) } }
        }
    }

    @Test
    fun a_file_the_chat_made_under_its_cache_is_read_by_each() {
        val photo = File(directory, "capture.png")
        val bitmap = Bitmap.createBitmap(PHOTO_EDGE, PHOTO_EDGE, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(92, 139, 163))
            photo.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val uri = Uri.fromFile(photo).toString()
        val described = checkNotNull(runBlocking { pipeline.describe(uri, PickedFrom.CAMERA) }) { "$uri was not read" }
        assertEquals("capture.png" to photo.length(), described.name to described.sizeBytes)
        val into = File(directory, "prepared")
        runBlocking { pipeline.prepare(described, asFile = true, into, SEND_LIMIT_BYTES) }
        assertArrayEquals(photo.readBytes(), into.readBytes())
        // A landing copy reads it too, the whole of it within the limit, and a byte past a limit it is over.
        val landed = File(directory, "landed")
        assertEquals(photo.length(), runBlocking { pipeline.copyAtMost(described, landed, photo.length()) })
        assertArrayEquals(photo.readBytes(), landed.readBytes())
        assertEquals(2L, runBlocking { pipeline.copyAtMost(described, landed, 1L) })
        assertNotNull("the tray draws it", runBlocking { trayThumbnail(context, described) })
    }

    @Test
    fun a_path_with_a_lone_surrogate_is_weighed_and_never_thrown_on() {
        assertTrue(liesUnder(File(directory, LONE_SURROGATE_NAME), directory))
        assertFalse(liesUnder(File(context.noBackupFilesDir, LONE_SURROGATE_NAME), directory))
        assertFalse(liesUnder(File(directory, "../$LONE_SURROGATE_NAME/.."), directory))
    }
}
