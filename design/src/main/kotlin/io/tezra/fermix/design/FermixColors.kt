package io.tezra.fermix.design

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours of one mode (design section 13.1): neutral monochrome surfaces, one accent, and status
 * colours for the connection dot and approval receipts. Where section 13.1 fixes no value (the ink,
 * the tonal surface, the scrim), the value is the visual canon's ("Colour — derived for this page").
 * Dynamic Color is rejected in-app (section 13.1), so nothing here comes from the wallpaper.
 */
@Immutable
data class FermixColors(
    /** The window, and every flat surface content lies on. */
    val canvas: Color,
    /** The agent's bubble, and the flat cards and pills of content. */
    val agentBubble: Color,
    /** Dividers, and the 1 dp edge where a control-plane surface meets content. */
    val hairline: Color,
    /** Fermix blue as a fill: the user's bubble, the primary action, focus. */
    val accent: Color,
    /** Fermix blue as text and icons, links among them: lifted in dark mode to read on the canvas. */
    val accentInk: Color,
    /** Text and icons on the accent, and on a filled status colour. */
    val onAccent: Color,
    val ok: Color,
    val warn: Color,
    val err: Color,
    /** Text: primary, secondary (supporting lines) and tertiary (the canon's --ink, --ink2, --ink3). */
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    /** The two control-plane surfaces, the app bar on scroll and the composer dock, at 94 %. */
    val tonal: Color,
    /** The tonal surface, opaque: sheets, menus and dialogs. */
    val tonalSolid: Color,
    /** Behind a sheet, a dialog or a lifted message. */
    val scrim: Color,
    /** The code card, dark in both modes (section 13.5). */
    val codeCard: Color,
    /** The two-dot mark drawn on an instance's tint, in both modes (the canon's `.mark.on`). */
    val onTint: Color,
) {
    companion object {
        val Light =
            FermixColors(
                canvas = Color(0xFFFFFFFF),
                agentBubble = Color(0xFFF3F4F6),
                hairline = Color(0xFFE7E8EC),
                accent = Color(0xFF2B5CFF),
                accentInk = Color(0xFF2B5CFF),
                onAccent = Color(0xFFFFFFFF),
                ok = Color(0xFF1F9D55),
                warn = Color(0xFFC27C0E),
                err = Color(0xFFD93025),
                ink = Color(0xFF121317),
                inkSecondary = Color(0xFF5B5E68),
                inkTertiary = Color(0xFF8B8E98),
                tonal = Color(0xFFECEDF1).copy(alpha = 0.94f),
                tonalSolid = Color(0xFFECEDF1),
                scrim = Color(0xFF0B0B0D).copy(alpha = 0.40f),
                codeCard = Color(0xFF16171B),
                onTint = Color(0xFFFFFFFF),
            )

        val Dark =
            FermixColors(
                canvas = Color(0xFF0B0B0D),
                agentBubble = Color(0xFF1A1B1F),
                hairline = Color(0xFF26272C),
                accent = Color(0xFF2B5CFF),
                accentInk = Color(0xFF5B82FF),
                onAccent = Color(0xFFFFFFFF),
                ok = Color(0xFF1F9D55),
                warn = Color(0xFFC27C0E),
                err = Color(0xFFD93025),
                ink = Color(0xFFF1F2F4),
                inkSecondary = Color(0xFFA3A6AF),
                inkTertiary = Color(0xFF72757E),
                tonal = Color(0xFF202126).copy(alpha = 0.94f),
                tonalSolid = Color(0xFF202126),
                scrim = Color(0xFF000000).copy(alpha = 0.56f),
                codeCard = Color(0xFF16171B),
                onTint = Color(0xFFFFFFFF),
            )
    }
}

/**
 * The six per-instance tones of design section 13.1, muted to about 30 % chroma, with the canon's
 * values. A tint appears in three places only: the avatar, the Custom Tab toolbar and the 2 dp line
 * under the app bar; it is never the accent, and it is the same in both modes.
 */
enum class Tint(
    val color: Color,
) {
    Slate(color = Color(SLATE)),
    Sage(color = Color(SAGE)),
    Clay(color = Color(CLAY)),
    Plum(color = Color(PLUM)),
    Ocean(color = Color(OCEAN)),
    Sand(color = Color(SAND)),
}

// The tints' values, ARGB.
private const val SLATE = 0xFF6B7A90
private const val SAGE = 0xFF76917E
private const val CLAY = 0xFFAD7F6B
private const val PLUM = 0xFF8C7093
private const val OCEAN = 0xFF5C8BA3
private const val SAND = 0xFFB09A6E

/** The colours of the mode FermixTheme draws in. Read outside FermixTheme, it fails. */
val LocalFermixColors =
    staticCompositionLocalOf<FermixColors> { error("LocalFermixColors is read outside FermixTheme.") }

/**
 * Material 3's roles, every one from the design's tokens, so that no Material component draws a
 * baseline colour. One accent: primary is the accent's fill, which Material's filled Button, Checkbox,
 * Switch and Slider draw with onPrimary on it, and the secondary and tertiary roles are the neutral ink
 * and surfaces. Material also draws a TextButton's label, and so a dialog's actions, in primary, which in
 * dark mode is not the lifted ink section 13.1 asks of text and icons: a text button takes
 * [textButtonColors], and a screen that uses another component drawing primary as text or an icon (a
 * focused text field's label, a selected tab, a radio button) passes accentInk to it. Content lies
 * flat, so the surface tint, which colours tonal elevation, is the canvas itself.
 *
 * The containers are mapped by what material3 1.4.0 draws on them, not by Material's rise: the app bar
 * on scroll and menus (surfaceContainer), a docked sheet (surfaceContainerLow) and a dialog
 * (surfaceContainerHigh) are the tonal surface, opaque; a filled card and a filled text field
 * (surfaceContainerHighest) are flat content, the agent bubble's grey. The app bar's 94 % is
 * [FermixColors.tonal], which its screen passes as the scrolled container colour. A filled card takes
 * its content colour from contentColorFor, which answers with the "on" colour of the first role holding
 * the card's grey, primaryContainer, so onPrimaryContainer is the ink. Material fills a FAB with
 * primaryContainer, and the design has no FAB (section 13.1, "Not Telegram").
 *
 * Material draws a sheet's scrim at 32 % of the scrim role, whatever the role's own alpha, not the
 * canon's 40 % and 56 %, so a sheet passes `scrimColor = colors.scrim`; the role is the scrim's colour,
 * opaque. No Material 1.4 component reads the fixed roles; they are set so that none is a baseline colour.
 */
internal fun materialColorScheme(
    colors: FermixColors,
    darkTheme: Boolean,
): ColorScheme {
    val inverse = if (darkTheme) FermixColors.Light else FermixColors.Dark
    return ColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.agentBubble,
        onPrimaryContainer = colors.ink,
        inversePrimary = inverse.accentInk,
        secondary = colors.ink,
        onSecondary = colors.canvas,
        secondaryContainer = colors.agentBubble,
        onSecondaryContainer = colors.ink,
        tertiary = colors.ink,
        onTertiary = colors.canvas,
        tertiaryContainer = colors.agentBubble,
        onTertiaryContainer = colors.ink,
        background = colors.canvas,
        onBackground = colors.ink,
        surface = colors.canvas,
        onSurface = colors.ink,
        surfaceVariant = colors.agentBubble,
        onSurfaceVariant = colors.inkSecondary,
        surfaceTint = colors.canvas,
        inverseSurface = colors.ink,
        inverseOnSurface = colors.canvas,
        error = colors.err,
        onError = colors.onAccent,
        errorContainer = colors.agentBubble,
        onErrorContainer = colors.ink,
        outline = colors.inkTertiary,
        outlineVariant = colors.hairline,
        scrim = colors.scrim.copy(alpha = 1f),
        surfaceBright = if (darkTheme) colors.tonalSolid else colors.canvas,
        surfaceDim = if (darkTheme) colors.canvas else colors.tonalSolid,
        surfaceContainer = colors.tonalSolid,
        surfaceContainerHigh = colors.tonalSolid,
        surfaceContainerHighest = colors.agentBubble,
        surfaceContainerLow = colors.tonalSolid,
        surfaceContainerLowest = colors.canvas,
        primaryFixed = colors.accent,
        primaryFixedDim = colors.accent,
        onPrimaryFixed = colors.onAccent,
        onPrimaryFixedVariant = colors.onAccent,
        secondaryFixed = colors.agentBubble,
        secondaryFixedDim = colors.tonalSolid,
        onSecondaryFixed = colors.ink,
        onSecondaryFixedVariant = colors.inkSecondary,
        tertiaryFixed = colors.agentBubble,
        tertiaryFixedDim = colors.tonalSolid,
        onTertiaryFixed = colors.ink,
        onTertiaryFixedVariant = colors.inkSecondary,
    )
}

/**
 * A text button's colours, a dialog's actions among them (the canon's `.btn.x`): the accent as ink, lifted
 * in dark mode, where the fill Material would draw reads at 3.8:1 on the canvas. Disabled, they are
 * Material's: no container, and the ink at 38 %.
 */
fun textButtonColors(colors: FermixColors): ButtonColors =
    ButtonColors(
        containerColor = Color.Transparent,
        contentColor = colors.accentInk,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = colors.ink.copy(alpha = DISABLED_CONTENT_ALPHA),
    )

/** Material's opacity for disabled content. */
private const val DISABLED_CONTENT_ALPHA = 0.38f
