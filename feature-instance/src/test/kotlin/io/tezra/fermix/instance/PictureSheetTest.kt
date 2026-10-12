package io.tezra.fermix.instance

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.data.EYE_STYLES
import io.tezra.fermix.data.TINT_NAMES
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.transport.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/** What TalkBack calls each tint, in data's TINT_NAMES' order: the tints' own names (the owner, 2026-10-11). */
private val SPOKEN_TINTS =
    listOf(
        R.string.instance_tint_coral,
        R.string.instance_tint_amber,
        R.string.instance_tint_olive,
        R.string.instance_tint_teal,
        R.string.instance_tint_sky,
        R.string.instance_tint_iris,
        R.string.instance_tint_rose,
        R.string.instance_tint_slate,
    )

/** What TalkBack calls each eye style, in data's EYE_STYLES' order. */
private val SPOKEN_EYES =
    listOf(
        R.string.instance_eyes_open,
        R.string.instance_eyes_happy,
        R.string.instance_eyes_wink,
        R.string.instance_eyes_squint,
        R.string.instance_eyes_sleepy,
        R.string.instance_eyes_sideways,
    )

/**
 * The avatar's Picture sheet (the owner, 2026-10-10): the Instance header's avatar is the button that opens it; it
 * offers the eight tints and the six eye styles (2026-10-11), the record's selected, a photo to choose, and the photo's
 * removal once there is one, every control a 48 dp target TalkBack names; and a photo that could not be used is said
 * under the header.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class PictureSheetTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val calls = mutableListOf<String>()

    private val actions =
        InstanceActions(
            onBack = {},
            onRename = {},
            onTest = {},
            onNotifications = {},
            onPreviews = {},
            onClearCache = {},
            onUnpair = {},
            onTint = { calls += "tint:$it" },
            onEyes = { calls += "eyes:$it" },
            onChoosePhoto = { calls += "choose" },
            onRemovePhoto = { calls += "remove" },
        )

    private fun string(
        @StringRes id: Int,
    ): String = rule.activity.getString(id)

    private fun ui(pictureFailed: Boolean = false): InstanceUi =
        InstanceUi(
            record = sample(nickname = "Studio"),
            others = emptyList(),
            link = Link.Up(Candidate.Scope.LAN, latencyMs = 9, caughtUp = true),
            diagnostics = emptyList(),
            reachable = setOf(LAN),
            previews = true,
            cacheBytes = 4_096,
            test = TestState.Idle,
            releaseBuild = true,
            pictureFailed = pictureFailed,
        )

    private fun sheet(
        tint: String,
        photo: Boolean,
        eyes: String = "Open",
    ) {
        val record = sample().copy(tint = tint, eyes = eyes, pictureAt = if (photo) 1L else null)
        rule.setContent {
            FermixTheme {
                PictureSheetContent(
                    record = record,
                    onTint = actions.onTint,
                    onEyes = actions.onEyes,
                    onChoose = actions.onChoosePhoto,
                    onRemove = actions.onRemovePhoto,
                )
            }
        }
    }

    @Test
    fun `the header's avatar is a button, Change picture, that opens the Picture sheet`() {
        rule.setContent { FermixTheme { InstanceScreen(ui(), actions, zone = ZoneOffset.UTC) } }
        val avatar = rule.onNodeWithContentDescription(string(R.string.instance_picture_change))
        assertEquals(Role.Button, avatar.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        avatar.performClick()
        rule.waitForIdle()
        rule.onNode(isHeading() and hasTextExactly(string(R.string.instance_picture_title))).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.instance_picture_choose)).assertIsDisplayed()
    }

    @Test
    fun `the sheet offers the eight tints as radio buttons of 48 dp, the record's selected, and a tap sets one`() {
        sheet(tint = "Amber", photo = false)
        assertEquals(8, TINT_NAMES.size)
        val target = with(rule.density) { 48.dp.toPx() }
        TINT_NAMES.zip(SPOKEN_TINTS).forEach { (name, spoken) ->
            val node = rule.onNodeWithContentDescription(string(spoken)).fetchSemanticsNode()
            assertEquals(name, Role.RadioButton, node.config.getOrNull(SemanticsProperties.Role))
            assertEquals(name, name == "Amber", node.config.getOrNull(SemanticsProperties.Selected))
            assertTrue("$name is ${node.size}", node.size.width >= target - 1f && node.size.height >= target - 1f)
        }
        rule.onNodeWithContentDescription(string(R.string.instance_tint_sky)).performClick()
        assertEquals(listOf("tint:Sky"), calls)
    }

    // A photo keeps the avatar's disc, and the two rows still set the record, as a tint did before the eyes.
    @Test
    fun `the sheet offers the six eye styles as radio buttons of 48 dp on the record's tint, and a tap sets one`() {
        sheet(tint = "Teal", photo = true, eyes = "Happy")
        val target = with(rule.density) { 48.dp.toPx() }
        val tint = tintColor("Teal").toArgb()
        assertEquals(EYE_STYLES.size, SPOKEN_EYES.map(::string).toSet().size)
        EYE_STYLES.zip(SPOKEN_EYES).forEach { (name, spoken) ->
            val choice = rule.onNodeWithContentDescription(string(spoken))
            val node = choice.fetchSemanticsNode()
            assertEquals(name, Role.RadioButton, node.config.getOrNull(SemanticsProperties.Role))
            assertEquals(name, name == "Happy", node.config.getOrNull(SemanticsProperties.Selected))
            assertTrue("$name is ${node.size}", node.size.width >= target - 1f && node.size.height >= target - 1f)
            // Under the eyes, a quarter of the target below its centre, the disc is the record's tint in every style.
            val image = choice.captureToImage().asAndroidBitmap()
            assertEquals(name, tint, image.getPixel(image.width / 2, image.height * 3 / 4))
        }
        rule.onNodeWithContentDescription(string(R.string.instance_eyes_wink)).performClick()
        rule.onNodeWithContentDescription(string(R.string.instance_eyes_sideways)).performClick()
        assertEquals(listOf("eyes:Wink", "eyes:Sideways"), calls)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun `on a phone on its side the sheet opens whole, Remove photo in the window`() {
        val pictured = ui().copy(record = ui().record.copy(pictureAt = 1L))
        rule.setContent { FermixTheme { InstanceScreen(pictured, actions, zone = ZoneOffset.UTC) } }
        rule.onNodeWithContentDescription(string(R.string.instance_picture_change)).performClick()
        rule.waitForIdle()
        val remove = rule.onNodeWithText(string(R.string.instance_picture_remove)).fetchSemanticsNode().boundsInRoot
        val window = rule.activity.resources.displayMetrics.heightPixels
        assertTrue("Remove photo at $remove, in a window $window tall", remove.bottom <= window)
    }

    @Test
    fun `Choose a photo asks for one, and Remove photo is offered only once there is a photo`() {
        sheet(tint = "Slate", photo = false)
        rule.onAllNodesWithText(string(R.string.instance_picture_remove)).assertCountEquals(0)
        rule.onNodeWithText(string(R.string.instance_picture_choose)).performClick()
        assertEquals(listOf("choose"), calls)
    }

    @Test
    fun `with a photo the sheet offers its removal`() {
        sheet(tint = "Slate", photo = true)
        rule.onNodeWithText(string(R.string.instance_picture_remove)).performClick()
        assertEquals(listOf("remove"), calls)
    }

    @Test
    fun `a photo that could not be used is said once under the header, politely`() {
        rule.setContent { FermixTheme { InstanceScreen(ui(pictureFailed = true), actions, zone = ZoneOffset.UTC) } }
        val failed = rule.onNodeWithText(string(R.string.instance_picture_failed))
        failed.assertIsDisplayed()
        rule.onAllNodesWithText(string(R.string.instance_picture_failed)).assertCountEquals(1)
        val live = failed.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(LiveRegionMode.Polite, live)
    }
}
