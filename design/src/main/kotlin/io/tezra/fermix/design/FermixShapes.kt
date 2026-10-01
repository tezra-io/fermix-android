package io.tezra.fermix.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Who wrote a bubble, and so which side of the column it sits on: the user's at the end, the agent's at the start. */
enum class Sender { User, Agent }

/** A bubble's place among the same sender's bubbles that group with it (within [FermixShapes.bubbleGroupWindow]). */
enum class GroupPosition { Single, First, Middle, Last }

/** The corners of design section 13.1, "Shape and density". */
object FermixShapes {
    /** A bubble's outer corner. Bubbles have no tails. */
    val bubbleCorner: Dp = 20.dp

    /** A grouped bubble's corner on the sender's side. */
    val bubbleInnerCorner: Dp = 6.dp

    /** Bubbles from one sender group when they are this close in time. */
    val bubbleGroupWindow: Duration = 2.minutes

    /** Code, table, preview, approval and error cards. */
    val card = RoundedCornerShape(16.dp)

    /** Sheets: the palette, the model picker, attach. */
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** The composer dock: a pill while it holds one line, 28 dp corners as it grows to six. */
    val dock = RoundedCornerShape(28.dp)

    /** Chips. */
    val chip = RoundedCornerShape(10.dp)

    /** Buttons: a full pill. */
    val button = RoundedCornerShape(percent = 50)

    /**
     * Material's slots, by what material3 1.4.0 draws in them: extraSmall holds menus, outlined text
     * fields, snackbars and tooltips, which the visual canon draws at 16 dp (its `.menu` and `.sfld`);
     * small holds chips; medium and large hold cards; and extraLarge, which Material gives sheets (its
     * top) and dialogs, is 28 dp.
     */
    val material =
        Shapes(
            extraSmall = card,
            small = chip,
            medium = card,
            large = card,
            extraLarge = RoundedCornerShape(28.dp),
        )
}

/**
 * A bubble's corners (design section 13.1): 20 dp, except that within a group the corners that face a
 * neighbour on the sender's side are 6 dp, and the last bubble keeps a 6 dp corner at the bottom on the
 * sender's side, as the visual canon draws it. A bubble alone is the last of a group of one; the first
 * of a group already has its 6 dp corner there, facing the next.
 */
fun bubbleShape(
    sender: Sender,
    position: GroupPosition,
): RoundedCornerShape {
    val outer = FermixShapes.bubbleCorner
    val inner = FermixShapes.bubbleInnerCorner
    val topOnSenderSide = if (position == GroupPosition.Middle || position == GroupPosition.Last) inner else outer
    return when (sender) {
        Sender.User -> {
            RoundedCornerShape(topStart = outer, topEnd = topOnSenderSide, bottomEnd = inner, bottomStart = outer)
        }

        Sender.Agent -> {
            RoundedCornerShape(topStart = topOnSenderSide, topEnd = outer, bottomEnd = outer, bottomStart = inner)
        }
    }
}
