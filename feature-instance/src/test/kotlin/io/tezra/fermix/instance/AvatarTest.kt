package io.tezra.fermix.instance

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.AVATAR_EYE_INK
import io.tezra.fermix.design.FermixTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/** A photo's colour, which no tint and no ink is. */
private const val MAGENTA = 0xFFFF00FF.toInt()

private const val AVATAR = "avatar"

/** An id of the 64 hex digits an instance's is. */
private val ID = "ab".repeat(32)

/**
 * The avatar: its tint with the mark's eyes on it in the mark's ink, in both modes (the owner, 2026-10-11), or the
 * owner's photo (2026-10-10) drawn in the round in their place, read for the screens once per (instance, version,
 * size), off the main thread, the last 16 kept for all of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AvatarTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private fun magenta(side: Int): Bitmap =
        Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(MAGENTA) }

    private var dark by mutableStateOf(false)

    private fun drawn(picture: ImageBitmap?): ImageBitmap {
        rule.setContent {
            FermixTheme(darkTheme = dark) {
                InstanceAvatar(
                    record = sample().copy(tint = "Amber"),
                    dot = null,
                    size = AvatarSize.LARGE,
                    picture = picture,
                    modifier = Modifier.testTag(AVATAR),
                )
            }
        }
        return rule.onNodeWithTag(AVATAR).captureToImage()
    }

    /** The avatar as it draws now, after [dark] changed. */
    private fun again(): Bitmap {
        rule.waitForIdle()
        return rule.onNodeWithTag(AVATAR).captureToImage().asAndroidBitmap()
    }

    @Test
    fun `an avatar with a picture draws it in the round, and without one its tint`() {
        val pictured = drawn(magenta(side = 96).asImageBitmap()).asAndroidBitmap()
        assertEquals(MAGENTA, pictured.getPixel(pictured.width / 2, pictured.height / 2))
        assertEquals(MAGENTA, pictured.getPixel(pictured.width / 2, 2))
        assertNotEquals("the corner, outside the round, is the picture's", MAGENTA, pictured.getPixel(1, 1))
    }

    // The eyes' line runs through the disc's centre, so the middle row crosses both eyes: two runs of the mark's ink,
    // one each side of the centre, which lies between them in the tint.
    @Test
    fun `without a picture an avatar draws its eyes in the mark's ink on its tint, in both modes`() {
        val light = drawn(picture = null).asAndroidBitmap()
        dark = true
        val tint = tintColor("Amber").toArgb()
        for (mode in listOf(light, again())) {
            val middle = mode.height / 2
            assertEquals(tint, mode.getPixel(mode.width / 2, middle))
            val runs = inkRuns(mode)
            assertEquals("the ink along the middle row: $runs", 2, runs.size)
            assertTrue(runs[0].last < mode.width / 2 && runs[1].first > mode.width / 2)
            for (eye in runs) assertEquals(AVATAR_EYE_INK.toArgb(), mode.getPixel((eye.first + eye.last) / 2, middle))
        }
    }

    @Test
    fun `an avatar with a picture shows the picture alone, no eyes over it`() {
        val pictured = drawn(magenta(side = 96).asImageBitmap()).asAndroidBitmap()
        assertEquals(emptyList<IntRange>(), inkRuns(pictured))
        assertEquals(MAGENTA, pictured.getPixel(pictured.width / 2, pictured.height / 2))
    }

    @Test
    fun `a picture is read once per instance, version and size, and the last 16 are kept`() =
        runTest {
            val reads = mutableListOf<Pair<String, Int>>()
            val cache =
                AvatarPictureCache(
                    read = { id, side ->
                        reads += id to side
                        magenta(side)
                    },
                    log = { message, fault -> throw AssertionError(message, fault) },
                    io = StandardTestDispatcher(testScheduler),
                )
            val first = AvatarKey(ID, pictureAt = 1L, side = 96)
            val picture = cache.picture(first)
            assertSame(picture, cache.picture(first))
            assertSame(picture, cache.kept(first))
            cache.picture(first.copy(side = 144))
            cache.picture(first.copy(pictureAt = 2L))
            assertEquals(listOf(ID to 96, ID to 144, ID to 96), reads)
            (3L..16L).forEach { cache.picture(first.copy(pictureAt = it)) }
            assertNull("the oldest of 17 is let go", cache.kept(first))
            cache.picture(first)
            assertEquals(18, reads.size)
        }

    @Test
    fun `a picture that cannot be read is told to the log, and the avatar keeps its tint`() =
        runTest {
            val logged = mutableListOf<Throwable?>()
            val unreadable = IOException("not a JPEG")
            val cache =
                AvatarPictureCache(
                    read = { _, _ -> throw unreadable },
                    log = { _, fault -> logged += fault },
                    io = StandardTestDispatcher(testScheduler),
                )
            assertNull(cache.picture(AvatarKey(ID, pictureAt = 1L, side = 96)))
            assertEquals(listOf<Throwable?>(unreadable), logged)
        }

    @Test
    fun `a screen's avatar draws its kept picture, and none while the record has no picture's time`() {
        val cache =
            AvatarPictureCache(read = { _, side -> magenta(side) }, log = { _, _ -> }, io = UnconfinedTestDispatcher())
        var pictured: ImageBitmap? = null
        var plain: ImageBitmap? = magenta(side = 1).asImageBitmap()
        rule.setContent {
            CompositionLocalProvider(LocalAvatarPictures provides cache) {
                pictured = rememberAvatarPicture(ID, pictureAt = 1L, size = AvatarSize.ROW)
                plain = rememberAvatarPicture(ID, pictureAt = null, size = AvatarSize.ROW)
            }
        }
        rule.waitForIdle()
        val side = with(rule.density) { AvatarSize.ROW.disc.roundToPx() }
        assertEquals(side, pictured?.width)
        assertNull(plain)
    }
}

/** The runs of the mark's ink along [image]'s middle row, each from its first column to its last. */
private fun inkRuns(image: Bitmap): List<IntRange> {
    val ink = AVATAR_EYE_INK.toArgb()
    val middle = image.height / 2
    val runs = mutableListOf<IntRange>()
    for (column in 0 until image.width) {
        if (image.getPixel(column, middle) != ink) continue
        val last = runs.lastOrNull()
        when {
            last != null && last.last == column - 1 -> runs[runs.lastIndex] = last.first..column
            else -> runs += column..column
        }
    }
    return runs
}
