package io.tezra.fermix.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The M51 update's section 1 (MILESTONE_51_ANDROID_MONOCHROME_AND_WELCOME_MOTION.md), which wins over design
// section 13.1: its tokens (1.2) and Material's roles (1.4). Where it fixes no value (the tertiary ink, the tonal
// surface, the scrim, the code card, the mark on a tint, the tints), design section 13.1 and the visual canon's
// section 1 "Colour — derived for this page" stand. Fermix blue is the one token the owner kept, on 2026-10-05,
// to mark what is unread: the signal.
class FermixColorsTest {
    @Test
    fun `light mode holds the update's values, black ink on white`() {
        val colors = FermixColors.Light
        assertEquals(Color(0xFFFFFFFF), colors.canvas)
        assertEquals(Color(0xFF0B0B0D), colors.ink)
        assertEquals(Color(0xFFFFFFFF), colors.onInk)
        assertEquals(Color(0xFF5B5E66), colors.textSecondary)
        assertEquals(Color(0xFFF3F4F6), colors.agentBubble)
        assertEquals(Color(0xFFE7E8EC), colors.hairline)
        assertEquals(Color(0xFFD93025), colors.errText)
        assertEquals(Color(0xFF16171B), colors.codeCard)
    }

    @Test
    fun `dark mode holds the update's values, a soft off-white ink on a near-black canvas`() {
        val colors = FermixColors.Dark
        assertEquals(Color(0xFF0B0B0D), colors.canvas)
        assertEquals(Color(0xFFDEDFE3), colors.ink)
        assertEquals(Color(0xFF0B0B0D), colors.onInk)
        assertEquals(Color(0xFFA3A6AE), colors.textSecondary)
        assertEquals(Color(0xFF1A1B1F), colors.agentBubble)
        assertEquals(Color(0xFF26272C), colors.hairline)
        assertEquals(Color(0xFFFF6B5E), colors.errText)
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
    fun `Fermix blue is the signal alone, white on it, the same in both modes`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            assertEquals(Color(0xFF2B5CFF), colors.signal)
            assertEquals(Color(0xFFFFFFFF), colors.onSignal)
        }
    }

    @Test
    fun `the selection is the ink at 20 percent`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            assertEquals(colors.ink.copy(alpha = 0.20f), colors.selection)
        }
    }

    // The app is monochrome (the update's 1.1): a token's red, green and blue lie within a few steps of each other,
    // a grey, unless it is a status colour or the signal. Fermix blue spreads 212 steps; the bluest grey, 13.
    @Test
    fun `every token but the status colours and the signal is a grey`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            val greys =
                listOf(
                    colors.canvas,
                    colors.ink,
                    colors.onInk,
                    colors.textSecondary,
                    colors.inkTertiary,
                    colors.agentBubble,
                    colors.hairline,
                    colors.selection,
                    colors.tonal,
                    colors.tonalSolid,
                    colors.scrim,
                    colors.codeCard,
                    colors.onTint,
                    colors.onSignal,
                )
            for (grey in greys) assertTrue(spread(grey) <= GREY_SPREAD, "$grey spreads ${spread(grey)} steps")
            assertTrue(spread(colors.signal) > GREY_SPREAD)
        }
    }

    @Test
    fun `the tertiary ink is the canon's`() {
        assertEquals(Color(0xFF8B8E98), FermixColors.Light.inkTertiary)
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

    // The update's 1.2: the six tints "still never stand in for an accent", and the one blue left is the signal.
    @Test
    fun `no tint is the signal or the ink`() {
        for (tint in Tint.entries) {
            for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
                assertNotEquals(colors.signal, tint.color, "$tint")
                assertNotEquals(colors.ink, tint.color, "$tint")
            }
        }
    }

    @Test
    fun `the Material scheme is built from the light tokens`() {
        val scheme = materialColorScheme(FermixColors.Light, darkTheme = false)
        assertEquals(Color(0xFF0B0B0D), scheme.primary)
        assertEquals(Color(0xFFFFFFFF), scheme.onPrimary)
        assertEquals(Color(0xFFFFFFFF), scheme.surface)
        assertEquals(Color(0xFFFFFFFF), scheme.background)
        assertEquals(Color(0xFF0B0B0D), scheme.onSurface)
        assertEquals(Color(0xFF0B0B0D), scheme.onBackground)
        assertEquals(Color(0xFF5B5E66), scheme.onSurfaceVariant)
        assertEquals(Color(0xFFECEDF1), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFFE7E8EC), scheme.outlineVariant)
        assertEquals(Color(0xFFD93025), scheme.error)
        assertEquals(Color(0xFFF3F4F6), scheme.primaryContainer)
        assertEquals(Color(0xFF0B0B0D), scheme.onPrimaryContainer)
    }

    @Test
    fun `the Material scheme is built from the dark tokens, its error the dark mode's error text`() {
        val scheme = materialColorScheme(FermixColors.Dark, darkTheme = true)
        assertEquals(Color(0xFFDEDFE3), scheme.primary)
        assertEquals(Color(0xFF0B0B0D), scheme.onPrimary)
        assertEquals(Color(0xFF0B0B0D), scheme.surface)
        assertEquals(Color(0xFF0B0B0D), scheme.background)
        assertEquals(Color(0xFFDEDFE3), scheme.onSurface)
        assertEquals(Color(0xFFDEDFE3), scheme.onBackground)
        assertEquals(Color(0xFFA3A6AE), scheme.onSurfaceVariant)
        assertEquals(Color(0xFF202126), scheme.surfaceContainerHigh)
        assertEquals(Color(0xFF26272C), scheme.outlineVariant)
        assertEquals(Color(0xFFFF6B5E), scheme.error)
        assertEquals(Color(0xFF1A1B1F), scheme.primaryContainer)
        assertEquals(Color(0xFFDEDFE3), scheme.onPrimaryContainer)
    }

    // The update's 1.4: "build both ColorSchemes explicitly so no role falls back to Material's baseline purple".
    // Every role is one of the mode's tokens, and none is the signal, which marks only what is unread and so is never
    // what Material draws.
    @Test
    fun `every Material role is one of the mode's tokens, never the signal`() {
        val modes = listOf(FermixColors.Light to false, FermixColors.Dark to true)
        for ((colors, darkTheme) in modes) {
            val roles = rolesOf(materialColorScheme(colors, darkTheme))
            assertTrue("Primary" in roles && "SurfaceContainerHighest" in roles, "the roles read: ${roles.keys}")
            val tokens = materialTokensOf(colors)
            for ((role, color) in roles) {
                assertTrue(color in tokens, "$role is $color, no token of ${if (darkTheme) "dark" else "light"} mode")
                assertNotEquals(colors.signal, color, role)
            }
        }
    }

    // A role is also held to differ from the value Material's baseline gives it. The only roles that hold the
    // baseline's are white and black that 1.2 fixes itself: in light mode onInk, which every "on" role of a fill in
    // the ink and of the error is, and the canvas, the lowest container; in dark mode the scrim's opaque black.
    @Test
    fun `no Material role is the baseline's, but for the white and the black the update fixes`() {
        val light = sameAsBaseline(materialColorScheme(FermixColors.Light, darkTheme = false), lightColorScheme())
        assertEquals(setOf("OnPrimary", "OnSecondary", "OnTertiary", "OnError", "SurfaceContainerLowest"), light)
        assertEquals(Color(0xFFFFFFFF), FermixColors.Light.onInk)
        assertEquals(Color(0xFFFFFFFF), FermixColors.Light.canvas)
        val dark = sameAsBaseline(materialColorScheme(FermixColors.Dark, darkTheme = true), darkColorScheme())
        assertEquals(setOf("Scrim"), dark)
        assertEquals(Color(0xFF000000), FermixColors.Dark.scrim.copy(alpha = 1f))
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
    // holds the same value; primaryContainer comes before surfaceContainerHighest in that list. On the primary
    // fill, the ink, Material draws onInk: nothing of the ink is drawn on the ink.
    @Test
    fun `Material draws the content of a filled card, a dialog and the canvas in the ink, and of the ink in onInk`() {
        val modes = listOf(FermixColors.Light to false, FermixColors.Dark to true)
        for ((colors, darkTheme) in modes) {
            val scheme = materialColorScheme(colors, darkTheme)
            assertEquals(colors.ink, scheme.contentColorFor(scheme.surfaceContainerHighest), "dark $darkTheme")
            assertEquals(colors.ink, scheme.contentColorFor(scheme.surfaceContainerHigh), "dark $darkTheme")
            assertEquals(colors.ink, scheme.contentColorFor(scheme.surface), "dark $darkTheme")
            assertEquals(colors.onInk, scheme.contentColorFor(scheme.primary), "dark $darkTheme")
        }
    }

    @Test
    fun `a text button draws the ink, and 38 percent of it disabled`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            assertEquals(colors.ink, textButtonColors(colors).contentColor)
            assertEquals(Color.Transparent, textButtonColors(colors).containerColor)
            assertEquals(Color.Transparent, textButtonColors(colors).disabledContainerColor)
            assertEquals(colors.ink.copy(alpha = 0.38f), textButtonColors(colors).disabledContentColor)
        }
    }

    @Test
    fun `the mark on a tint is white in both modes`() {
        assertEquals(Color(0xFFFFFFFF), FermixColors.Light.onTint)
        assertEquals(Color(0xFFFFFFFF), FermixColors.Dark.onTint)
    }
}

/** How far apart a grey's channels may lie, in steps of 255. */
private const val GREY_SPREAD = 16

/** The steps between [color]'s strongest and weakest channel, of 255. */
private fun spread(color: Color): Int {
    val channels = listOf(color.red, color.green, color.blue).map { Math.round(it * CHANNEL_STEPS) }
    return channels.max() - channels.min()
}

private const val CHANNEL_STEPS = 255f

/**
 * The colours a Material role may hold in a mode: its tokens, opaque, the scrim's colour among them, as Material
 * applies a scrim's alpha itself; the signal is left out.
 */
private fun materialTokensOf(colors: FermixColors): Set<Color> =
    setOf(
        colors.canvas,
        colors.ink,
        colors.onInk,
        colors.textSecondary,
        colors.inkTertiary,
        colors.agentBubble,
        colors.hairline,
        colors.tonalSolid,
        colors.scrim.copy(alpha = 1f),
        colors.err,
        colors.errText,
        colors.ok,
        colors.warn,
        colors.codeCard,
        colors.onTint,
    )

/** The roles of [scheme] that hold the value [baseline], Material's own scheme, gives them. */
private fun sameAsBaseline(
    scheme: ColorScheme,
    baseline: ColorScheme,
): Set<String> {
    val base = rolesOf(baseline)
    return rolesOf(scheme).filter { (role, color) -> base[role] == color }.keys
}

/**
 * Every colour role of [scheme], by name: its getters that answer a Color, which the compiler hands back as the
 * colour's packed long under a mangled name (`getPrimary-0d7_KjU`). Read so, a role material3 adds is held to the
 * tokens too, where a list written here would leave it out.
 */
private fun rolesOf(scheme: ColorScheme): Map<String, Color> =
    ColorScheme::class.java.methods
        .filter { it.name.startsWith("get") && it.parameterCount == 0 }
        .filter { it.returnType == Long::class.javaPrimitiveType }
        .associate { it.name.removePrefix("get").substringBefore('-') to Color((it.invoke(scheme) as Long).toULong()) }
