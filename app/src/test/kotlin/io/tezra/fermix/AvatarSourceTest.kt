package io.tezra.fermix

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.tezra.fermix.data.avatarSource
import io.tezra.fermix.data.ownsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A path a file: URI could name, which a refusal must never repeat. */
private const val RECORDS_PATH = "/data/user/0/io.tezra.fermix/no_backup/instances.json"

/**
 * The avatar's photo as the app reads it (AGENTS.md: a string from another app never names a path or picks what the
 * app reads): the Photo Picker's pick, another app's `content:` URI, read through the ContentResolver; a `file:` URI,
 * which the resolver would open as a path, and the app's own FileProvider, which the app reads with its own rights,
 * with or without a user, refused, the refusal naming the scheme and authority alone. Over the app's merged manifest,
 * whose providers ownsProvider reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AvatarSourceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val own = { authority: String -> ownsProvider(app, authority) }
    private val chatFiles get() = "${app.packageName}.chat.files"

    private fun refusal(uri: String): String? =
        assertThrows(SecurityException::class.java) { avatarSource(app.contentResolver, uri, own) }.message

    @Test
    fun `a file URI is refused by its scheme alone, its path never named`() {
        assertEquals("the avatar refuses a file URI of no authority", refusal("file://$RECORDS_PATH"))
    }

    @Test
    fun `the app's own FileProvider is refused, with or without a user`() {
        assertEquals("the avatar refuses a content URI of $chatFiles", refusal("content://$chatFiles/shared/a.png"))
        assertEquals(
            "the avatar refuses a content URI of 0@$chatFiles",
            refusal("content://0@$chatFiles/shared/a.png"),
        )
    }

    @Test
    fun `the Photo Picker's pick, another app's content URI, is read through the resolver`() {
        val picked = "content://media/picker/0/com.android.providers.media.photopicker/media/7"
        avatarSource(app.contentResolver, picked, own)
    }
}
