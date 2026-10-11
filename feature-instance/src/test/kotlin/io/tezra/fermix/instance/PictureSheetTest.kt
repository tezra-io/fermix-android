package io.tezra.fermix.instance

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
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

/** What TalkBack calls each tint, in data's TINT_NAMES' order: the design's names (section 13.1). */
private val SPOKEN_TINTS =
    listOf(
        R.string.instance_tint_slate,
        R.string.instance_tint_sage,
        R.string.instance_tint_clay,
        R.string.instance_tint_plum,
        R.string.instance_tint_ocean,
        R.string.instance_tint_sand,
    )

/**
 * The avatar's Picture sheet (the owner, 2026-10-10): the Instance header's avatar is the button that opens it; it
 * offers the six tints, the record's selected, a photo to choose, and the photo's removal once there is one, every
 * control a 48 dp target TalkBack names; and a photo that could not be used is said under the header.
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
    ) {
        rule.setContent {
            FermixTheme {
                PictureSheetContent(
                    tint = tint,
                    hasPhoto = photo,
                    onTint = actions.onTint,
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
    fun `the sheet offers the six tints as radio buttons of 48 dp, the record's selected, and a tap sets one`() {
        sheet(tint = "Sand", photo = false)
        val target = with(rule.density) { 48.dp.toPx() }
        TINT_NAMES.zip(SPOKEN_TINTS).forEach { (name, spoken) ->
            val node = rule.onNodeWithContentDescription(string(spoken)).fetchSemanticsNode()
            assertEquals(name, Role.RadioButton, node.config.getOrNull(SemanticsProperties.Role))
            assertEquals(name, name == "Sand", node.config.getOrNull(SemanticsProperties.Selected))
            assertTrue("$name is ${node.size}", node.size.width >= target - 1f && node.size.height >= target - 1f)
        }
        rule.onNodeWithContentDescription(string(R.string.instance_tint_ocean)).performClick()
        assertEquals(listOf("tint:Ocean"), calls)
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
