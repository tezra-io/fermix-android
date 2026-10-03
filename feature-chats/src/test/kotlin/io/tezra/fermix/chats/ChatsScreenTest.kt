package io.tezra.fermix.chats

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziActivity
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.instance.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Material 3's padding above a menu's first entry (DropdownMenuVerticalPadding). */
private const val MENU_PADDING_DP = 8f

/**
 * The Chats list as the owner meets it, on Robolectric: a row whose link ended on `1002` says so and never
 * that the phone was unpaired (design section 9.4), and a row's long-press menu carries section 13.4's
 * entries in its order, over the held row. JUnit 4, in Roborazzi's activity, on the compact window of
 * @FermixPreviews.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChatsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private fun row(link: Link) =
        ChatRow(
            record = sample(1),
            profileId = "main",
            agentName = null,
            dev = false,
            link = link,
            line = if (link == Link.ProtocolError || link == Link.Revoked) RowLine.Speaks(link) else RowLine.Empty,
            time = null,
            unread = 0,
        )

    private fun show(
        rows: List<ChatRow>,
        actions: ChatsActions = actions(),
    ) {
        rule.setContent { FermixTheme { ChatsScreen(ChatsUi(rows, repairs = emptyList()), actions) } }
    }

    private fun actions(
        onOpen: (ChatRow) -> Unit = {},
        onMoveToTop: (String) -> Unit = {},
    ) = ChatsActions({}, {}, onOpen, onMoveToTop, { _, _ -> }, {}, {}, {}, {})

    @Test
    fun `a row whose link ended on 1002 says protocol error, and nothing of being unpaired`() {
        show(listOf(row(Link.ProtocolError)))
        rule.onNodeWithText("Protocol error").assertExists()
        assertTrue(rule.onAllNodesWithText("Unpaired by $HOST").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a revoked row says who unpaired it`() {
        show(listOf(row(Link.Revoked)))
        rule.onNodeWithText("Unpaired by $HOST").assertExists()
    }

    @Test
    fun `a row's long-press opens Move to top, Rename, Details and Unpair in that order, and a tap opens the chat`() {
        val opened = mutableListOf<String>()
        val moved = mutableListOf<String>()
        show(listOf(row(Link.Connecting)), actions(onOpen = { opened += it.record.id }, onMoveToTop = { moved += it }))
        rule.onNodeWithText(HOST).performClick()
        assertEquals(listOf(sample(1).id), opened)
        rule.onNodeWithText(HOST).performTouchInput { longClick() }
        val labels = listOf("Move to top", "Rename", "Details", "Unpair…")
        val tops =
            labels.map {
                rule
                    .onNodeWithText(it)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        val ordered = tops.zipWithNext().all { (above, below) -> above < below }
        assertTrue("the menu reads $labels from the top: $tops", ordered)
        rule.onNodeWithText("Move to top").performClick()
        assertEquals(listOf(sample(1).id), moved)
    }

    @Test
    fun `a row's long-press menu opens over the held row, 14 dp below its top and 76 dp in, past the avatar`() {
        show(listOf(row(Link.Connecting)))
        rule.onNodeWithText(HOST).performTouchInput { longClick() }
        rule.waitForIdle()
        val held = rule.onNodeWithText(HOST).fetchSemanticsNode()
        val rowTop = held.positionOnScreen.y
        val rowStart = held.positionOnScreen.x
        val menu = rule.onNodeWithText("Move to top").fetchSemanticsNode()
        val density = held.layoutInfo.density.density
        // The first entry sits under the menu's own 8 dp of padding, so its top is the menu's top plus that.
        val entryTop = menu.positionOnScreen.y
        assertTrue(
            "the menu's first entry, at $entryTop, lies within the row, $rowTop to ${rowTop + held.size.height}",
            entryTop > rowTop && entryTop < rowTop + held.size.height,
        )
        assertEquals((MENU_TOP.value + MENU_PADDING_DP) * density, entryTop - rowTop, 1f)
        assertEquals(MENU_START.value * density, menu.positionOnScreen.x - rowStart, 1f)
    }

    @Test
    fun `no Fermix is the empty state, with Add Fermix in the bar and under the mark`() {
        var added = 0
        rule.setContent {
            FermixTheme {
                ChatsScreen(
                    ChatsUi(emptyList(), emptyList()),
                    ChatsActions({ added++ }, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}),
                )
            }
        }
        rule.onNodeWithText("Add Fermix").performClick()
        assertEquals(1, added)
    }
}
