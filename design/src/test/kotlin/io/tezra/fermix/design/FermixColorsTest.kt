package io.tezra.fermix.design

import androidx.compose.material3.contentColorFor
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

// Design section 13.1, and where it fixes no value, the visual canon's section 1 "Colour — derived
// for this page" (MILESTONE_51_ANDROID_COMPANION_APP_UI.html, its --ink, --tonal and --scrim).
class FermixColorsTest {
    @Test
    fun `light mode holds the design's values`() {
        val colors = FermixColors.Light
        assertEquals(Color(0xFFFFFFFF), colors.canvas)
        assertEquals(Color(0xFFF3F4F6), colors.agentBubble)
        assertEquals(Color(0xFFE7E8EC), colors.hairline)
        assertEquals(Color(0xFF2B5CFF), colors.accent)
        assertEquals(Color(0xFF2B5CFF), colors.accentInk)
        assertEquals(Color(0xFFFFFFFF), colors.onAccent)
        assertEquals(Color(0xFF16171B), colors.codeCard)
    }

    @Test
    fun `dark mode holds the design's values and lifts the accent for text and icons`() {
        val colors = FermixColors.Dark
        assertEquals(Color(0xFF0B0B0D), colors.canvas)
        assertEquals(Color(0xFF1A1B1F), colors.agentBubble)
        assertEquals(Color(0xFF26272C), colors.hairline)
        assertEquals(Color(0xFF2B5CFF), colors.accent)
        assertEquals(Color(0xFF5B82FF), colors.accentInk)
        assertEquals(Color(0xFFFFFFFF), colors.onAccent)
        assertEquals(Color(0xFF16171B), colors.codeCard)
    }

    @Test
    fun `the status colours are the same in both modes`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            assertEquals(Color(0xFF1F9D55), colors.ok)
            assertEquals(Color(0xFFC27C0E), colors.warn)
            assertEquals(Color(0xFFD93025), colors.err)
        }
    }

    @Test
    fun `the ink shades are the canon's`() {
        assertEquals(Color(0xFF121317), FermixColors.Light.ink)
        assertEquals(Color(0xFF5B5E68), FermixColors.Light.inkSecondary)
        assertEquals(Color(0xFF8B8E98), FermixColors.Light.inkTertiary)
        assertEquals(Color(0xFFF1F2F4), FermixColors.Dark.ink)
        assertEquals(Color(0xFFA3A6AF), FermixColors.Dark.inkSecondary)
        assertEquals(Color(0xFF72757E), FermixColors.Dark.inkTertiary)
    }

    @Test
    fun `the tonal surface is the canon's at 94 percent, and opaque for sheets`() {
        assertEquals(Color(0xFFECEDF1), FermixColors.Light.tonalSolid)
        assertEquals(Color(0xFFECEDF1).copy(alpha = 0.94f), FermixColors.Light.tonal)
        assertEquals(Color(0xFF202126), FermixColors.Dark.tonalSolid)
        assertEquals(Color(0xFF202126).copy(alpha = 0.94f), FermixColors.Dark.tonal)
    }

    @Test
    fun `the scrim is the canon's`() {
        assertEquals(Color(0xFF0B0B0D).copy(alpha = 0.40f), FermixColors.Light.scrim)
        assertEquals(Color(0xFF000000).copy(alpha = 0.56f), FermixColors.Dark.scrim)
    }

    @Test
    fun `the six tints are the canon's, in the design's order`() {
        val expected =
            listOf(
                Tint.Slate to Color(0xFF6B7A90),
                Tint.Sage to Color(0xFF76917E),
                Tint.Clay to Color(0xFFAD7F6B),
                Tint.Plum to Color(0xFF8C7093),
                Tint.Ocean to Color(0xFF5C8BA3),
                Tint.Sand to Color(0xFFB09A6E),
            )
        assertEquals(expected, Tint.entries.map { it to it.color })
    }

    @Test
    fun `no tint is the accent`() {
        for (tint in Tint.entries) {
            assertNotEquals(FermixColors.Light.accent, tint.color, "$tint")
            assertNotEquals(FermixColors.Dark.accentInk, tint.color, "$tint")
        }
    }

    @Test
    fun `the Material scheme is built from the light tokens`() {
        val scheme = materialColorScheme(FermixColors.Light, darkTheme = false)
        assertEquals(Color(0xFF2B5CFF), scheme.primary)
        assertEquals(Color(0xFFFFFFFF), scheme.onPrimary)
        assertEquals(Color(0xFFFFFFFF), scheme.surface)
        assertEquals(Color(0xFFFFFFFF), scheme.background)
        assertEquals(Color(0xFF121317), scheme.onSurface)
        assertEquals(Color(0xFFECEDF1), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFFE7E8EC), scheme.outlineVariant)
        assertEquals(Color(0xFFD93025), scheme.error)
    }

    @Test
    fun `the Material scheme is built from the dark tokens`() {
        val scheme = materialColorScheme(FermixColors.Dark, darkTheme = true)
        assertEquals(Color(0xFF2B5CFF), scheme.primary)
        assertEquals(Color(0xFFFFFFFF), scheme.onPrimary)
        assertEquals(Color(0xFF0B0B0D), scheme.surface)
        assertEquals(Color(0xFF0B0B0D), scheme.background)
        assertEquals(Color(0xFFF1F2F4), scheme.onSurface)
        assertEquals(Color(0xFF202126), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFF26272C), scheme.outlineVariant)
        assertEquals(Color(0xFFD93025), scheme.error)
    }

    @Test
    fun `tonal elevation tints nothing, since content lies flat`() {
        assertEquals(FermixColors.Light.canvas, materialColorScheme(FermixColors.Light, darkTheme = false).surfaceTint)
        assertEquals(FermixColors.Dark.canvas, materialColorScheme(FermixColors.Dark, darkTheme = true).surfaceTint)
    }

    // material3 1.4.0 draws the app bar on scroll and menus on surfaceContainer, a docked sheet on
    // surfaceContainerLow, a dialog on surfaceContainerHigh, and a filled card on surfaceContainerHighest.
    @Test
    fun `Material's bars, menus, sheets and dialogs are the tonal surface, its cards the agent bubble's grey`() {
        val light = materialColorScheme(FermixColors.Light, darkTheme = false)
        assertEquals(Color(0xFFECEDF1), light.surfaceContainer)
        assertEquals(Color(0xFFECEDF1), light.surfaceContainerLow)
        assertEquals(Color(0xFFECEDF1), light.surfaceContainerHigh)
        assertEquals(Color(0xFFF3F4F6), light.surfaceContainerHighest)
        assertEquals(Color(0xFFFFFFFF), light.surfaceContainerLowest)
        val dark = materialColorScheme(FermixColors.Dark, darkTheme = true)
        assertEquals(Color(0xFF202126), dark.surfaceContainer)
        assertEquals(Color(0xFF202126), dark.surfaceContainerLow)
        assertEquals(Color(0xFF202126), dark.surfaceContainerHigh)
        assertEquals(Color(0xFF1A1B1F), dark.surfaceContainerHighest)
        assertEquals(Color(0xFF0B0B0D), dark.surfaceContainerLowest)
    }

    // A filled card, like any Material container without a content colour of its own, takes
    // contentColorFor(its container), which returns the "on" colour of the first role in its list that
    // holds the same value; primaryContainer comes before surfaceContainerHighest in that list.
    @Test
    fun `Material draws the content of a filled card, a dialog and the canvas in the ink`() {
        val modes = listOf(FermixColors.Light to false, FermixColors.Dark to true)
        for ((colors, darkTheme) in modes) {
            val scheme = materialColorScheme(colors, darkTheme)
            assertEquals(colors.ink, scheme.contentColorFor(scheme.surfaceContainerHighest), "dark $darkTheme")
            assertEquals(colors.ink, scheme.contentColorFor(scheme.surfaceContainerHigh), "dark $darkTheme")
            assertEquals(colors.ink, scheme.contentColorFor(scheme.surface), "dark $darkTheme")
        }
    }

    @Test
    fun `a text button draws the accent as ink, lifted in dark mode`() {
        assertEquals(Color(0xFF2B5CFF), textButtonColors(FermixColors.Light).contentColor)
        assertEquals(Color(0xFF5B82FF), textButtonColors(FermixColors.Dark).contentColor)
        assertEquals(Color.Transparent, textButtonColors(FermixColors.Dark).containerColor)
        assertEquals(Color.Transparent, textButtonColors(FermixColors.Dark).disabledContainerColor)
        assertEquals(Color(0xFFF1F2F4).copy(alpha = 0.38f), textButtonColors(FermixColors.Dark).disabledContentColor)
    }

    @Test
    fun `the mark on a tint is white in both modes`() {
        assertEquals(Color(0xFFFFFFFF), FermixColors.Light.onTint)
        assertEquals(Color(0xFFFFFFFF), FermixColors.Dark.onTint)
    }
}
