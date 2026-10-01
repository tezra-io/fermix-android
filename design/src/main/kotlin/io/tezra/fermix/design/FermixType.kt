package io.tezra.fermix.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The type scale of design section 13.1, in sp, so that it follows the system font scale, Android 14's
 * non-linear scaling to 200 % included. Google Sans Flex sets the words; Google Sans Code sets anything
 * machine-owned (code, ids, fingerprints, the SAS) with tabular figures (section 13.10, item 9). Both
 * are bundled under the OFL (section 12.1); res/font holds them and assets/fonts their provenance.
 */
object FermixType {
    /** Welcome. */
    val display = sans(size = 36.sp, lineHeight = 44.sp, weight = FontWeight.Medium)

    /** Screen titles. */
    val headline = sans(size = 24.sp, lineHeight = 32.sp, weight = FontWeight.Medium)

    /** Row and bar titles. */
    val title = sans(size = 16.sp, lineHeight = 24.sp, weight = FontWeight.SemiBold)

    /** Message text. */
    val body = sans(size = 16.sp, lineHeight = 24.sp, weight = FontWeight.Normal)

    /** Supporting text; the thinking card's headings at 70 %. */
    val bodyMedium = sans(size = 14.sp, lineHeight = 20.sp, weight = FontWeight.Normal)

    /** Buttons and chips. */
    val label = sans(size = 14.sp, lineHeight = 20.sp, weight = FontWeight.Medium)

    /**
     * Timestamps and captions, in Google Sans Flex as the visual canon sets them, with tabular figures
     * so that a clock's digits do not shift as it ticks.
     */
    val labelSmall =
        sans(
            size = 11.sp,
            lineHeight = 16.sp,
            weight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
            fontFeatureSettings = TABULAR_FIGURES,
        )

    /** Code, ids, fingerprints, and figures in tables. */
    val mono = code(size = 13.5.sp, lineHeight = 20.sp)

    /**
     * The six-digit verification code, grouped `481 062` by its screen. Section 13.1 gives no tracking;
     * the canon draws it 1 px apart.
     */
    val sas = code(size = 44.sp, lineHeight = 52.sp, letterSpacing = 1.sp)

    /**
     * Material's fifteen slots, each a style of the scale, so that no Material component sets a size of
     * its own: a slot the scale has no size for takes the nearest style of the same weight.
     */
    val material =
        Typography(
            displayLarge = display,
            displayMedium = display,
            displaySmall = display,
            headlineLarge = headline,
            headlineMedium = headline,
            headlineSmall = headline,
            titleLarge = title,
            titleMedium = title,
            titleSmall = title,
            bodyLarge = body,
            bodyMedium = bodyMedium,
            bodySmall = bodyMedium,
            labelLarge = label,
            labelMedium = labelSmall,
            labelSmall = labelSmall,
        )
}

/** Material's own line-height placement, so that the scale's line heights sit as Material's do. */
private val LINE_HEIGHT_STYLE = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** The weights the design sets type in (the canon loads 400, 500 and 600). */
private val WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)

/**
 * Google Sans Code: its upright and italic files, at each weight. The font is monospaced, so its figures
 * are tabular by design and it has no `tnum` feature; the styles ask for `tnum` all the same, so the
 * figures stay tabular wherever a fallback font draws them.
 */
private val GoogleSansCode =
    FontFamily(
        WEIGHTS.flatMap { weight ->
            listOf(
                Font(R.font.google_sans_code, weight, FontStyle.Normal, variationSettings = settings(weight)),
                Font(R.font.google_sans_code_italic, weight, FontStyle.Italic, variationSettings = settings(weight)),
            )
        },
    )

/**
 * Google Sans Flex at one optical size, the style's own: small type gets the open spacing of a low
 * `opsz` and display type the tighter one of a high. Compose turns the sp into the axis value as the sp
 * times the font scale, a linear figure, so at font scale 1.0 it is the size the text is drawn at, as a
 * browser sets it; above 1.0, Android 14 and later draw large text at less than that figure (display
 * asks for 72 at font scale 2.0 and is drawn at about 43), and large text gets a slightly tighter
 * optical size than its drawn size. Each weight is an instance of the one variable font; its width and
 * other axes keep their defaults. Flex has no italic file, so its italic is the font's own slant axis
 * at its end, never Android's synthetic skew.
 */
private fun googleSansFlex(opticalSize: TextUnit): FontFamily =
    FontFamily(
        WEIGHTS.flatMap { weight ->
            val opsz = FontVariation.opticalSizing(opticalSize)
            listOf(
                Font(R.font.google_sans_flex, weight, FontStyle.Normal, variationSettings = settings(weight, opsz)),
                Font(
                    R.font.google_sans_flex,
                    weight,
                    FontStyle.Italic,
                    variationSettings = settings(weight, opsz, FontVariation.slant(FLEX_ITALIC_SLANT)),
                ),
            )
        },
    )

/** The far end of Google Sans Flex's `slnt` axis, which runs from -10 to 0 degrees. */
private const val FLEX_ITALIC_SLANT = -10f

/** OpenType's tabular figures: every digit as wide as every other. */
private const val TABULAR_FIGURES = "tnum"

private fun settings(
    weight: FontWeight,
    vararg axes: FontVariation.Setting,
): FontVariation.Settings = FontVariation.Settings(FontVariation.weight(weight.weight), *axes)

private fun sans(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    fontFeatureSettings: String? = null,
): TextStyle =
    TextStyle(
        fontFamily = googleSansFlex(opticalSize = size),
        fontWeight = weight,
        fontSize = size,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
        fontFeatureSettings = fontFeatureSettings,
        lineHeightStyle = LINE_HEIGHT_STYLE,
    )

private fun code(
    size: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit = TextUnit.Unspecified,
): TextStyle =
    TextStyle(
        fontFamily = GoogleSansCode,
        fontWeight = FontWeight.Normal,
        fontSize = size,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing,
        fontFeatureSettings = TABULAR_FIGURES,
        lineHeightStyle = LINE_HEIGHT_STYLE,
    )
