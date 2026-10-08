package io.tezra.fermix.design

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours of one mode, monochrome (the M51 update's section 1.2, which wins over design section 13.1): white
 * in light mode and near-black in dark, one ink for text, the primary action, the owner's bubble and links, status
 * colours for the connection dot, approval receipts and the security event, and Fermix blue as [signal] alone,
 * which the owner kept on 2026-10-05 to mark what is unread. Where the update fixes no value (the tertiary ink, the
 * tonal surface, the scrim, the code card), the value is the visual canon's ("Colour — derived for this page").
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
    /**
     * Primary text and icons, and as a fill the primary action, the owner's bubble, a switch that is on and a
     * chosen chip; links in it are always underlined, and focus, a caret and progress are drawn in it.
     */
    val ink: Color,
    /** Text and icons on the ink. */
    val onInk: Color,
    /** Secondary text: supporting lines, timestamps, hints. */
    val textSecondary: Color,
    /** The tertiary ink (the canon's --ink3): a dot that is off, a provider that cannot be listed. */
    val inkTertiary: Color,
    /** The ink at 20 %: selected text, a selected message's row, words search marks. */
    val selection: Color,
    val ok: Color,
    val warn: Color,
    /** The error colour as a fill: the connection dot, an error card's rule, the security event's edge. */
    val err: Color,
    /** The error colour as text and icons: [err] in light mode, lifted in dark mode to read on the canvas. */
    val errText: Color,
    /** Fermix blue, which marks what is unread and nothing else: a row's count, the timeline's divider, the pill's. */
    val signal: Color,
    /** The count on the signal. */
    val onSignal: Color,
    /** The two control-plane surfaces, the app bar on scroll and the composer dock, at 94 %. */
    val tonal: Color,
    /** The tonal surface, opaque: sheets, menus and dialogs. */
    val tonalSolid: Color,
    /** Behind a sheet, a dialog or a lifted message. */
    val scrim: Color,
    /** The code card, dark in both modes (section 13.5). */
    val codeCard: Color,
) {
    companion object {
        val Light =
            FermixColors(
                canvas = Color(0xFFFFFFFF),
                agentBubble = Color(0xFFF3F4F6),
                hairline = Color(0xFFE7E8EC),
                ink = Color(LIGHT_INK),
                onInk = Color(0xFFFFFFFF),
                textSecondary = Color(0xFF5B5E66),
                inkTertiary = Color(0xFF8B8E98),
                selection = Color(LIGHT_INK).copy(alpha = SELECTION_ALPHA),
                ok = Color(OK),
                warn = Color(WARN),
                err = Color(ERR),
                errText = Color(ERR),
                signal = Color(SIGNAL),
                onSignal = Color(ON_SIGNAL),
                tonal = Color(0xFFECEDF1).copy(alpha = 0.94f),
                tonalSolid = Color(0xFFECEDF1),
                scrim = Color(0xFF0B0B0D).copy(alpha = 0.40f),
                codeCard = Color(CODE_CARD),
            )

        val Dark =
            FermixColors(
                canvas = Color(0xFF0B0B0D),
                agentBubble = Color(0xFF1A1B1F),
                hairline = Color(0xFF26272C),
                ink = Color(DARK_INK),
                onInk = Color(0xFF0B0B0D),
                textSecondary = Color(0xFFA3A6AE),
                inkTertiary = Color(0xFF72757E),
                selection = Color(DARK_INK).copy(alpha = SELECTION_ALPHA),
                ok = Color(OK),
                warn = Color(WARN),
                err = Color(ERR),
                errText = Color(0xFFFF6B5E),
                signal = Color(SIGNAL),
                onSignal = Color(ON_SIGNAL),
                tonal = Color(0xFF202126).copy(alpha = 0.94f),
                tonalSolid = Color(0xFF202126),
                scrim = Color(0xFF000000).copy(alpha = 0.56f),
                codeCard = Color(CODE_CARD),
            )
    }
}

// The values both modes share, or that two tokens of a mode share, ARGB.
private const val LIGHT_INK = 0xFF0B0B0D
private const val DARK_INK = 0xFFDEDFE3
private const val OK = 0xFF1F9D55
private const val WARN = 0xFFC27C0E
private const val ERR = 0xFFD93025
private const val SIGNAL = 0xFF2B5CFF
private const val ON_SIGNAL = 0xFFFFFFFF
private const val CODE_CARD = 0xFF16171B

/** The selection's share of the ink (the update's 1.2). */
private const val SELECTION_ALPHA = 0.20f

/**
 * The six per-instance tones of design section 13.1, muted to about 30 % chroma, with the canon's
 * values. A tint appears in three places only: the avatar, the Custom Tab toolbar and the 2 dp line
 * under the app bar; it never stands in for the ink or the signal, and it is the same in both modes.
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
 * Material 3's roles, every one a token of the mode (the M51 update's section 1.4), so that no Material component
 * draws a baseline colour, and none the signal. Primary is the ink, which Material's filled Button, Switch and
 * progress indicators draw with onPrimary, onInk, on it, and in which it draws a TextButton's label, a dialog's
 * actions, a focused text field's border, label and caret, a selected tab and a radio button: every one reads on
 * the canvas and the tonal surfaces (ContrastTest). Secondary and tertiary are the ink too, and every container a
 * neutral grey: Material fills a selected FilterChip with secondaryContainer, the agent bubble's grey, never the ink
 * with the ink on it. Error is the error text, lifted in dark mode, with onInk on it. Content lies flat, so the
 * surface tint, which colours tonal elevation, is the canvas itself.
 *
 * The containers are mapped by what material3 1.4.0 draws on them, not by Material's rise: the app bar
 * on scroll and menus (surfaceContainer), a docked sheet (surfaceContainerLow) and a dialog
 * (surfaceContainerHigh) are the tonal surface, opaque; a filled card and a filled text field
 * (surfaceContainerHighest) are flat content, the agent bubble's grey. The app bar's 94 % is
 * [FermixColors.tonal], which its screen passes as the scrolled container colour. A filled card takes
 * its content colour from contentColorFor, which answers with the "on" colour of the first role holding
 * the card's grey, primaryContainer, so onPrimaryContainer is the ink. Material fills a FAB with
 * primaryContainer, and the design has no FAB (section 13.1, "Not Telegram"). The inverse surface, a snackbar's,
 * is the ink, with onInk on it and as its inverse primary.
 *
 * Material draws a sheet's scrim at 32 % of the scrim role, whatever the role's own alpha, not the
 * canon's 40 % and 56 %, so a sheet passes `scrimColor = colors.scrim`; the role is the scrim's colour,
 * opaque. No Material 1.4 component reads the fixed roles; they are set so that none is a baseline colour.
 */
internal fun materialColorScheme(
    colors: FermixColors,
    darkTheme: Boolean,
): ColorScheme =
    ColorScheme(
        primary = colors.ink,
        onPrimary = colors.onInk,
        primaryContainer = colors.agentBubble,
        onPrimaryContainer = colors.ink,
        inversePrimary = colors.onInk,
        secondary = colors.ink,
        onSecondary = colors.onInk,
        secondaryContainer = colors.agentBubble,
        onSecondaryContainer = colors.ink,
        tertiary = colors.ink,
        onTertiary = colors.onInk,
        tertiaryContainer = colors.agentBubble,
        onTertiaryContainer = colors.ink,
        background = colors.canvas,
        onBackground = colors.ink,
        surface = colors.canvas,
        onSurface = colors.ink,
        surfaceVariant = colors.agentBubble,
        onSurfaceVariant = colors.textSecondary,
        surfaceTint = colors.canvas,
        inverseSurface = colors.ink,
        inverseOnSurface = colors.onInk,
        error = colors.errText,
        onError = colors.onInk,
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
        primaryFixed = colors.ink,
        primaryFixedDim = colors.ink,
        onPrimaryFixed = colors.onInk,
        onPrimaryFixedVariant = colors.onInk,
        secondaryFixed = colors.agentBubble,
        secondaryFixedDim = colors.tonalSolid,
        onSecondaryFixed = colors.ink,
        onSecondaryFixedVariant = colors.textSecondary,
        tertiaryFixed = colors.agentBubble,
        tertiaryFixedDim = colors.tonalSolid,
        onTertiaryFixed = colors.ink,
        onTertiaryFixedVariant = colors.textSecondary,
    )

/**
 * A text button's colours, a dialog's actions among them (the canon's `.btn.x`): the ink on no container, the
 * secondary action beside the primary's ink pill (the update's 1.3), as Material draws a TextButton in the scheme's
 * primary; named here so a screen's text buttons say so. Disabled, they are Material's: no container, and the ink
 * at 38 %.
 */
fun textButtonColors(colors: FermixColors): ButtonColors =
    ButtonColors(
        containerColor = Color.Transparent,
        contentColor = colors.ink,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = colors.ink.copy(alpha = DISABLED_CONTENT_ALPHA),
    )

/** Material's opacity for disabled content. */
private const val DISABLED_CONTENT_ALPHA = 0.38f
