package io.tezra.fermix.design

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val FRAME = "frame"
private const val CONTROL = "control"

/** Room around a control in its frame, wide enough for its ring and the canvas past it. */
private val MARGIN = 16.dp
private val PILL_WIDTH = 120.dp
private val PILL_HEIGHT = 40.dp

/** xhdpi: two pixels a dp. */
private const val PX_PER_DP = 2

/**
 * The focus ring of the M51 update's 1.3 ("Focus | 2 dp ink ring, 2 dp offset"), drawn by [focusRing] on Robolectric's
 * native graphics, read off the frame's pixels: a 2 dp stroke 2 dp outside the control's bounds while the control is
 * focused and the window shows focus (it has the focus, and a keyboard's or a d-pad's, never a touch's), on that
 * control alone, in the ink on the canvas and in onInk on an ink fill, the ink lined with onInk on a picture; a row's
 * inside its own bounds, an icon button's round its disc inside its target, a wide control's round what it marks as
 * its content, either way round; drawn over a sibling after it and, held raised, over its holder's; brought into view
 * with the control, an icon button's whole target too; and the control measured as it is without one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class FocusRingTest {
    @get:Rule
    val rule = createAndroidComposeRule<RoborazziActivity>()

    private val light = FermixColors.Light

    @Test
    fun `a focused pill under keyboard input is ringed in the ink, 2 dp wide, 2 dp outside its bounds`() {
        val image = focusedPill(InputMode.Keyboard, RingOn.Surface)
        val top = MARGIN
        val middle = MARGIN + PILL_WIDTH / 2
        assertEquals(light.canvas.argb, image.at(middle, top - 1.dp))
        assertEquals(light.ink.argb, image.at(middle, top - 2.5.dp))
        assertEquals(light.ink.argb, image.at(middle, top - 3.5.dp))
        assertEquals(light.canvas.argb, image.at(middle, top - 5.dp))
        // Its other three sides, round the pill's ends.
        val bottom = MARGIN + PILL_HEIGHT
        assertEquals(light.ink.argb, image.at(middle, bottom + 3.dp))
        val centre = MARGIN + PILL_HEIGHT / 2
        assertEquals(light.ink.argb, image.at(MARGIN - 3.dp, centre))
        assertEquals(light.ink.argb, image.at(MARGIN + PILL_WIDTH + 3.dp, centre))
        // The pill itself is as it was: its fill reaches its edge, with no ring drawn over it.
        assertEquals(light.agentBubble.argb, image.at(middle, top + 1.dp))
    }

    @Test
    fun `a focused pill under touch input has no ring`() {
        val image = focusedPill(InputMode.Touch, RingOn.Surface)
        val middle = MARGIN + PILL_WIDTH / 2
        for (out in listOf(1.dp, 2.5.dp, 3.dp, 3.5.dp, 5.dp)) {
            assertEquals("${out.value} dp above the pill", light.canvas.argb, image.at(middle, MARGIN - out))
        }
    }

    @Test
    fun `the window's touch mode, the one Android leaves for a key and enters for a touch, hides the ring`() {
        val focus = FocusRequester()
        var modes: InputModeManager? = null
        rule.setContent {
            FermixTheme(darkTheme = false) {
                modes = LocalInputModeManager.current
                Frame { Pill(focus, RingOn.Surface) }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        assertEquals(InputMode.Touch, modes?.inputMode)
        val middle = MARGIN + PILL_WIDTH / 2
        assertEquals(light.canvas.argb, frame().at(middle, MARGIN - 3.dp))
    }

    @Test
    fun `under keyboard input only the focused control is ringed, and the one beside it has none`() {
        val focus = FocusRequester()
        val other = FocusRequester()
        rule.setContent {
            Keyboard {
                Frame {
                    Column(verticalArrangement = Arrangement.spacedBy(MARGIN)) {
                        Pill(focus, RingOn.Surface)
                        Pill(other, RingOn.Surface)
                    }
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        val image = frame()
        val middle = MARGIN + PILL_WIDTH / 2
        val second = MARGIN + PILL_HEIGHT + MARGIN
        assertEquals(light.ink.argb, image.at(middle, MARGIN - 3.dp))
        for (out in listOf(2.5.dp, 3.dp, 3.5.dp)) {
            val below = second + PILL_HEIGHT + out
            assertEquals("${out.value} dp below the other pill", light.canvas.argb, image.at(middle, below))
            assertEquals("${out.value} dp left of it", light.canvas.argb, image.at(MARGIN - out, second + 20.dp))
        }
    }

    @Test
    fun `while a menu or a dialog over the window holds the focus, the control under it shows no ring`() {
        val focus = FocusRequester()
        rule.setContent {
            Keyboard {
                CompositionLocalProvider(LocalWindowInfo provides UnfocusedWindow) {
                    Frame { Pill(focus, RingOn.Surface) }
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        val middle = MARGIN + PILL_WIDTH / 2
        for (out in listOf(2.5.dp, 3.dp, 3.5.dp)) {
            assertEquals("${out.value} dp above the pill", light.canvas.argb, frame().at(middle, MARGIN - out))
        }
    }

    @Test
    fun `a sibling drawn after a focused control does not cover its ring`() {
        val focus = FocusRequester()
        rule.setContent {
            Keyboard {
                Frame {
                    Row(horizontalArrangement = Arrangement.spacedBy(FOCUS_RING_OFFSET)) {
                        Pill(focus, RingOn.Surface)
                        Box(Modifier.size(PILL_HEIGHT).background(light.codeCard))
                    }
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        // The ring's right side, 2 to 4 dp past the pill, lies on the square drawn after it.
        val right = MARGIN + PILL_WIDTH
        val centre = MARGIN + PILL_HEIGHT / 2
        assertEquals(light.ink.argb, frame().at(right + 3.dp, centre))
        assertEquals(light.codeCard.argb, frame().at(right + 5.dp, centre))
    }

    @Test
    fun `what holds a focused control below its neighbours' level is raised over them while the ring shows`() {
        val focus = FocusRequester()
        rule.setContent {
            Keyboard {
                Frame {
                    Column(verticalArrangement = Arrangement.spacedBy(FOCUS_RING_OFFSET)) {
                        Box(Modifier.raisedWhileFocused()) { Pill(focus, RingOn.Surface) }
                        Box(Modifier.size(PILL_WIDTH, PILL_HEIGHT).background(light.codeCard))
                    }
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        val below = MARGIN + PILL_HEIGHT
        assertEquals(light.ink.argb, frame().at(MARGIN + PILL_WIDTH / 2, below + 3.dp))
        assertEquals(light.codeCard.argb, frame().at(MARGIN + PILL_WIDTH / 2, below + 5.dp))
    }

    @Test
    fun `a scroll that brings a focused control into view brings its ring with it`() {
        val focus = FocusRequester()
        rule.setContent {
            Keyboard {
                Frame {
                    Column(Modifier.testTag(VIEWPORT).height(VIEWPORT_HEIGHT).verticalScroll(rememberScrollState())) {
                        // The pill starts half past the viewport's bottom.
                        Spacer(Modifier.height(VIEWPORT_HEIGHT - PILL_HEIGHT / 2))
                        Pill(focus, RingOn.Surface)
                        Spacer(Modifier.height(VIEWPORT_HEIGHT))
                    }
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        val viewport = rule.onNodeWithTag(VIEWPORT).getBoundsInRoot()
        val pill = rule.onNodeWithTag(CONTROL).getBoundsInRoot()
        val room = FOCUS_RING_OFFSET + FOCUS_RING_WIDTH
        val ends = "the pill ends at ${pill.bottom}, the viewport at ${viewport.bottom}"
        assertTrue(ends, pill.bottom + room <= viewport.bottom)
        assertEquals(light.ink.argb, frame().at(MARGIN + PILL_WIDTH / 2, pill.bottom + 3.dp))
    }

    @Test
    fun `a scroll that brings a focused icon button into view brings its whole target, where its ring lies`() {
        val focus = FocusRequester()
        rule.setContent {
            Keyboard {
                Frame {
                    Column(Modifier.height(VIEWPORT_HEIGHT).verticalScroll(rememberScrollState())) {
                        // The button's target starts half past the viewport's bottom.
                        Spacer(Modifier.height(VIEWPORT_HEIGHT - TARGET / 2))
                        IconButton(onClick = {}, modifier = Modifier.iconFocusRing().focusRequester(focus)) {}
                        Spacer(Modifier.height(VIEWPORT_HEIGHT))
                    }
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        // Material brings its 40 dp disc into view; the ring's stroke lies 2 to 4 dp under it, inside the target.
        assertEquals(light.ink.argb, frame().at(MARGIN + TARGET / 2, MARGIN + VIEWPORT_HEIGHT - 1.dp))
    }

    @Test
    fun `a control ringed round its content keeps its own width, and its ring follows the content`() {
        val focus = FocusRequester()
        rule.setContent {
            Keyboard {
                Frame {
                    Box(
                        Modifier
                            .testTag(CONTROL)
                            .contentFocusRing(FermixShapes.button)
                            .focusRequester(focus)
                            .focusable()
                            .width(PILL_WIDTH * 2)
                            .wrapContentWidth(Alignment.Start)
                            .ringedContent()
                            .size(PILL_WIDTH, PILL_HEIGHT)
                            .background(light.agentBubble, FermixShapes.button),
                    )
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        rule.onNodeWithTag(CONTROL).assertWidthIsEqualTo(PILL_WIDTH * 2)
        val image = frame()
        val centre = MARGIN + PILL_HEIGHT / 2
        assertEquals(light.ink.argb, image.at(MARGIN - 3.dp, centre))
        assertEquals(light.ink.argb, image.at(MARGIN + PILL_WIDTH + 3.dp, centre))
        assertEquals(light.canvas.argb, image.at(MARGIN + PILL_WIDTH * 2 + 3.dp, centre))
    }

    @Test
    fun `right to left, a control ringed round its content rings the content at its start, on the right`() {
        val focus = FocusRequester()
        rule.setContent {
            Keyboard {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Frame {
                        Box(
                            Modifier
                                .contentFocusRing(FermixShapes.button)
                                .focusRequester(focus)
                                .focusable()
                                .width(PILL_WIDTH * 2)
                                .wrapContentWidth(Alignment.Start)
                                .ringedContent()
                                .size(PILL_WIDTH, PILL_HEIGHT),
                        )
                    }
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        val image = frame()
        val centre = MARGIN + PILL_HEIGHT / 2
        assertEquals(light.ink.argb, image.at(MARGIN + PILL_WIDTH - 3.dp, centre))
        assertEquals(light.ink.argb, image.at(MARGIN + PILL_WIDTH * 2 + 3.dp, centre))
        assertEquals(light.canvas.argb, image.at(MARGIN - 3.dp, centre))
    }

    @Test
    fun `an icon button's ring is 2 dp outside its 40 dp disc, inside its 48 dp target`() {
        val focus = FocusRequester()
        rule.setContent {
            Keyboard {
                Frame {
                    IconButton(onClick = {}, modifier = Modifier.iconFocusRing().focusRequester(focus)) {}
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        val image = frame()
        val centre = MARGIN + TARGET / 2
        // From the target's top down: the canvas outside it, the stroke along its edge, the 2 dp gap above the disc.
        assertEquals(light.canvas.argb, image.at(centre, MARGIN - 1.dp))
        assertEquals(light.ink.argb, image.at(centre, MARGIN + 0.5.dp))
        assertEquals(light.ink.argb, image.at(centre, MARGIN + 1.5.dp))
        assertEquals(light.canvas.argb, image.at(centre, MARGIN + 3.dp))
        assertEquals(light.ink.argb, image.at(MARGIN + 1.dp, centre))
        assertEquals(light.ink.argb, image.at(MARGIN + TARGET - 1.dp, centre))
    }

    @Test
    fun `on a picture the ring is the ink lined with onInk inside it`() = assertLinedOnPicture(FermixColors.Light)

    @Test
    fun `on a picture in dark mode the ring is dark mode's ink lined with its onInk`() =
        assertLinedOnPicture(FermixColors.Dark)

    /** A focused picture's row ring in [colors]' mode: from its edge in, the ink, onInk, then the picture. */
    private fun assertLinedOnPicture(colors: FermixColors) {
        val focus = FocusRequester()
        rule.setContent {
            Moded(InputMode.Keyboard, darkTheme = colors == FermixColors.Dark) {
                Frame {
                    Box(
                        Modifier
                            .rowFocusRing(RingOn.Picture)
                            .focusRequester(focus)
                            .focusable()
                            .size(PILL_WIDTH)
                            .background(PICTURE),
                    )
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        val image = frame()
        val middle = MARGIN + PILL_WIDTH / 2
        assertEquals(colors.canvas.argb, image.at(middle, MARGIN - 1.dp))
        assertEquals(colors.ink.argb, image.at(middle, MARGIN + 1.dp))
        assertEquals(colors.onInk.argb, image.at(middle, MARGIN + 3.dp))
        assertEquals(PICTURE.argb, image.at(middle, MARGIN + 5.dp))
    }

    @Test
    fun `on an ink fill the ring is onInk`() {
        val image = focusedPill(InputMode.Keyboard, RingOn.Ink, ground = light.ink)
        val middle = MARGIN + PILL_WIDTH / 2
        assertEquals(light.ink.argb, image.at(middle, MARGIN - 1.dp))
        assertEquals(light.onInk.argb, image.at(middle, MARGIN - 3.dp))
        assertEquals(light.ink.argb, image.at(middle, MARGIN - 5.dp))
    }

    @Test
    fun `on a surface dark in both modes the ring is dark mode's ink, in light mode too`() {
        val image = focusedPill(InputMode.Keyboard, RingOn.Dark, ground = light.codeCard)
        assertEquals(FermixColors.Dark.ink.argb, image.at(MARGIN + PILL_WIDTH / 2, MARGIN - 3.dp))
    }

    @Test
    fun `in dark mode the ring on the canvas is dark mode's ink`() {
        val dark = FermixColors.Dark
        val image = focusedPill(InputMode.Keyboard, RingOn.Surface, darkTheme = true)
        val middle = MARGIN + PILL_WIDTH / 2
        assertEquals(dark.ink.argb, image.at(middle, MARGIN - 3.dp))
        assertEquals(dark.canvas.argb, image.at(middle, MARGIN - 1.dp))
    }

    @Test
    fun `the ring takes no room, so a ringed and focused pill measures and lies as one with no ring`() {
        val focus = FocusRequester()
        rule.setContent {
            Keyboard {
                Column {
                    Frame { Pill(focus, RingOn.Surface) }
                    Box(Modifier.testTag(NEXT).size(10.dp))
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        rule
            .onNodeWithTag(CONTROL)
            .assertWidthIsEqualTo(PILL_WIDTH)
            .assertHeightIsEqualTo(PILL_HEIGHT)
            .assertLeftPositionInRootIsEqualTo(MARGIN)
            .assertTopPositionInRootIsEqualTo(MARGIN)
        rule.onNodeWithTag(NEXT).assertTopPositionInRootIsEqualTo(MARGIN + PILL_HEIGHT + MARGIN)
        assertEquals(light.ink.argb, frame().at(MARGIN + PILL_WIDTH / 2, MARGIN - 3.dp))
    }

    @Test
    fun `a focused row spanning its column is ringed inside its bounds, the ring's outer edge on them`() {
        val focus = FocusRequester()
        rule.setContent {
            Keyboard {
                Frame {
                    Box(
                        Modifier
                            .testTag(CONTROL)
                            .fillMaxWidth()
                            .rowFocusRing()
                            .focusRequester(focus)
                            .focusable()
                            .size(width = PILL_WIDTH, height = ROW_HEIGHT),
                    )
                }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        val image = frame()
        val middle = MARGIN + 40.dp
        assertEquals(light.ink.argb, image.at(middle, MARGIN + 0.5.dp))
        assertEquals(light.ink.argb, image.at(middle, MARGIN + 1.5.dp))
        assertEquals(light.canvas.argb, image.at(middle, MARGIN + 3.dp))
        assertEquals(light.canvas.argb, image.at(middle, MARGIN - 1.dp))
        assertEquals(light.ink.argb, image.at(MARGIN + 1.dp, MARGIN + ROW_HEIGHT / 2))
    }

    @Test
    fun `a preview's ring is drawn with nothing focused, in any input mode`() {
        rule.setContent {
            FermixTheme(darkTheme = false) {
                Frame {
                    Box(
                        Modifier
                            .ring(FermixShapes.button, RingOn.Surface, within = false, shown = true)
                            .size(PILL_WIDTH, PILL_HEIGHT)
                            .background(light.agentBubble, FermixShapes.button),
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals(light.ink.argb, frame().at(MARGIN + PILL_WIDTH / 2, MARGIN - 3.dp))
    }

    @Test
    fun `the ring's colour on each ground is the token the update names`() {
        for (colors in listOf(FermixColors.Light, FermixColors.Dark)) {
            assertEquals(colors.ink, ringColor(colors, RingOn.Surface))
            assertEquals(colors.onInk, ringColor(colors, RingOn.Ink))
            assertEquals(FermixColors.Dark.ink, ringColor(colors, RingOn.Dark))
            assertEquals(colors.ink, ringColor(colors, RingOn.Picture))
            assertEquals(colors.onInk, ringLining(colors, RingOn.Picture))
            for (on in listOf(RingOn.Surface, RingOn.Ink, RingOn.Dark)) assertEquals(null, ringLining(colors, on))
        }
    }

    /** A pill of the agent bubble's grey on [ground], focused while the window is in [mode]. */
    private fun focusedPill(
        mode: InputMode,
        on: RingOn,
        ground: Color? = null,
        darkTheme: Boolean = false,
    ): Bitmap {
        val focus = FocusRequester()
        rule.setContent {
            Moded(mode, darkTheme) {
                Frame(ground) { Pill(focus, on) }
            }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.waitForIdle()
        return frame()
    }

    private fun frame(): Bitmap = rule.onNodeWithTag(FRAME).captureToImage().asAndroidBitmap()
}

private const val NEXT = "next"
private val ROW_HEIGHT = 56.dp
private const val VIEWPORT = "viewport"
private val VIEWPORT_HEIGHT = 100.dp

/** A Material icon button's touch target, its 40 dp disc in the middle. */
private val TARGET = 48.dp

/** A picture's middle grey, which neither the ink nor onInk is. */
private val PICTURE = Color(0xFF7F7F7F)

/** A window that a menu or a dialog over it took the focus from. */
private object UnfocusedWindow : WindowInfo {
    override val isWindowFocused: Boolean = false
}

/** [content] in the theme with the window's input mode fixed at [mode], as a key or a touch would leave it. */
@Composable
private fun Moded(
    mode: InputMode,
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    FermixTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(
            LocalInputModeManager provides FixedInputMode(mode),
            content = content,
        )
    }
}

@Composable
private fun Keyboard(content: @Composable () -> Unit) = Moded(InputMode.Keyboard, darkTheme = false, content)

/** The window's input mode, held at [inputMode]: Android's own leaves keyboard mode only for a touch. */
private class FixedInputMode(
    override val inputMode: InputMode,
) : InputModeManager {
    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == this.inputMode
}

/** A frame of [ground] (the canvas by default) with [MARGIN] around its content. */
@Composable
private fun Frame(
    ground: Color? = null,
    content: @Composable () -> Unit,
) {
    val fill = ground ?: LocalFermixColors.current.canvas
    Box(Modifier.testTag(FRAME).background(fill).padding(MARGIN)) { content() }
}

/** A focusable pill of the agent bubble's grey, ringed for [on]. */
@Composable
private fun Pill(
    focus: FocusRequester,
    on: RingOn,
) {
    Box(
        Modifier
            .testTag(CONTROL)
            .focusRing(FermixShapes.button, on)
            .focusRequester(focus)
            .focusable()
            .size(PILL_WIDTH, PILL_HEIGHT)
            .background(LocalFermixColors.current.agentBubble, FermixShapes.button),
    )
}

private val Color.argb: Int get() = toArgb()

/** The pixel at ([x], [y]) dp of the frame. */
private fun Bitmap.at(
    x: Dp,
    y: Dp,
): Int = getPixel((x.value * PX_PER_DP).toInt(), (y.value * PX_PER_DP).toInt())
