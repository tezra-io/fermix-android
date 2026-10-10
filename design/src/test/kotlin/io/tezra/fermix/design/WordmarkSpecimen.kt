package io.tezra.fermix.design

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// The Fermix wordmark's sheet: the file drawn at the heights the app draws it, Welcome's 40 dp and the Chats list's
// bar's 24 dp, and large, at 96 dp, each in its box's hairline, so the file's own margin shows. Its labels name sizes,
// not product copy, so they stay out of strings.xml, as the other sheets' names do.

private val SHEET_PADDING = 16.dp
private val SIZES = listOf(96.dp to "96 dp", 40.dp to "40 dp, Welcome", 24.dp to "24 dp, the Chats list's bar")

/** The wordmark at 96, 40 and 24 dp, the letters in the ink and the dots in the signal, each box in the hairline. */
@FermixPreviews
@Composable
fun SpecimenWordmark() {
    FermixPreviewTheme {
        val colors = LocalFermixColors.current
        FermixColumn(ColumnWidth.Wide) {
            Column(modifier = Modifier.padding(SHEET_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((height, name) in SIZES) {
                    FermixWordmark(height, Modifier.border(FermixSpacing.hairline, colors.hairline))
                    Text(text = name, style = FermixType.labelSmall, color = colors.textSecondary)
                }
            }
        }
    }
}
