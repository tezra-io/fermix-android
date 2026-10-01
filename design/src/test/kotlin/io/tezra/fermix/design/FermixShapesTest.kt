package io.tezra.fermix.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

// Design section 13.1, "Shape and density", as the visual canon draws it: no tails; a group's inner
// corners are 6 dp, and its last bubble keeps a 6 dp corner at the bottom on the sender's side, a
// bubble alone being the last of a group of one.
class FermixShapesTest {
    private class Row(
        val sender: Sender,
        val position: GroupPosition,
        val topStart: Int,
        val topEnd: Int,
        val bottomEnd: Int,
        val bottomStart: Int,
    )

    // The user's side is the end, the agent's the start.
    private val table =
        listOf(
            Row(Sender.User, GroupPosition.Single, 20, 20, 6, 20),
            Row(Sender.User, GroupPosition.First, 20, 20, 6, 20),
            Row(Sender.User, GroupPosition.Middle, 20, 6, 6, 20),
            Row(Sender.User, GroupPosition.Last, 20, 6, 6, 20),
            Row(Sender.Agent, GroupPosition.Single, 20, 20, 20, 6),
            Row(Sender.Agent, GroupPosition.First, 20, 20, 20, 6),
            Row(Sender.Agent, GroupPosition.Middle, 6, 20, 20, 6),
            Row(Sender.Agent, GroupPosition.Last, 6, 20, 20, 6),
        )

    @Test
    fun `the table covers every sender at every place in a group`() {
        val covered = table.map { it.sender to it.position }.toSet()
        assertEquals(Sender.entries.size * GroupPosition.entries.size, covered.size)
        assertEquals(covered.size, table.size)
    }

    @Test
    fun `a bubble's corners follow its sender and its place in the group`() {
        for (row in table) {
            val expected =
                RoundedCornerShape(
                    topStart = row.topStart.dp,
                    topEnd = row.topEnd.dp,
                    bottomEnd = row.bottomEnd.dp,
                    bottomStart = row.bottomStart.dp,
                )
            assertEquals(expected, bubbleShape(row.sender, row.position), "${row.sender} ${row.position}")
        }
    }

    @Test
    fun `bubbles from one sender group within two minutes`() {
        assertEquals(2.minutes, FermixShapes.bubbleGroupWindow)
    }

    @Test
    fun `cards, sheets, the dock, chips and buttons`() {
        assertEquals(RoundedCornerShape(16.dp), FermixShapes.card)
        assertEquals(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), FermixShapes.sheet)
        assertEquals(RoundedCornerShape(28.dp), FermixShapes.dock)
        assertEquals(RoundedCornerShape(10.dp), FermixShapes.chip)
        assertEquals(RoundedCornerShape(percent = 50), FermixShapes.button)
    }

    @Test
    fun `Material's shape slots take the design's corners`() {
        val material = FermixShapes.material
        // material3 1.4.0 draws menus and outlined text fields in extraSmall: the canon's 16 dp .menu and .sfld.
        assertEquals(FermixShapes.card, material.extraSmall)
        assertEquals(FermixShapes.chip, material.small)
        assertEquals(FermixShapes.card, material.medium)
        assertEquals(FermixShapes.card, material.large)
        assertEquals(RoundedCornerShape(28.dp), material.extraLarge)
    }

    @Test
    fun `spacing is the design's density`() {
        assertEquals(4.dp, FermixSpacing.grid)
        assertEquals(2.dp, FermixSpacing.withinGroup)
        assertEquals(12.dp, FermixSpacing.betweenGroups)
        assertEquals(12.dp, FermixSpacing.gutter)
        assertEquals(12.dp, FermixSpacing.bubblePaddingHorizontal)
        assertEquals(9.dp, FermixSpacing.bubblePaddingVertical)
        assertEquals(48.dp, FermixSpacing.minTarget)
        assertEquals(48.dp, FermixSpacing.avatar)
        assertEquals(32.dp, FermixSpacing.avatarSmall)
        assertEquals(1.dp, FermixSpacing.hairline)
        assertEquals(2.dp, FermixSpacing.tintLine)
        assertEquals(0.78f, FermixSpacing.USER_BUBBLE_MAX_WIDTH)
        assertEquals(0.88f, FermixSpacing.AGENT_BUBBLE_MAX_WIDTH)
        assertEquals(0.6f, FermixSpacing.TIMESTAMP_ALPHA)
    }
}
