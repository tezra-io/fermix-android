package io.tezra.fermix.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The two widths of design section 13.11 rule 2's centred column, [FermixColumn]. */
enum class ColumnWidth(
    val width: Dp,
) {
    /** The timeline, the composer, the chats list, search and sheets. */
    Wide(width = 640.dp),

    /** Onboarding and the full-screen states. */
    Narrow(width = 480.dp),
}
