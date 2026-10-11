package io.tezra.fermix.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI

// The thinking card's two-dot mark's sheet (the owner, 2026-10-10): the mark over its halo at the foot of a breath,
// at rest, which reduce-motion and the card's screenshots hold, and at the top, then the dots a quarter turn on. Each
// pose is fixed, never a clock's. Its labels name poses, not product copy, so they stay out of strings.xml, as the
// other sheets' names do.

private val CELL = 48.dp
private val SHEET_PADDING = 16.dp

/** The poses: the halo's share of its 28 dp, and the dots' angle. */
private val POSES =
    listOf(
        "foot, 0.92" to (0.92f to ORBIT_POSE),
        "rest, 1" to (1f to ORBIT_POSE),
        "top, 1.08" to (1.08f to ORBIT_POSE),
        "a quarter turn" to (1f to ORBIT_POSE + PI.toFloat() / 2f),
    )

/** The mark at each pose, its halo's size named under it. */
@FermixPreviews
@Composable
fun SpecimenTwoDotMark() {
    FermixPreviewTheme {
        val colors = LocalFermixColors.current
        FermixColumn(ColumnWidth.Wide) {
            Row(modifier = Modifier.padding(SHEET_PADDING), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((name, pose) in POSES) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.size(CELL), contentAlignment = Alignment.Center) {
                            TwoDotMarkAt(TwoDotMotion(angle = pose.second, haloScale = pose.first))
                        }
                        Text(
                            text = name,
                            style = FermixType.labelSmall,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
