package io.tezra.fermix

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Long enough, on the main looper's clock, for a frame to recompose what a new value of the setting changed. */
private val FRAME = Duration.ofMillis(100)

/**
 * The recents preview follows the app lock's setting (design section 13.7, FollowLockSetting): a window made before
 * the setting is read, as each new activity's is, keeps its preview out of the recents until the setting says the
 * lock is off. On Robolectric, in an activity of its own, with the plain Application, so no service runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FollowLockSettingTest {
    @Test
    fun `the recents preview stays hidden until the setting is read, and shows once it says the lock is off`() {
        val shown = mutableListOf<Boolean>()
        val setting = MutableSharedFlow<Boolean>(replay = 1)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { FollowLockSetting(setting, shown::add) }
        shadowOf(Looper.getMainLooper()).idleFor(FRAME)
        assertEquals(listOf(false), shown)
        assertTrue(setting.tryEmit(false))
        shadowOf(Looper.getMainLooper()).idleFor(FRAME)
        assertEquals(listOf(false, true), shown)
    }
}
