package io.tezra.fermix

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.data.Instance
import io.tezra.fermix.instance.AvatarSize
import io.tezra.fermix.instance.InstanceViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs

/** The Fermix the test pairs, which no session can open. */
private const val HOST = "avatar-mbp"

/** The photo's colour, which no tint, ink or dot is, and its edge: a small PNG, scaled up to the 256 square. */
private const val MAGENTA = 0xFFFF00FF.toInt()
private const val PHOTO_EDGE = 64

/** How far a scaled JPEG's channel may stray from the photo's colour. */
private const val JPEG_SLACK = 24

/** The least share of an avatar's disc the photo's colour must cover: the disc less its dot and its rim. */
private const val COVERED = 0.6

/**
 * The avatar's photo through the Instance screen's ViewModel on the device (the owner, 2026-10-10): the owner's pick,
 * a media-store image read through the ContentResolver as the Photo Picker's is, becomes the picture's file and then
 * the record's time; the app's activity then starts afresh, its avatars read from nothing kept (a process restart's
 * state for them: the records as the store has them, the pictures from their files, no picture decoded), and the
 * Chats row and the chat's bar each draw the photo in the avatar's round. Removing the record takes the picture's
 * file. A photo of the app's own file or provider is refused. The record is paired so that no session can open it;
 * the app must hold no record before.
 */
@RunWith(AndroidJUnit4::class)
class AvatarDeviceTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as FermixApplication
    private val services get() = app.services
    private val record: Instance = testRecord(gateway = 9, host = HOST)
    private var photo: Uri? = null

    @Before
    fun nothingPaired() {
        runBlocking { services.instances.dismissRepairNotices() }
        val held = runBlocking { services.instances.instances.first() }
        assertEquals("the avatar test needs an app with no Fermix paired", emptyList<Instance>(), held)
    }

    @After
    fun cleanUp() {
        val live = liveActivities()
        instrumentation.runOnMainSync { live.forEach { it.finishAndRemoveTask() } }
        rule.waitUntil(STEP_MILLIS) { liveActivities().isEmpty() }
        val picture = services.instances.pictures.fileOf(record.id)
        val held = runBlocking { services.instances.instances.first() }
        runBlocking {
            if (held.any { it.id == record.id }) services.instances.remove(record.id)
            services.settings.setLastChat(null)
        }
        photo?.let { app.contentResolver.delete(it, null, null) }
        assertFalse("the picture's file went with its record", picture.exists())
    }

    @Test
    fun aPhotoSetThroughTheInstanceScreenShowsOnTheChatsRowAndTheChatBarOnceTheAppStartsAfresh() {
        runBlocking { services.instances.upsert(record) }
        val picked = galleryImage(app.contentResolver, "avatar-test.png", magentaPng()).also { photo = it }
        InstanceViewModel(services.instanceParts(), record.id).setPicture(picked.toString())
        val records = services.instances
        runBlocking { withTimeout(STEP_MILLIS) { records.instances.first { it.single().pictureAt != null } } }
        assertTrue("the picture's file is written", records.pictures.fileOf(record.id).isFile)

        app.startActivity(
            Intent()
                .setComponent(ComponentName(app, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        // The Chats row, and then the chat's bar, whose avatar and name are one button too.
        val named = hasText(HOST) and hasClickAction()
        rule.waitUntil("the Chats row", STEP_MILLIS) { rule.onAllNodes(named).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil("the photo on the Chats row", STEP_MILLIS) { covers(rule.onNode(named), AvatarSize.ROW) }
        rule.onNode(named).performClick()
        rule.waitUntil("the chat alone", STEP_MILLIS) {
            rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodes(named).fetchSemanticsNodes().size == 1
        }
        rule.waitUntil("the photo in the chat's bar", STEP_MILLIS) { covers(rule.onNode(named), AvatarSize.SMALL) }
    }

    /**
     * A photo URI the app may not read (AGENTS.md: a string from another app never names a path or picks what the app
     * reads), each a real image: a `file:` URI, which the resolver would open as a path, and the app's own
     * FileProvider's, which the app reads with its own rights. Each is refused, the screen says so, and nothing is
     * written.
     */
    @Test
    fun aPhotoOfTheAppsOwnFileOrItsOwnProviderIsRefusedAndNothingIsWritten() {
        runBlocking { services.instances.upsert(record) }
        val shared = File(app.cacheDir, "shared/avatar-own.png")
        try {
            checkNotNull(shared.parentFile).mkdirs()
            shared.writeBytes(magentaPng())
            val own = FileProvider.getUriForFile(app, "${app.packageName}.chat.files", shared)
            for (uri in listOf(Uri.fromFile(shared), own)) {
                val model = InstanceViewModel(services.instanceParts(), record.id)
                model.setPicture(uri.toString())
                runBlocking { withTimeout(STEP_MILLIS) { model.ui.first { it?.pictureFailed == true } } }
                val picture = services.instances.pictures.fileOf(record.id)
                assertFalse("$uri was written", picture.exists())
                val held = runBlocking { services.instances.instances.first() }
                assertEquals("$uri set the record's picture", null, held.single().pictureAt)
            }
        } finally {
            shared.delete()
        }
    }

    /** Whether [node]'s image shows the photo's colour over [COVERED] of an avatar of [size]'s disc. */
    private fun covers(
        node: SemanticsNodeInteraction,
        size: AvatarSize,
    ): Boolean {
        val image = runCatching { node.captureToImage().asAndroidBitmap() }.getOrNull() ?: return false
        val radius = size.disc.value / 2f * app.resources.displayMetrics.density
        val disc = PI * radius * radius
        return magentaPixels(image) >= disc * COVERED
    }
}

/** How many of [image]'s pixels are the photo's colour, within a JPEG's slack. */
private fun magentaPixels(image: Bitmap): Int {
    val pixels = IntArray(image.width * image.height)
    image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
    return pixels.count { pixel ->
        listOf(16, 8, 0).all { shift -> abs((pixel shr shift and 0xFF) - (MAGENTA shr shift and 0xFF)) <= JPEG_SLACK }
    }
}

/** A small PNG, magenta all over. */
private fun magentaPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(PHOTO_EDGE, PHOTO_EDGE, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(MAGENTA)
        val bytes = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) { "the PNG was not written" }
        return bytes.toByteArray()
    } finally {
        bitmap.recycle()
    }
}
