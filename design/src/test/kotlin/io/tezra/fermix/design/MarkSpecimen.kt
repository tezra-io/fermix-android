package io.tezra.fermix.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The Fermix mark's sheet: its moments read off one image in each mode, every pose fixed, never a clock's (the
// preview's tester sets no inspection mode, so it would draw whatever a clock had reached). Its labels name instants,
// not product copy, so they stay out of strings.xml, as the other sheets' names do.

/** The mark at the size Verify draws it; each cell leaves room above it for the drop's dot and the hop. */
private val MARK = 56.dp
private val CELL = 76.dp
private val ROOM_ABOVE = 14.dp
private val SHEET_PADDING = 16.dp

/** The drop's beats (the M51 update's 3.1), in ms on its clock: the fall, the landing, the swell, the eyes, a blink. */
private val DROP_BEATS = listOf(0, 200, 380, 400, 470, 600, 800, 900, 1_000, 1_140, 1_200, 1_380, 1_460)

/** An idle blink at its middle, 75 ms into its 150: the eyes at a tenth of their height. */
private val BLINK_MIDDLE = MarkPose.Rest.idling(MarkIdle(ms = 75f, blinks = Blinks(start = 0f, next = 3_000f)))

/** The drop's beats side by side, then an idle blink's middle, the happy eyes, and the hop's top at 250 ms. */
private val SHEET: List<Pair<String, MarkPose>> =
    DROP_BEATS.map { ms -> "${thousands(ms)} ms" to dropAt(ms.toFloat()) } +
        listOf(
            "blink" to BLINK_MIDDLE,
            "happy" to hopAt(Hop.CLOCK_MILLIS.toFloat()),
            "hop's top" to hopAt(250f),
        )

/** The drop's beats side by side, then the blink's middle, the happy eyes and the hop's top, each named. */
@FermixPreviews
@Composable
fun SpecimenMark() {
    FermixPreviewTheme {
        val colors = LocalFermixColors.current
        FermixColumn(ColumnWidth.Wide) {
            FlowRow(
                modifier = Modifier.padding(SHEET_PADDING),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((name, pose) in SHEET) {
                    Column(
                        modifier = Modifier.width(CELL).padding(top = ROOM_ABOVE),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FermixMark(pose = { pose }, size = MARK)
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

/** [ms] with a comma between its thousands, as the update writes its times. */
private fun thousands(ms: Int): String {
    if (ms < 1_000) return "$ms"
    val rest = (ms % 1_000).toString().padStart(3, '0')
    return "${ms / 1_000},$rest"
}
