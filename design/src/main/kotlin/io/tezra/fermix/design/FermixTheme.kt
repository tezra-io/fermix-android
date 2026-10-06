package io.tezra.fermix.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * The design language of section 13.1 for [content]: the design's colours in the system's mode (never
 * Dynamic Color), its type, its shapes, the standard motion scheme, and the owner's reduce-motion
 * setting, all handed to Material 3 as well, so that its components draw in the same language. Selected text is
 * washed in the selection, the ink at 20 % (the M51 update's 1.2), where Material would wash it in its primary, the
 * ink, at 40 %.
 */
@Composable
fun FermixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) FermixColors.Dark else FermixColors.Light
    val colorScheme = remember(colors, darkTheme) { materialColorScheme(colors, darkTheme) }
    val selection =
        remember(colors) { TextSelectionColors(handleColor = colors.ink, backgroundColor = colors.selection) }
    CompositionLocalProvider(
        LocalFermixColors provides colors,
        LocalFermixMotion provides FermixMotionScheme.Standard,
        LocalReducedMotion provides rememberReducedMotion(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = FermixShapes.material,
            typography = FermixType.material,
        ) {
            CompositionLocalProvider(LocalTextSelectionColors provides selection, content = content)
        }
    }
}
