package io.tezra.fermix.instance

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
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
import io.tezra.fermix.design.FermixTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * The avatar with the owner's photo (the owner, 2026-10-10): drawn in the round where the tint was, and read for the
 * screens once per (instance, version, size), off the main thread, the last 16 kept for all of them.
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

    private fun drawn(picture: ImageBitmap?): ImageBitmap {
        rule.setContent {
            FermixTheme {
                InstanceAvatar(
                    tint = "Sand",
                    dot = null,
                    size = AvatarSize.LARGE,
                    picture = picture,
                    modifier = Modifier.testTag(AVATAR),
                )
            }
        }
        return rule.onNodeWithTag(AVATAR).captureToImage()
    }

    @Test
    fun `an avatar with a picture draws it in the round, and without one its tint`() {
        val pictured = drawn(magenta(side = 96).asImageBitmap()).asAndroidBitmap()
        assertEquals(MAGENTA, pictured.getPixel(pictured.width / 2, pictured.height / 2))
        assertEquals(MAGENTA, pictured.getPixel(pictured.width / 2, 2))
        assertNotEquals("the corner, outside the round, is the picture's", MAGENTA, pictured.getPixel(1, 1))
    }

    @Test
    fun `an avatar without a picture is its tint`() {
        val plain = drawn(picture = null).asAndroidBitmap()
        assertEquals(tintColor("Sand").toArgb(), plain.getPixel(plain.width / 2, plain.height / 2))
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
