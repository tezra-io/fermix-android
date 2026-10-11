package io.tezra.fermix

import android.app.Application
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.data.AVATAR_SIDE_PX
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.InstanceStore
import io.tezra.fermix.data.ProfileDatabases
import io.tezra.fermix.data.avatarSource
import io.tezra.fermix.data.ownsProvider
import io.tezra.fermix.instance.InstanceParts
import io.tezra.fermix.instance.InstanceViewModel
import io.tezra.fermix.transport.NetworkFacts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** How long a test waits in real time for DataStore's threads and the picture's decode; past it, it fails. */
private val SETTLE = 10.seconds

/** How often a wait looks again at what the store's own io changes. */
private const val POLL_MILLIS = 10L

/** When the owner picked the photo, by the test's wall clock. */
private const val PICKED_AT = 1_790_600_000_000L

/** A photo's colour, which no tint is. */
private const val MAGENTA = 0xFFFF00FF.toInt()

/** Another photo's colour, the one picked last. */
private const val GREEN = 0xFF00FF00.toInt()

/** The URI of the photo whose provider holds it until the test lets it go ([HeldPhoto]). */
private const val HELD = "held"

/** How long a pick cancelled meanwhile is given to land anyway, had it not been cancelled: many times its decode. */
private val LAND_WINDOW = 2.seconds

/** How far a JPEG's channel may stray from the colour it was given. */
private const val JPEG_SLACK = 12

/** Whether [argb]'s channels are each within [JPEG_SLACK] of [expected]'s. */
private fun near(
    argb: Int,
    expected: Int,
): Boolean =
    listOf(0, 8, 16).all { shift ->
        val strayed = (argb shr shift and 0xFF) - (expected shr shift and 0xFF)
        abs(strayed) <= JPEG_SLACK
    }

private val RECORD =
    Instance(
        gatewayPk = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
        tlsFp = "02".repeat(32),
        host = "suj-mbp",
        profile = "fermix",
        label = "suj-mbp",
        tint = "Slate",
        candidates = emptyList(),
        port = 4031,
        deviceId = "device-1",
        keyAlias = "fermix.device.1.0102030405060708",
        pushSalt = Base64.getEncoder().encodeToString(ByteArray(32) { 0x73 }),
        pushPlatforms = emptyList(),
        notificationsEnabled = false,
    )

/**
 * The avatar's photo and tint through the Instance screen's ViewModel (the owner, 2026-10-10), over the app's own
 * store and files: a photo picked becomes the picture's file, a 256 square, and then the record's time, which a new
 * store over the same files, as the next process makes, reads back with the file; a photo that cannot be read leaves
 * the record as it was and is logged (the screen's line is InstanceStateTest's and PictureSheetTest's); Remove photo
 * takes the time and the file; a tint is the record's. On Robolectric's native graphics, whose ImageDecoder decodes
 * and crops as the phone's does. No test here reads the screen's state, which opens the profile's database: the
 * bundled SQLite library loads once for the JVM, into the sandbox of the app's other tests, the legacy graphics'.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarWiringTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val directory: File get() = folder.root

    private val main = StandardTestDispatcher()

    private val logged = mutableListOf<String>()

    /** The first line logged, once it is. */
    private val firstLogged = CompletableDeferred<String>()

    /** The thread a wait polls the store's own io from, in real time, while the test's scheduler runs the rest. */
    private val waiter = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before
    fun mainOnTheTestScheduler() = Dispatchers.setMain(main)

    @After
    fun mainBack() {
        Dispatchers.resetMain()
        waiter.close()
    }

    private val databases by lazy {
        ProfileDatabases(ApplicationProvider.getApplicationContext(), File(directory, "profiles"))
    }

    private val records: File get() = File(directory, "instances.json")

    private suspend fun TestScope.store(): InstanceStore {
        val store = InstanceStore(records, backgroundScope, databases)
        store.upsert(RECORD)
        return store
    }

    /**
     * The screen's ViewModel over [store], each photo URI read by [photo], by default as the file it names, a photo's
     * time read on [now].
     */
    private fun TestScope.model(
        store: InstanceStore,
        photo: (String) -> ImageDecoder.Source = { ImageDecoder.createSource(File(it)) },
        now: () -> Long = { PICKED_AT },
    ): InstanceViewModel {
        val parts =
            InstanceParts(
                instances = store,
                sessions = MutableStateFlow(emptyMap()),
                network = MutableStateFlow(NetworkFacts(1L, false, false, false)),
                profiles = databases,
                tester = { error("no connection test in this test") },
                notifications = { _, _ -> },
                unpair = {},
                releaseBuild = false,
                photo = photo,
                now = now,
                log = { message, _ ->
                    logged += message
                    firstLogged.complete(message)
                },
            )
        return InstanceViewModel(parts, RECORD.id)
    }

    /** A landscape photo of [width] × [height], [colour] (magenta), as a JPEG file named [name]. */
    private fun photo(
        width: Int,
        height: Int,
        colour: Int = MAGENTA,
        name: String = "picked.jpg",
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        val file = File(directory, name)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        return file
    }

    /**
     * A magenta photo whose provider holds it, as a slow one in the Photo Picker does: its decode waits, on the
     * picture's io thread, until the test lets it go ([let]), bounded; [asked] returns once the decode has begun.
     */
    private inner class HeldPhoto {
        private val file = photo(width = 300, height = 300, name = "held.jpg")
        private val begun = CountDownLatch(1)
        private val released = CountDownLatch(1)

        val source: ImageDecoder.Source =
            ImageDecoder.createSource {
                begun.countDown()
                val let = released.await(SETTLE.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                check(let) { "the photo was never let go" }
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                AssetFileDescriptor(descriptor, 0, file.length())
            }

        suspend fun asked() =
            withContext(waiter) {
                val began = begun.await(SETTLE.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                check(began) { "the photo's decode never began" }
            }

        fun let() = released.countDown()
    }

    /**
     * Whether the record's picture time comes to one [at] takes within [LAND_WINDOW] of real time, as a pick
     * cancelled meanwhile would land: waited for on [waiter], the test's scheduler running the rest meanwhile.
     */
    private suspend fun landsAgain(
        store: InstanceStore,
        at: (Long?) -> Boolean,
    ): Boolean =
        withContext(waiter) {
            withTimeoutOrNull(LAND_WINDOW) { store.instances.first { at(it.single().pictureAt) } } != null
        }

    @Test
    fun `a photo picked becomes a 256 square file and then the record's time, which the next process reads back`() =
        runTest(main, timeout = SETTLE) {
            val process = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val store = InstanceStore(records, process, databases)
            store.upsert(RECORD)
            model(store).setPicture(photo(width = 800, height = 600).path)
            val pictured = store.instances.first { it.single().pictureAt != null }.single()
            assertEquals(PICKED_AT, pictured.pictureAt)
            assertEquals(emptyList<String>(), logged)

            // The process ends, and the next one reads the record from its file and the picture from its own.
            checkNotNull(process.coroutineContext[Job]).cancelAndJoin()
            val next = InstanceStore(records, backgroundScope, databases)
            assertEquals(
                PICKED_AT,
                next.instances
                    .first()
                    .single()
                    .pictureAt,
            )
            val picture = checkNotNull(next.pictures.read(RECORD.id, AVATAR_SIDE_PX))
            assertEquals(AVATAR_SIDE_PX to AVATAR_SIDE_PX, picture.width to picture.height)
            val centre = picture.getPixel(AVATAR_SIDE_PX / 2, AVATAR_SIDE_PX / 2)
            assertTrue("the centre is the photo's: ${Integer.toHexString(centre)}", near(centre, MAGENTA))
        }

    @Test
    fun `a picture asked for larger than its file is read at the file's own 256 square, never scaled up`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            model(store).setPicture(photo(width = 400, height = 400).path)
            store.instances.first { it.single().pictureAt != null }
            val larger = checkNotNull(store.pictures.read(RECORD.id, AVATAR_SIDE_PX + AVATAR_SIDE_PX / 8))
            assertEquals(AVATAR_SIDE_PX to AVATAR_SIDE_PX, larger.width to larger.height)
            val smaller = checkNotNull(store.pictures.read(RECORD.id, AVATAR_SIDE_PX / 2))
            assertEquals(AVATAR_SIDE_PX / 2 to AVATAR_SIDE_PX / 2, smaller.width to smaller.height)
        }

    @Test
    fun `a photo that cannot be read leaves the record as it was, writes no file, and is logged`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val junk = File(directory, "junk.jpg").apply { writeText("not a photo") }
            model(store).setPicture(junk.path)
            firstLogged.await()
            advanceUntilIdle()
            assertEquals(listOf(RECORD), store.instances.first())
            assertFalse(store.pictures.fileOf(RECORD.id).exists())
            assertEquals(1, logged.size)
        }

    @Test
    fun `a photo the app may not read, its own file or its own provider's, is refused, logged by class alone`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val app: Application = ApplicationProvider.getApplicationContext()
            val read = { uri: String -> avatarSource(app.contentResolver, uri) { ownsProvider(app, it) } }
            val photo = photo(width = 300, height = 300)
            val model = model(store, read)
            model.setPicture(Uri.fromFile(photo).toString())
            advanceUntilIdle()
            model.setPicture("content://${app.packageName}.chat.files/shared/${photo.name}")
            advanceUntilIdle()
            assertEquals(listOf(RECORD), store.instances.first())
            assertFalse(store.pictures.fileOf(RECORD.id).exists())
            assertEquals(List(2) { "A photo was refused, or its grant is gone: SecurityException" }, logged)
        }

    @Test
    fun `a photo picked while another is on its way is the picture, the one before it never landing`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val held = HeldPhoto()
            val green = photo(width = 300, height = 300, colour = GREEN, name = "green.jpg")
            var clock = PICKED_AT
            val model =
                model(
                    store,
                    photo = { if (it == HELD) held.source else ImageDecoder.createSource(File(it)) },
                    now = { clock++ },
                )
            model.setPicture(HELD)
            held.asked()
            model.setPicture(green.path)
            val last =
                store.instances
                    .first { it.single().pictureAt != null }
                    .single()
                    .pictureAt
            held.let()
            assertFalse("the held photo landed after the last", landsAgain(store) { it != last })
            val picture = checkNotNull(store.pictures.read(RECORD.id, AVATAR_SIDE_PX))
            val centre = picture.getPixel(AVATAR_SIDE_PX / 2, AVATAR_SIDE_PX / 2)
            assertTrue("the picture is the last one picked: ${Integer.toHexString(centre)}", near(centre, GREEN))
            assertEquals(emptyList<String>(), logged)
        }

    @Test
    fun `Remove photo while a photo is on its way takes it too, the photo never landing`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val held = HeldPhoto()
            val model = model(store, photo = { if (it == HELD) held.source else ImageDecoder.createSource(File(it)) })
            model.setPicture(photo(width = 300, height = 300).path)
            store.instances.first { it.single().pictureAt != null }
            model.setPicture(HELD)
            held.asked()
            model.removePicture()
            store.instances.first { it.single().pictureAt == null }
            held.let()
            assertFalse("the held photo came back after its removal", landsAgain(store) { it != null })
            withContext(waiter) {
                val avatars = store.pictures.fileOf(RECORD.id).parentFile
                withTimeout(SETTLE) { while (avatars?.list().orEmpty().isNotEmpty()) delay(POLL_MILLIS) }
            }
            assertEquals(emptyList<String>(), logged)
        }

    @Test
    fun `Remove photo takes the record's time and the file, and a tint chosen is the record's`() =
        runTest(main, timeout = SETTLE) {
            val store = store()
            val model = model(store)
            model.setPicture(photo(width = 300, height = 900).path)
            store.instances.first { it.single().pictureAt != null }
            model.removePicture()
            store.instances.first { it.single().pictureAt == null }
            // The record is written first and the file deleted after it, on the store's own io: waited for, bounded.
            withContext(waiter) {
                withTimeout(SETTLE) { while (store.pictures.fileOf(RECORD.id).exists()) delay(POLL_MILLIS) }
            }
            model.setTint("Ocean")
            assertEquals(
                "Ocean",
                store.instances
                    .first { it.single().tint == "Ocean" }
                    .single()
                    .tint,
            )
        }
}
