package io.tezra.fermix.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * The design language of section 13.1 for [content]: the design's colours in the system's mode (never
 * Dynamic Color), its type, its shapes, the standard motion scheme, and the owner's reduce-motion
 * setting, all handed to Material 3 as well, so that its components draw in the same language.
 */
@Composable
fun FermixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) FermixColors.Dark else FermixColors.Light
    val colorScheme = remember(colors, darkTheme) { materialColorScheme(colors, darkTheme) }
    CompositionLocalProvider(
        LocalFermixColors provides colors,
        LocalFermixMotion provides FermixMotionScheme.Standard,
        LocalReducedMotion provides rememberReducedMotion(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = FermixShapes.material,
            typography = FermixType.material,
            content = content,
        )
    }
}
