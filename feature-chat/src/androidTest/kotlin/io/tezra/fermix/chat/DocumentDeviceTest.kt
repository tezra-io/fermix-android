package io.tezra.fermix.chat

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import io.tezra.fermix.protocol.HistoryMessage
import io.tezra.fermix.protocol.MediaRef
import io.tezra.fermix.session.TimelineRow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** The agent's answer that carries the document: the row after the host's thread. */
private const val DOCUMENT_ROW = THREAD_ROWS + 1

/** A raw HTML page's words, which the chat never draws. */
private const val PAGE = "<html><body><script>alert('drawn')</script>The export report</body></html>"

/** A ref the wire allows (PROTOCOL.md gives `ref` no form) that climbs from the cache's shared/ to no_backup/. */
private const val CLIMBING_REF = "../../no_backup/"

/** A file the test plants where the app keeps its records, and what it holds. */
private const val PLANTED_NAME = "planted-records.pb"
private val PLANTED = byteArrayOf(9, 8, 7, 6)

/** A name the wire allows that no filesystem holds: a lone surrogate, which ART's path calls throw on. */
private const val LONE_SURROGATE_NAME = "x\uD800.pdf"

/** Where Save puts a document and an image (design section 13.7), as the media store's RELATIVE_PATH says them. */
private const val SAVED_DOCUMENTS = "Download/Fermix/"
private const val SAVED_IMAGES = "Pictures/Fermix/"

/** A small image's edge. */
private const val IMAGE_EDGE = 12

/** The most entries of one name the test puts in Download/Fermix waiting for the media store to refuse the name. */
private const val MAX_SAME_NAME = 64

/** The tag the test logs what it saw under. */
private const val TEST_TAG = "DocumentDeviceTest"

/** A PNG of one colour, which the chat decodes and draws. */
private fun png(): ByteArray {
    val bitmap = Bitmap.createBitmap(IMAGE_EDGE, IMAGE_EDGE, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.rgb(92, 139, 163))
        return ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "the image did not compress" }
            out.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}

/**
 * A document from the agent on a device (design sections 13.5 and 13.7): a tap downloads it and hands it to the
 * chooser as a read-only content URI of the app's own FileProvider, whose bytes are the blob's; raw HTML goes the
 * same way, as a row with its name, never drawn in the chat; Share hands the same URI to the share sheet, and Save
 * writes it into Download/Fermix. A ref and a name from the wire never name the copy's path, nor stop the app: one
 * that climbs out of the cache is copied under its shared directory all the same, a name no filesystem holds is
 * copied under one it does, an image whose type is no image's is saved as a document, and a save of a name the
 * media store can number no further is refused.
 */
class DocumentDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ChatTestActivity>()

    private fun words(id: Int): String = rule.activity.getString(id)

    /** The agent's row holding the document [name] of type [mime] as [ref], its bytes [bytes] on the daemon. */
    private fun documentArrives(
        name: String,
        mime: String,
        bytes: ByteArray,
        ref: String = "blob-$name",
    ) {
        val rig = rule.activity.rig
        val media = MediaRef(ref, "file", mime, bytes.size.toLong(), filename = name)
        rig.session.blobs = mapOf(ref to bytes)
        val row =
            TimelineRow.Message(
                HistoryMessage(DOCUMENT_ROW.toULong(), "assistant", "", at(DOCUMENT_ROW.toLong()), listOf(media)),
            )
        rig.store.rows.update { listOf(row) + it }
        rule.waitUntil("the document's row shows", STEP_MILLIS) {
            rule.onAllNodes(hasText(name)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The document tapped: the chooser the chat starts, and the intent it offers. */
    private fun opened(name: String): Pair<Intent, Intent> {
        rule.onNodeWithText(name).performClick()
        return chooserStarted()
    }

    /** The chooser the chat started, and the intent it offers. */
    private fun chooserStarted(): Pair<Intent, Intent> {
        val started = rule.activity.rig.started
        rule.waitUntil("the chooser starts", STEP_MILLIS) { started.isNotEmpty() }
        val chooser = started.single()
        val offered = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)) { "$chooser" }
        return chooser to offered
    }

    private fun assertOffered(
        view: Intent,
        mime: String,
        bytes: ByteArray,
    ) {
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(mime, view.type)
        assertServed(checkNotNull(view.data), view.flags, bytes)
    }

    /** [uri] is the app's own FileProvider's, granted read-only by [flags], and serves [bytes]. */
    private fun assertServed(
        uri: Uri,
        flags: Int,
        bytes: ByteArray,
    ) {
        assertEquals("content", uri.scheme)
        assertEquals(filesAuthority(rule.activity), uri.authority)
        assertTrue("read-only", flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue("never writable", flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        val served = checkNotNull(rule.activity.contentResolver.openInputStream(uri)).use { it.readBytes() }
        assertArrayEquals(bytes, served)
    }

    private fun downloads(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun images(): Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** The entries the app saved into [collection] as [name] in [relative]. */
    private fun savedAs(
        collection: Uri,
        relative: String,
        name: String,
    ): List<Uri> = entries(collection, "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", relative, name)

    /** The entries the app saved into [collection] as [name] in [relative], or as the media store numbers it. */
    private fun numberedAs(
        collection: Uri,
        relative: String,
        name: String,
    ): List<Uri> {
        val numbered = "${name.substringBeforeLast('.')} (%).${name.substringAfterLast('.')}"
        val named = MediaStore.MediaColumns.DISPLAY_NAME
        return entries(collection, "($named = ? OR $named LIKE ?)", relative, name, numbered)
    }

    /** The entries of [collection] in [relative] whose name [selection] picks with [names]. */
    private fun entries(
        collection: Uri,
        selection: String,
        relative: String,
        vararg names: String,
    ): List<Uri> {
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND $selection"
        val columns = arrayOf(MediaStore.MediaColumns._ID)
        val cursor = rule.activity.contentResolver.query(collection, columns, where, arrayOf(relative, *names), null)
        return checkNotNull(cursor) { "the media store answered no query" }.use {
            List(it.count) { at ->
                check(it.moveToPosition(at)) { "the media store lost an entry" }
                ContentUris.withAppendedId(collection, it.getLong(0))
            }
        }
    }

    /** Every entry the app saved as [name] or its numbered names deleted, from Download/ and Pictures/Fermix. */
    private fun unsaved(name: String) {
        val saved = numberedAs(downloads(), SAVED_DOCUMENTS, name) + numberedAs(images(), SAVED_IMAGES, name)
        saved.forEach { rule.activity.contentResolver.delete(it, null, null) }
    }

    /**
     * Download/Fermix filled with documents named [name], as Save writes them, until the media store can number the
     * name no further: how many it holds then.
     */
    private fun filledWith(
        name: String,
        bytes: ByteArray,
    ): Int {
        repeat(MAX_SAME_NAME) { held ->
            if (!savedAgain(name, bytes)) return held
        }
        error("the media store took $MAX_SAME_NAME documents named $name and refused none")
    }

    /**
     * One more document named [name] in Download/Fermix, written as Save writes it, pending until it is whole; false
     * when the media store refused to publish it, the refusal this test waits for, and its entry is deleted again.
     */
    private fun savedAgain(
        name: String,
        bytes: ByteArray,
    ): Boolean {
        val resolver = rule.activity.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, SAVED_DOCUMENTS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val entry = checkNotNull(resolver.insert(downloads(), values)) { "the media store took no document" }
        var published = false
        try {
            checkNotNull(resolver.openOutputStream(entry)) { "$entry took no bytes" }.use { it.write(bytes) }
            published = published(resolver, entry)
        } finally {
            if (!published) resolver.delete(entry, null, null)
        }
        return published
    }

    /** Whether the media store published the pending [entry]; false when it can number its name no further. */
    private fun published(
        resolver: ContentResolver,
        entry: Uri,
    ): Boolean {
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        val updated =
            try {
                resolver.update(entry, done, null, null)
            } catch (full: IllegalStateException) {
                Log.i(TEST_TAG, "the media store numbers the name no further", full)
                return false
            }
        check(updated == 1) { "$entry stayed pending" }
        return true
    }

    /** [act], then a wait until the media store says an entry of [collection] was deleted. */
    private fun deletionAfter(
        collection: Uri,
        act: () -> Unit,
    ) {
        val deleted = AtomicInteger()
        val observer =
            object : ContentObserver(null) {
                override fun onChange(
                    selfChange: Boolean,
                    uris: Collection<Uri>,
                    flags: Int,
                ) {
                    if (flags and ContentResolver.NOTIFY_DELETE != 0) deleted.incrementAndGet()
                }
            }
        val resolver = rule.activity.contentResolver
        resolver.registerContentObserver(collection, true, observer)
        try {
            act()
            rule.waitUntil("an entry of $collection is deleted", STEP_MILLIS) { deleted.get() > 0 }
        } finally {
            resolver.unregisterContentObserver(observer)
        }
    }

    /**
     * [block], which saves [name], with no entry of that name before or after it, and Download/Fermix taken away
     * again when Save made it: the phone is left as the test found it.
     */
    private fun saving(
        name: String,
        block: () -> Unit,
    ) {
        val storage = rule.activity.getSystemService(StorageManager::class.java)
        val root = checkNotNull(storage.primaryStorageVolume.directory) { "the shared storage is not mounted" }
        val directory = File(root, SAVED_DOCUMENTS)
        val made = !directory.exists()
        unsaved(name)
        try {
            block()
        } finally {
            unsaved(name)
            if (made) check(!directory.exists() || directory.delete()) { "$directory was left behind" }
        }
    }

    /** The one entry Save wrote as [name] into Download/Fermix, once it is there, holding [bytes]. */
    private fun assertSavedAsDocument(
        name: String,
        bytes: ByteArray,
    ) {
        rule.waitUntil("Save writes $name", STEP_MILLIS) { savedAs(downloads(), SAVED_DOCUMENTS, name).isNotEmpty() }
        val entry = savedAs(downloads(), SAVED_DOCUMENTS, name).single()
        val written = checkNotNull(rule.activity.contentResolver.openInputStream(entry)).use { it.readBytes() }
        assertArrayEquals(bytes, written)
        assertEquals(emptyList<Uri>(), savedAs(images(), SAVED_IMAGES, name))
    }

    private fun assertGoesOn() {
        val activity = rule.activity
        assertFalse("the app did not stop", activity.isFinishing)
        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    /** Whether a node [matcher] matches shows. */
    private fun shows(matcher: SemanticsMatcher): Boolean = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun a_tapped_document_opens_through_the_chooser_from_the_apps_provider() {
        val pdf = "%PDF-1.7 export report".toByteArray()
        documentArrives("export-report.pdf", "application/pdf", pdf)
        val (chooser, view) = opened("export-report.pdf")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertOffered(view, "application/pdf", pdf)
    }

    @Test
    fun a_row_whose_ref_climbs_out_is_copied_under_the_caches_shared_directory_and_never_over_the_apps_files() {
        val activity = rule.activity
        val planted = File(activity.noBackupFilesDir, PLANTED_NAME)
        planted.writeBytes(PLANTED)
        // A phone that has shared a blob before, so a path through cache/shared/.. resolves.
        val shared = File(activity.cacheDir, "shared")
        check(shared.isDirectory || shared.mkdirs()) { "$shared could not be made" }
        try {
            val pdf = "%PDF-1.7 the daemon's own bytes".toByteArray()
            documentArrives(PLANTED_NAME, "application/pdf", pdf, ref = CLIMBING_REF)
            val (chooser, view) = opened(PLANTED_NAME)
            assertArrayEquals("the app's own file is untouched", PLANTED, planted.readBytes())
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertOffered(view, "application/pdf", pdf)
            val copies = shared.walkTopDown().filter { it.isFile && it.name == PLANTED_NAME }.toList()
            assertEquals(listOf(File(File(shared, sharedDirectoryName(CLIMBING_REF)), PLANTED_NAME)), copies)
            assertGoesOn()
        } finally {
            planted.delete()
        }
    }

    @Test
    fun raw_html_is_a_row_never_drawn_and_opens_through_the_chooser() {
        documentArrives("report.html", "text/html", PAGE.toByteArray())
        assertTrue(
            "the page's words are never drawn",
            rule.onAllNodes(hasText("The export report", substring = true)).fetchSemanticsNodes().isEmpty(),
        )
        val (chooser, view) = opened("report.html")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertOffered(view, "text/html", PAGE.toByteArray())
    }

    @Test
    fun a_name_no_filesystem_holds_is_copied_under_one_it_does_and_the_app_goes_on() {
        val pdf = "%PDF-1.7 a name with a lone surrogate".toByteArray()
        documentArrives(LONE_SURROGATE_NAME, "application/pdf", pdf)
        val (chooser, view) = opened(LONE_SURROGATE_NAME)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertOffered(view, "application/pdf", pdf)
        val shared = File(rule.activity.cacheDir, "shared")
        val copy = File(File(shared, sharedDirectoryName("blob-$LONE_SURROGATE_NAME")), "x.pdf")
        assertTrue("$copy holds the copy", copy.isFile)
        assertGoesOn()
    }

    @Test
    fun share_hands_the_document_to_the_share_sheet_from_the_apps_provider() {
        val pdf = "%PDF-1.7 the shared report".toByteArray()
        documentArrives("shared-report.pdf", "application/pdf", pdf)
        rule.onNodeWithText("shared-report.pdf").performTouchInput { longClick() }
        rule.onNodeWithText(words(R.string.chat_share)).performClick()
        val (chooser, send) = chooserStarted()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertServed(checkNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)), send.flags, pdf)
    }

    @Test
    fun save_writes_the_document_into_download_fermix_under_its_name() {
        val name = "saved-report.pdf"
        val pdf = "%PDF-1.7 the saved report".toByteArray()
        saving(name) {
            documentArrives(name, "application/pdf", pdf)
            rule.onNodeWithContentDescription(words(R.string.chat_save)).performClick()
            assertSavedAsDocument(name, pdf)
        }
    }

    @Test
    fun an_image_whose_type_is_no_images_is_saved_as_a_document_and_the_app_goes_on() {
        val name = "scan.pdf"
        val bytes = png()
        saving(name) {
            val rig = rule.activity.rig
            val ref = "blob-$name"
            rig.session.blobs = mapOf(ref to bytes)
            val media = MediaRef(ref, "image", "application/pdf", bytes.size.toLong(), filename = name)
            val row = DOCUMENT_ROW.toLong()
            val message = HistoryMessage(row.toULong(), "assistant", "", at(row), listOf(media))
            rig.store.rows.update { listOf(TimelineRow.Message(message)) + it }
            rule.waitUntil("the image shows", STEP_MILLIS) { shows(hasContentDescription(words(R.string.chat_image))) }
            rule.onNodeWithContentDescription(words(R.string.chat_image)).performClick()
            rule.waitUntil("the viewer opens", STEP_MILLIS) { shows(hasText(words(R.string.chat_show_in_chat))) }
            rule.onNodeWithText(words(R.string.chat_save)).performClick()
            assertSavedAsDocument(name, bytes)
            assertGoesOn()
        }
    }

    @Test
    fun a_save_of_a_name_the_media_store_numbers_no_further_is_refused_and_the_app_goes_on() {
        val name = "repeated-report.pdf"
        val pdf = "%PDF-1.7 a report the agent names alike each time".toByteArray()
        saving(name) {
            val held = filledWith(name, pdf)
            assertTrue("the media store took the name at first", held > 0)
            documentArrives(name, "application/pdf", pdf)
            // The media store takes the pending entry and refuses to publish it; Save deletes it again.
            deletionAfter(downloads()) { rule.onNodeWithContentDescription(words(R.string.chat_save)).performClick() }
            rule.waitForIdle()
            assertGoesOn()
            assertEquals(held, numberedAs(downloads(), SAVED_DOCUMENTS, name).size)
        }
    }
}
