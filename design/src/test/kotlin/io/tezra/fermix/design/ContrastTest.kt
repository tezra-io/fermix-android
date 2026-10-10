package io.tezra.fermix.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

// The M51 update's section 1.2, "Contrast, measured (WCAG ratio)", as a test, each pair at the update's number to
// one decimal; the pairs of the tokens named here that the app draws and the update does not measure, each held to
// its WCAG 2 floor (success criteria 1.4.3 and 1.4.11): 4.5 : 1 for text, 3 : 1 for a mark that is not text; and
// those under their floor that README's departures name, each pinned at what it measures. A translucent token is
// measured as it lands, over what it is drawn on. The colours of a fixed surface that a module names itself, the
// code card's syntax and Scan's camera, are its module's and not measured here. The focus ring's pairs are measured in
// the colour the ring itself draws (ringColor).
class ContrastTest {
    @Test
    fun `black on white is 21 to 1 either way round, and a colour on itself 1 to 1`() {
        assertEquals(21.0, contrast(Color.Black, Color.White), PRECISION)
        assertEquals(21.0, contrast(Color.White, Color.Black), PRECISION)
        assertEquals(1.0, contrast(SIGNAL_BLUE, SIGNAL_BLUE), PRECISION)
    }

    @Test
    fun `light mode measures as the update's table`() {
        val colors = FermixColors.Light
        assertMeasured(19.7, colors.ink, colors.canvas)
        assertMeasured(17.9, colors.ink, colors.agentBubble)
        assertMeasured(19.7, colors.onInk, colors.ink)
        assertMeasured(6.5, colors.textSecondary, colors.canvas)
        assertMeasured(5.9, colors.textSecondary, colors.agentBubble)
        assertMeasured(4.8, colors.errText, colors.canvas)
    }

    @Test
    fun `dark mode measures as the update's table`() {
        val colors = FermixColors.Dark
        assertMeasured(14.8, colors.ink, colors.canvas)
        assertMeasured(12.9, colors.ink, colors.agentBubble)
        assertMeasured(14.8, colors.onInk, colors.ink)
        // The update prints 7.1 : 1 here, its agent bubble's figure; #A3A6AE on #0B0B0D measures 8.1 : 1. The colour is
        // the update's, and the difference is reported to the owner.
        assertMeasured(8.1, colors.textSecondary, colors.canvas)
        assertMeasured(7.1, colors.textSecondary, colors.agentBubble)
        assertMeasured(7.0, colors.errText, colors.canvas)
        // "#D93025 would be 4.1 : 1, below AA": why dark mode's error text is a colour of its own.
        assertMeasured(4.1, colors.err, colors.canvas)
    }

    @Test
    fun `the unread count reads on the signal, and the signal on the canvas, in both modes`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            assertAtLeast(TEXT, colors.onSignal, colors.signal)
            assertAtLeast(MARK, colors.signal, colors.canvas)
        }
    }

    // The Fermix wordmark's two eye-dots are the signal (the owner, 2026-10-10), drawn at a mark's size on the canvas,
    // Welcome's and the Chats list's bar's, never as text: dark mode's would be under text's 4.5 : 1 (README).
    @Test
    fun `the wordmark's dots measure 5,2 to 1 on the light canvas and 3,8 to 1 on the dark, a mark's 3 to 1 or more`() {
        assertMeasured(5.2, FermixColors.Light.signal, FermixColors.Light.canvas)
        assertMeasured(3.8, FermixColors.Dark.signal, FermixColors.Dark.canvas)
    }

    @Test
    fun `the ink and the secondary text read on every surface they are drawn on`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            for (surface in surfacesOf(colors)) {
                assertAtLeast(TEXT, colors.ink, surface)
                assertAtLeast(TEXT, colors.textSecondary, surface)
            }
            // Inline code and the chosen model chip lie on the hairline, and so does an open fence's "code…" chip.
            assertAtLeast(TEXT, colors.ink, colors.hairline)
            assertAtLeast(TEXT, colors.textSecondary, colors.hairline)
        }
    }

    @Test
    fun `dark mode's error text reads on every surface it is drawn on`() {
        val colors = FermixColors.Dark
        for (surface in surfacesOf(colors)) assertAtLeast(TEXT, colors.errText, surface)
    }

    // Words search marks, and a selected message's row, are washed: in the selection on the canvas and the agent's
    // bubble, and in the owner's bubble, the ink, in onInk at the selection's own alpha, as the selection would not
    // show on the ink it is made of.
    @Test
    fun `marked words read on their wash, on the canvas, the agent's bubble and the owner's`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            assertAtLeast(TEXT, colors.ink, colors.selection.compositeOver(colors.canvas))
            assertAtLeast(TEXT, colors.ink, colors.selection.compositeOver(colors.agentBubble))
            val onTheInk = colors.onInk.copy(alpha = colors.selection.alpha).compositeOver(colors.ink)
            assertAtLeast(TEXT, colors.onInk, onTheInk)
            assertTrue(
                contrast(onTheInk, colors.ink) > contrast(colors.selection.compositeOver(colors.ink), colors.ink),
            )
        }
    }

    // A message's time and mark, at 60 %, in its bubble's ink: the owner's, onInk on the ink; the agent's, the ink
    // on its bubble; a card's or a media message's, the ink on the canvas under it.
    @Test
    fun `a message's faded time reads where it is drawn, in both modes`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            assertAtLeast(TEXT, faded(colors.onInk, colors.ink), colors.ink)
            assertAtLeast(TEXT, faded(colors.ink, colors.agentBubble), colors.agentBubble)
            assertAtLeast(TEXT, faded(colors.ink, colors.canvas), colors.canvas)
        }
    }

    // The status colours as marks: the connection dot, which wears a ring of the canvas, and a failure screen's edge
    // on the canvas; an error card's rule on the agent's bubble; the recording dot on the composer's tonal dock.
    @Test
    fun `the status colours read as marks on the surfaces they are drawn on, in both modes`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            for (status in listOf(colors.ok, colors.warn, colors.err)) {
                assertAtLeast(MARK, status, colors.canvas)
                assertAtLeast(MARK, status, colors.agentBubble)
            }
            assertAtLeast(MARK, colors.err, colors.tonal.compositeOver(colors.canvas))
        }
    }

    // The focus ring (the update's 1.3, and section 8: "every button, link and focus ring meets the contrast in
    // 1.2"), a mark, in the colour the ring draws on each ground (ringColor): the ink on every surface a control lies
    // on and on a selected row's wash, onInk on the ink, and dark mode's ink on what is dark in both modes, the code
    // card and, darker than any surface named here, the camera's and the viewer's black.
    @Test
    fun `the focus ring reads as a mark on every surface it lies on, in both modes`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            val ring = ringColor(colors, RingOn.Surface)
            for (surface in surfacesOf(colors)) assertAtLeast(MARK, ring, surface)
            assertAtLeast(MARK, ring, colors.selection.compositeOver(colors.canvas))
            assertAtLeast(MARK, ringColor(colors, RingOn.Ink), colors.ink)
            assertAtLeast(MARK, ringColor(colors, RingOn.Dark), colors.codeCard)
            assertAtLeast(MARK, ringColor(colors, RingOn.Dark), FermixColors.Dark.canvas)
        }
        // On the canvas, the agent's bubble and the ink, the update's own table: the ink and onInk are its first pairs.
        val light = FermixColors.Light
        val dark = FermixColors.Dark
        assertMeasured(19.7, ringColor(light, RingOn.Surface), light.canvas)
        assertMeasured(17.9, ringColor(light, RingOn.Surface), light.agentBubble)
        assertMeasured(19.7, ringColor(light, RingOn.Ink), light.ink)
        assertMeasured(14.8, ringColor(dark, RingOn.Surface), dark.canvas)
        assertMeasured(12.9, ringColor(dark, RingOn.Surface), dark.agentBubble)
        assertMeasured(14.8, ringColor(dark, RingOn.Ink), dark.ink)
    }

    // Dark mode's ink rings a control on a surface dark in both modes, and lies there alone, in light mode too: an
    // icon button there is ringed inside its target (iconFocusRing), so Pair's copy button's ring stays on the
    // command card, which is as tall as the button (PairRingTest), and never on light mode's canvas, where it
    // measures 1.3 : 1.
    @Test
    fun `dark mode's ink is a ring on the dark surfaces alone, and would not read on light mode's canvas`() {
        assertMeasured(1.3, ringColor(FermixColors.Light, RingOn.Dark), FermixColors.Light.canvas)
    }

    // A picture can be any colour, so an image cell's ring is two lines, the ink and onInk inside it (RingOn.Picture):
    // the two ratios a colour makes with them multiply to theirs, so the larger is at least its square root, and over
    // every luminance (every grey) one of the two lines reads as a mark.
    @Test
    fun `on any picture one of the two lines of a picture's ring reads as a mark, in both modes`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            val outer = ringColor(colors, RingOn.Picture)
            val inner = checkNotNull(ringLining(colors, RingOn.Picture))
            assertTrue(sqrt(contrast(outer, inner)) >= MARK, "the lines' own ratio's root is under $MARK")
            for (level in 0..GREY_LEVELS) {
                val grey = Color(level, level, level)
                val best = maxOf(contrast(outer, grey), contrast(inner, grey))
                assertTrue(best >= MARK, "on $grey the picture's ring is $best : 1, under $MARK : 1")
            }
        }
    }

    // A link among a message's words takes no modifier, so no ring: focused, it is drawn in onInk on the ink
    // (ChatMarkdown's focused link style), text on the ink as the owner's bubble is, a block of the ink on the agent
    // bubble and on a card it lies on as a mark.
    @Test
    fun `a focused link, onInk on the ink, reads as text and as a mark on what it lies on, in both modes`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            assertAtLeast(TEXT, colors.onInk, colors.ink)
            for (surface in surfacesOf(colors)) assertAtLeast(MARK, colors.ink, surface)
        }
    }

    // A selected message's row is washed in the selection, and what the row draws on its canvas, outside its
    // bubbles, lies on that wash: a state line under the owner's bubble, a job's tag over a card, a card's time, an
    // upload's line. The error text and the stamp's faded ink read there in neither mode, nor light mode's secondary
    // text, so a selected row draws them in the ink; each is pinned at what README's departures report.
    @Test
    fun `a selected row's lines are the ink, as the error text and the faded time do not read on its wash`() {
        val light = FermixColors.Light
        val lightWash = light.selection.compositeOver(light.canvas)
        assertAtLeast(TEXT, light.ink, lightWash)
        assertMeasured(3.0, light.errText, lightWash)
        assertMeasured(4.4, faded(light.ink, lightWash), lightWash)
        assertMeasured(4.1, light.textSecondary, lightWash)
        val dark = FermixColors.Dark
        val darkWash = dark.selection.compositeOver(dark.canvas)
        assertAtLeast(TEXT, dark.ink, darkWash)
        assertMeasured(4.4, dark.errText, darkWash)
        assertMeasured(4.4, faded(dark.ink, darkWash), darkWash)
    }

    // The pairs the update's own colours, and the M51 values it leaves standing, draw under their floor: each is
    // named in README's departures for the owner and pinned here at what it measures, so a change to either colour
    // of a pair is seen.
    @Test
    fun `the pairs under their floor measure as README reports them`() {
        val light = FermixColors.Light
        val dark = FermixColors.Dark
        // Light mode's error text on the agent's bubble (a Denied receipt, a held Chats row's revoked line) and on the
        // tonal surface (a sheet's or a dialog's refusal, the palette's /stop).
        assertMeasured(4.3, light.errText, light.agentBubble)
        assertMeasured(4.1, light.errText, light.tonalSolid)
        // Light mode's ok and warn as text on an approval's grey (the Approved receipt, the countdown's line), and the
        // countdown's bar, a mark, in warn on its hairline track.
        assertMeasured(3.2, light.ok, light.agentBubble)
        assertMeasured(3.1, light.warn, light.agentBubble)
        assertMeasured(2.8, light.warn, light.hairline)
        // The tertiary ink as text: the Model sheet's unlisted provider and unavailable group, on the sheet's tone.
        assertMeasured(2.8, light.inkTertiary, light.tonalSolid)
        assertMeasured(3.5, dark.inkTertiary, dark.tonalSolid)
        // A queued owner's bubble, drawn whole at M51's 55 %: its words on its fill, over light mode's canvas.
        val queued = { color: Color -> color.copy(alpha = QUEUED_ALPHA).compositeOver(light.canvas) }
        assertMeasured(4.3, queued(light.onInk), queued(light.ink))
        // Sand, the one tint under a mark's 3 : 1: its avatar, a plain disc, on light mode's canvas.
        assertMeasured(2.7, Tint.Sand.color, light.canvas)
        // Connecting's step dots: the current one, the ink, against the done ones, the secondary text; those to come,
        // the hairline, on the canvas.
        assertMeasured(3.0, light.ink, light.textSecondary)
        assertMeasured(1.8, dark.ink, dark.textSecondary)
        assertMeasured(1.2, light.hairline, light.canvas)
        assertMeasured(1.3, dark.hairline, dark.canvas)
        // The code card and Pair's command card beside the ink pill and the owner's bubble in light mode.
        assertMeasured(1.1, light.codeCard, light.ink)
    }
}

/** [ink] at a message's time's opacity, as it lands on [surface]. */
private fun faded(
    ink: Color,
    surface: Color,
): Color = ink.copy(alpha = FermixSpacing.TIMESTAMP_ALPHA).compositeOver(surface)

/** A queued bubble's opacity, M51 section 13.6's "55%-opacity bubble" (feature-chat's QUEUED_ALPHA). */
private const val QUEUED_ALPHA = 0.55f

/** How close two ratios must lie to be the same number. */
private const val PRECISION = 1e-9

/** WCAG 2's floors: text, and a mark that is not text (a badge's fill, a divider, an icon). */
private const val TEXT = 4.5
private const val MARK = 3.0

/** The last of an 8-bit channel's levels, a grey for each. */
private const val GREY_LEVELS = 255

private val SIGNAL_BLUE = Color(0xFF2B5CFF)

/** The surfaces text lies on: the canvas, the agent's bubble, a sheet's or a dialog's tone, and a bar's at 94 %. */
private fun surfacesOf(colors: FermixColors): List<Color> =
    listOf(colors.canvas, colors.agentBubble, colors.tonalSolid, colors.tonal.compositeOver(colors.canvas))

/** [ink] on [surface] measures [expected] to one decimal, as the update prints its ratios. */
private fun assertMeasured(
    expected: Double,
    ink: Color,
    surface: Color,
) {
    val ratio = contrast(ink, surface)
    assertEquals(expected, round(ratio * DECIMAL) / DECIMAL, "$ink on $surface measures $ratio")
}

private const val DECIMAL = 10.0

private fun assertAtLeast(
    floor: Double,
    ink: Color,
    surface: Color,
) {
    val ratio = contrast(ink, surface)
    assertTrue(ratio >= floor, "$ink on $surface is $ratio : 1, under $floor : 1")
}

/** WCAG 2's contrast ratio of [a] and [b], both opaque: the lighter's relative luminance + 0.05 over the darker's. */
private fun contrast(
    a: Color,
    b: Color,
): Double {
    require(a.alpha == 1f && b.alpha == 1f) { "contrast is measured between opaque colours: $a, $b" }
    val lighter = maxOf(luminance(a), luminance(b))
    val darker = minOf(luminance(a), luminance(b))
    return (lighter + LUMINANCE_FLARE) / (darker + LUMINANCE_FLARE)
}

private const val LUMINANCE_FLARE = 0.05

/** WCAG 2's relative luminance of an sRGB colour: its channels made linear, weighted as Rec. 709 weighs them. */
private fun luminance(color: Color): Double =
    RED_WEIGHT * linear(color.red) + GREEN_WEIGHT * linear(color.green) + BLUE_WEIGHT * linear(color.blue)

private const val RED_WEIGHT = 0.2126
private const val GREEN_WEIGHT = 0.7152
private const val BLUE_WEIGHT = 0.0722

/**
 * An sRGB channel made linear. The knee is sRGB's 0.04045, as WCAG 2.2's note gives it; WCAG 2.0's 0.03928 decides
 * no 8-bit channel differently, as none lies between the two (10/255 is 0.0392, 11/255 is 0.0431).
 */
private fun linear(channel: Float): Double =
    if (channel <= SRGB_KNEE) channel / SRGB_SLOPE else ((channel + SRGB_OFFSET) / (1 + SRGB_OFFSET)).pow(SRGB_GAMMA)

private const val SRGB_KNEE = 0.04045
private const val SRGB_SLOPE = 12.92
private const val SRGB_OFFSET = 0.055
private const val SRGB_GAMMA = 2.4
