package io.tezra.fermix

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.tezra.fermix.data.Instance
import io.tezra.fermix.transport.Candidate
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

/** How long a step of a test may take on an emulator: an activity's start, a copy, an unlock. */
internal const val STEP_MILLIS = 15_000L

/** A gallery photo's edge: enough pixels to decode. */
private const val GALLERY_EDGE = 8

/** The media store's images, where the shell puts another app's. */
private const val IMAGES = "content://media/external/images/media"

/**
 * A record as a pairing with [host] leaves it, which no session can open: no key is under its alias, and nothing
 * answers at its address. Its gateway key is made of [gateway], which tells two of a test apart.
 */
internal fun testRecord(
    gateway: Int,
    host: String,
): Instance =
    Instance(
        gatewayPk = Base64.getEncoder().encodeToString(ByteArray(32) { (0x40 + gateway).toByte() }),
        tlsFp = "%02x".format(Locale.ROOT, 0x40 + gateway).repeat(32),
        host = host,
        profile = "fermix",
        label = host,
        tint = "Slate",
        candidates = listOf(Candidate("192.0.2.$gateway", Candidate.Scope.LAN, Candidate.Kind.IP)),
        port = 4031,
        deviceId = "share-test-$gateway",
        keyAlias = "fermix.device.share-test.$gateway",
        pushSalt = Base64.getEncoder().encodeToString(ByteArray(32) { 0x73 }),
        pushPlatforms = emptyList(),
        notificationsEnabled = false,
    )

/** [command] run by the device's shell, as the instrumentation may run it, and what it printed. */
internal fun shell(command: String): String {
    val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes().decodeToString() }
}

/** The window with the focus, as `dumpsys window` names it. */
internal fun focusedWindow(): String = shell("dumpsys window").lines().filter { "mCurrentFocus" in it }.joinToString()

/** The app's one activity while it is resumed, none while it is not. */
internal fun resumedActivity(): MainActivity? {
    var found: MainActivity? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        found = resumed.filterIsInstance<MainActivity>().singleOrNull()
    }
    return found
}

/** The app's activities that are not yet destroyed, in any stage. */
internal fun liveActivities(): List<MainActivity> {
    val live = mutableListOf<MainActivity>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        for (stage in Stage.entries.filter { it != Stage.DESTROYED }) {
            live += monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>()
        }
    }
    return live
}

/**
 * An image the app saved itself, as the tests make one: a small PNG named [name] in the phone's media store, in
 * Pictures itself, so no directory of its own is left behind; its URI, the caller's to delete. The media store is
 * another package's provider: the share's URI is a `content:` URI of the `media` authority, which the app reads by its
 * ownership of the row, the instrumentation running as the app, with no grant (shellImage is another app's). An entry
 * that could not be written is deleted again.
 */
internal fun galleryImage(
    resolver: ContentResolver,
    name: String,
): Uri {
    val values =
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val entry = checkNotNull(resolver.insert(collection, values)) { "the media store took no image" }
    var written = false
    try {
        pngInto(resolver, entry)
        written = true
    } finally {
        if (!written) resolver.delete(entry, null, null)
    }
    return entry
}

/** A small PNG written into the media store's [entry], which then stops being pending. */
private fun pngInto(
    resolver: ContentResolver,
    entry: Uri,
) {
    checkNotNull(resolver.openOutputStream(entry)) { "$entry took no bytes" }.use { it.write(png()) }
    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    check(resolver.update(entry, done, null, null) == 1) { "$entry stayed pending" }
}

/** A small PNG's bytes, one colour all over. */
internal fun png(): ByteArray {
    val bitmap = Bitmap.createBitmap(GALLERY_EDGE, GALLERY_EDGE, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.rgb(92, 139, 163))
        val bytes = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) { "the PNG was not written" }
        return bytes.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

/**
 * Another app's image: a PNG of [bytes] named [name] that the device's shell, a uid of its own, puts in the media
 * store, in Pictures; its URI, the caller's to delete with [deleteFromShell]. The app cannot read the row on its own,
 * having no media permission, so only a grant on the share that names it lets the app copy it. Each command is one
 * the shell runs with no quoting, its arguments split at spaces.
 */
internal fun shellImage(
    name: String,
    bytes: ByteArray,
): Uri {
    shell(
        "content insert --uri $IMAGES --bind _display_name:s:$name --bind mime_type:s:image/png " +
            "--bind relative_path:s:Pictures/",
    )
    val row = shell("content query --uri $IMAGES --projection _id --where _display_name='$name'")
    val id = Regex("_id=(\\d+)").find(row)?.groupValues?.get(1) ?: error("the shell made no row named $name: $row")
    val uri = Uri.parse("$IMAGES/$id")
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val (output, input) = automation.executeShellCommandRw("content write --uri $uri")
    ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(bytes) }
    ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    return uri
}

/** The shell's own media-store row [uri] deleted, as the shell made it. */
internal fun deleteFromShell(uri: Uri) {
    shell("content delete --uri $uri")
}

/**
 * [uri] shared with the share entry by the device's shell, another uid, as another app's share sheet would: one item
 * of [type], with a read grant on the intent's data, which names the item as its stream does, when [granted]. `am`
 * starts it in a task of its own; a `-f` would set the intent's flags whole and drop the grant's.
 */
internal fun shareFromShell(
    uri: Uri,
    type: String,
    granted: Boolean = true,
) {
    val grant = if (granted) " -d $uri --grant-read-uri-permission" else ""
    val started =
        shell(
            "am start -n io.tezra.fermix/.ShareTarget -a android.intent.action.SEND -t $type " +
                "--eu android.intent.extra.STREAM $uri$grant",
        )
    check("Error" !in started) { "the shell's share did not start: $started" }
}
