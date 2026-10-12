package io.tezra.fermix.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The avatar's eyes' sheet: each of the six styles on a disc at the three sizes the app draws an avatar, the chat bar's
// 32 dp, a row's 48 and the Instance header's 72, on three tints, a warm, a cool and the greyest, each style still, so
// the sheet draws no clock. Its words name styles and sizes, not product copy, so they stay out of strings.xml, as the
// other sheets' names do.

private val SHEET_PADDING = 16.dp
private val SIZES = listOf(32.dp, 48.dp, 72.dp)
private val TINTS = listOf(Tint.Coral, Tint.Teal, Tint.Slate)

/** The six eye styles in a row, once per size and tint, the rows named once beneath them. */
@FermixPreviews
@Composable
fun SpecimenEyes() {
    FermixPreviewTheme {
        val colors = LocalFermixColors.current
        FermixColumn(ColumnWidth.Wide) {
            Column(modifier = Modifier.padding(SHEET_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (size in SIZES) {
                    for (tint in TINTS) EyesRow(tint, size)
                }
                Text(
                    text =
                        EyeStyle.entries.joinToString(postfix = ", at 32, 48 and 72 dp on Coral, Teal and Slate") {
                            it.name
                        },
                    style = FermixType.labelSmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** The six styles on discs of [tint], [size] across. */
@Composable
private fun EyesRow(
    tint: Tint,
    size: Dp,
) {
    val paths = remember { MarkEyePaths() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (style in EyeStyle.entries) {
            Canvas(Modifier.size(size)) {
                drawCircle(tint.color)
                drawAvatarEyes(style, this.size, paths)
            }
        }
    }
}
