package io.tezra.fermix.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val MIB = 1_024L * 1_024

/** The sheet's actions, none of which a test of its line takes. */
private val NO_ACTIONS = ComposerActions(onField = {}, onSend = {}, onStop = {}, onPalette = {})

/**
 * The attach sheet's too-big line (design section 13.9; the owner, 2026-10-11): "{name} is {size} — the limit is
 * {max}." while the item's size reads other than the limit, and "{name} is over the {max} limit." while it reads as
 * the limit, a copy cut a byte past it or a size that rounds to it, so the line never says an item past the limit is
 * the limit's size. A size reads as the line says it, its unit after a no-break space. JUnit 4, in Roborazzi's
 * activity, on the compact window of @FermixPreviews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TooBigLineTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    /** The sheet with [tooBig] as its line, nothing picked, an empty box where the photos' grid goes. */
    private fun sheetWith(tooBig: TooBig) {
        val attach = AttachUi(sheet = true, tooBig = tooBig)
        rule.setContent { FermixTheme { AttachSheetContent(attach, TextFieldValue(""), NO_ACTIONS) { Box(it) } } }
    }

    @Test
    fun `a copy cut a byte past 20 MiB is over the 20 MB limit`() {
        sheetWith(TooBig("archive.zip", 20 * MIB + 1, 20 * MIB))
        rule.onNodeWithText("archive.zip is over the 20 MB limit.").assertExists()
    }

    @Test
    fun `a copy cut a byte past the app's own bound, before the daemon states a limit, is over the 128 MB limit`() {
        sheetWith(TooBig("archive.zip", LANDING_MAX_BYTES + 1, LANDING_MAX_BYTES))
        rule.onNodeWithText("archive.zip is over the 128 MB limit.").assertExists()
    }

    @Test
    fun `a size that reads other than the limit is said, beside the limit`() {
        sheetWith(TooBig("site-walkthrough.mov", 27_472_691L, 20 * MIB))
        rule.onNodeWithText("site-walkthrough.mov is 26.2 MB — the limit is 20 MB.").assertExists()
    }

    @Test
    fun `a known size past the limit that rounds to it is over the limit`() {
        sheetWith(TooBig("clip.mp4", 20 * MIB + 50 * 1_024, 20 * MIB))
        rule.onNodeWithText("clip.mp4 is over the 20 MB limit.").assertExists()
    }
}
