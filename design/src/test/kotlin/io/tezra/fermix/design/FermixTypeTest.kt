package io.tezra.fermix.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// The type scale of design section 13.1: size / line height in sp, weight, and tracking where the
// scale gives one (label-small +0.4; the SAS's 1 px is the visual canon's).
class FermixTypeTest {
    private val flex = setOf(R.font.google_sans_flex)
    private val code = setOf(R.font.google_sans_code, R.font.google_sans_code_italic)

    private val sansStyles =
        listOf(
            FermixType.display,
            FermixType.headline,
            FermixType.title,
            FermixType.body,
            FermixType.bodyMedium,
            FermixType.label,
            FermixType.labelSmall,
        )

    @Test
    fun `the sans styles are the design's scale in Google Sans Flex`() {
        assertStyle(FermixType.display, 36.sp, 44.sp, 500, flex)
        assertStyle(FermixType.headline, 24.sp, 32.sp, 500, flex)
        assertStyle(FermixType.title, 16.sp, 24.sp, 600, flex)
        assertStyle(FermixType.body, 16.sp, 24.sp, 400, flex)
        assertStyle(FermixType.bodyMedium, 14.sp, 20.sp, 400, flex)
        assertStyle(FermixType.label, 14.sp, 20.sp, 500, flex)
        assertStyle(FermixType.labelSmall, 11.sp, 16.sp, 500, flex)
    }

    @Test
    fun `the machine-owned styles are Google Sans Code with tabular figures`() {
        assertStyle(FermixType.mono, 13.5.sp, 20.sp, 400, code)
        assertStyle(FermixType.sas, 44.sp, 52.sp, 400, code)
        assertEquals("tnum", FermixType.mono.fontFeatureSettings)
        assertEquals("tnum", FermixType.sas.fontFeatureSettings)
    }

    @Test
    fun `only label-small and the SAS are tracked`() {
        val tracking =
            listOf(
                Triple("display", FermixType.display, TextUnit.Unspecified),
                Triple("headline", FermixType.headline, TextUnit.Unspecified),
                Triple("title", FermixType.title, TextUnit.Unspecified),
                Triple("body", FermixType.body, TextUnit.Unspecified),
                Triple("bodyMedium", FermixType.bodyMedium, TextUnit.Unspecified),
                Triple("label", FermixType.label, TextUnit.Unspecified),
                Triple("labelSmall", FermixType.labelSmall, 0.4.sp),
                Triple("mono", FermixType.mono, TextUnit.Unspecified),
                Triple("sas", FermixType.sas, 1.sp),
            )
        for ((name, style, letterSpacing) in tracking) {
            assertEquals(letterSpacing, style.letterSpacing, name)
        }
    }

    @Test
    fun `timestamps and captions keep their figures tabular`() {
        assertEquals("tnum", FermixType.labelSmall.fontFeatureSettings)
    }

    @Test
    fun `each sans style sets Flex's optical size to its own size`() {
        for (style in sansStyles) {
            assertEquals(setOf(style.fontSize.value), axisValues(style, "opsz", fontScale = 1f), "$style")
        }
    }

    // Compose turns an sp axis value into a figure linearly, while Android 14 and later draw large text
    // at less than twice its size at font scale 2.0: the optical size follows the sp, not the drawn size.
    @Test
    fun `at font scale 2, Flex's optical size is twice the style's sp, the linear figure`() {
        for (style in sansStyles) {
            assertEquals(setOf(style.fontSize.value * 2f), axisValues(style, "opsz", fontScale = 2f), "$style")
        }
    }

    @Test
    fun `Flex's italic is its own slant axis at its end, never a synthetic skew`() {
        for (style in sansStyles) {
            val italic = resourceFonts(style).filter { it.style == FontStyle.Italic }
            assertEquals(resourceFonts(style).count { it.style == FontStyle.Normal }, italic.size, "$style")
            assertEquals(setOf(-10f), italic.flatMap { slants(it) }.toSet(), "$style")
            val upright = resourceFonts(style).filter { it.style == FontStyle.Normal }
            assertEquals(emptyList<Float>(), upright.flatMap { slants(it) }, "$style")
        }
    }

    @Test
    fun `every Material slot takes a style of the scale`() {
        val material = FermixType.material
        assertEquals(FermixType.display, material.displayLarge)
        assertEquals(FermixType.display, material.displayMedium)
        assertEquals(FermixType.display, material.displaySmall)
        assertEquals(FermixType.headline, material.headlineLarge)
        assertEquals(FermixType.headline, material.headlineMedium)
        assertEquals(FermixType.headline, material.headlineSmall)
        assertEquals(FermixType.title, material.titleLarge)
        assertEquals(FermixType.title, material.titleMedium)
        assertEquals(FermixType.title, material.titleSmall)
        assertEquals(FermixType.body, material.bodyLarge)
        assertEquals(FermixType.bodyMedium, material.bodyMedium)
        assertEquals(FermixType.bodyMedium, material.bodySmall)
        assertEquals(FermixType.label, material.labelLarge)
        assertEquals(FermixType.labelSmall, material.labelMedium)
        assertEquals(FermixType.labelSmall, material.labelSmall)
    }

    private fun assertStyle(
        style: TextStyle,
        size: TextUnit,
        lineHeight: TextUnit,
        weight: Int,
        fonts: Set<Int>,
    ) {
        assertEquals(size, style.fontSize)
        assertEquals(lineHeight, style.lineHeight)
        assertEquals(FontWeight(weight), style.fontWeight)
        assertEquals(fonts, resourceFonts(style).map { it.resId }.toSet())
    }

    private fun resourceFonts(style: TextStyle): List<ResourceFont> =
        (style.fontFamily as FontListFontFamily).fonts.map { it as ResourceFont }

    private fun axisValues(
        style: TextStyle,
        axis: String,
        fontScale: Float,
    ): Set<Float> = resourceFonts(style).flatMap { axisValues(it, axis, fontScale) }.toSet()

    private fun slants(font: ResourceFont): List<Float> = axisValues(font, "slnt", fontScale = 1f)

    private fun axisValues(
        font: ResourceFont,
        axis: String,
        fontScale: Float,
    ): List<Float> =
        font.variationSettings.settings
            .filter { it.axisName == axis }
            .map { it.toVariationValue(Density(density = 1f, fontScale = fontScale)) }
}
