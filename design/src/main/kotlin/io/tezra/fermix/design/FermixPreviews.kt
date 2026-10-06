package io.tezra.fermix.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

// The three windows of design section 13.11 and the `screens` job (CI/CD design section 3): a phone, the
// inner screen of a book-style fold held upright, and the same held on its side. 320 dpi draws each dp
// as two whole pixels, so a 1 dp hairline is crisp and the images stay small.
private const val COMPACT = "spec:width=412dp,height=915dp,dpi=320"
private const val MEDIUM = "spec:width=673dp,height=841dp,dpi=320"
private const val EXPANDED = "spec:width=841dp,height=673dp,dpi=320"
private const val LIGHT = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL
private const val DARK = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL

/**
 * Every screen's previews: a compact, a medium and an expanded window, light and dark, at font scale
 * 1.0 and 2.0 (section 13.8). Each is also a screenshot test, so a preview annotated with this is drawn
 * twelve times by `./gradlew recordRoborazziDebug` into its module's src/test/screenshots, which git does
 * not track, and compared only on a machine that recorded before a change (`compareRoborazziDebug`,
 * `verifyRoborazziDebug`).
 */
@Preview(name = "compact light 1.0", device = COMPACT, uiMode = LIGHT, fontScale = 1f)
@Preview(name = "compact dark 1.0", device = COMPACT, uiMode = DARK, fontScale = 1f)
@Preview(name = "compact light 2.0", device = COMPACT, uiMode = LIGHT, fontScale = 2f)
@Preview(name = "compact dark 2.0", device = COMPACT, uiMode = DARK, fontScale = 2f)
@Preview(name = "medium light 1.0", device = MEDIUM, uiMode = LIGHT, fontScale = 1f)
@Preview(name = "medium dark 1.0", device = MEDIUM, uiMode = DARK, fontScale = 1f)
@Preview(name = "medium light 2.0", device = MEDIUM, uiMode = LIGHT, fontScale = 2f)
@Preview(name = "medium dark 2.0", device = MEDIUM, uiMode = DARK, fontScale = 2f)
@Preview(name = "expanded light 1.0", device = EXPANDED, uiMode = LIGHT, fontScale = 1f)
@Preview(name = "expanded dark 1.0", device = EXPANDED, uiMode = DARK, fontScale = 1f)
@Preview(name = "expanded light 2.0", device = EXPANDED, uiMode = LIGHT, fontScale = 2f)
@Preview(name = "expanded dark 2.0", device = EXPANDED, uiMode = DARK, fontScale = 2f)
annotation class FermixPreviews

/**
 * A preview's theme: [FermixTheme] in the preview's mode on the canvas, so that a preview shows what the
 * app shows. The window's width class needs no setting here: [FermixColumn] reads it from the window,
 * and a preview's window is its device.
 */
@Composable
fun FermixPreviewTheme(content: @Composable () -> Unit) {
    FermixTheme {
        Box(modifier = Modifier.fillMaxSize().background(LocalFermixColors.current.canvas)) { content() }
    }
}
