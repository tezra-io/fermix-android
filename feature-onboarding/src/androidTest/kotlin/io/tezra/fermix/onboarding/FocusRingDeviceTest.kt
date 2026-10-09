package io.tezra.fermix.onboarding

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.platform.app.InstrumentationRegistry
import io.tezra.fermix.design.Drop
import io.tezra.fermix.design.FOCUS_RING_OFFSET
import io.tezra.fermix.design.FOCUS_RING_WIDTH
import io.tezra.fermix.design.FermixColors
import io.tezra.fermix.design.FermixTheme
import io.tezra.fermix.design.MarkPose
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** The action that opens a dialog, its words the test's own, and where it and the dialog lie. */
private const val DIALOG_ACTION = "Open a dialog"
private val DIALOG_ACTION_PADDING = 24.dp
private val DIALOG_SIZE = 120.dp

/** How far a pixel's channel may stray from the ink or the canvas and still be it, on the device's own renderer. */
private const val CHANNEL_SLACK = 24

/** An ARGB pixel's colour channels: where each starts, and one channel's bits. */
private const val RED = 16
private const val GREEN = 8
private const val BLUE = 0
private const val CHANNEL = 0xFF

/**
 * The focus ring of the M51 update's 1.3 on the device, under Android's own input modes, keys and taps sent through
 * the system's input pipeline, read off the window's pixels at the ring's offset, in light mode. Welcome's two
 * actions, the focus moved with the d-pad: the ring on the focused action and on no other; once a finger touches an
 * action, the window is in touch mode and no ring is drawn. A button gives up the focus as the window enters touch
 * mode, so that touch alone cannot show the ring keeps to keyboard input; a field keeps it, so Name's field shows it:
 * tapped, it has the focus and no ring, until a key takes the window out of touch mode. A dialog that a key opens takes
 * the window's focus, and with it the ring from the action that opened it, which keeps the focus in its own window.
 */
class FocusRingDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val colors = FermixColors.Light

    @Test
    fun the_d_pad_rings_the_focused_action_alone_and_a_touch_takes_the_ring_away() {
        rule.setContent {
            FermixTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(colors.canvas)) {
                    WelcomeAt(
                        pose = { MarkPose.Rest },
                        ms = { Drop.CLOCK_MILLIS.toFloat() },
                        actions = WelcomeActions(onGetStarted = {}, onNoFermix = {}),
                    )
                }
            }
        }
        rule.awaitWindowFocus()
        val getStarted = rule.activity.getString(R.string.onboarding_welcome_get_started)
        val noFermix = rule.activity.getString(R.string.onboarding_welcome_no_fermix)

        val first = focusNext()
        val second = if (first == getStarted) noFermix else getStarted
        assertRingOn(first, off = second)

        assertEquals("the d-pad moved on to the other action", second, focusNext())
        assertRingOn(second, off = first)

        touch(first)
        rule.waitUntil("no ring once a finger touched an action", STEP_MILLIS) {
            val image = frame()
            !ringedAbove(image, bounds(first)) && !ringedAbove(image, bounds(second))
        }
        val image = frame()
        for (action in listOf(first, second)) {
            assertCanvas("$action's left, after a touch", image, leftOf(bounds(action)))
        }
    }

    @Test
    fun a_tapped_field_has_the_focus_and_no_ring_until_a_key_takes_the_window_out_of_touch_mode() {
        val paired = PairedFacts(record(gateway = 1, tint = "Ocean"), listOf(record(gateway = 5)), true, false)
        rule.setContent {
            FermixTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(colors.canvas)) { NameScreen(paired = paired, onContinue = {}) }
            }
        }
        rule.awaitWindowFocus()

        tap(rule.onNode(hasSetTextAction()))
        rule.waitUntil("the tapped field has the focus", STEP_MILLIS) { fieldFocused() }
        assertCanvas("the tapped field's left", frame(), leftOf(fieldBounds()))

        // A d-pad key in touch mode takes the window out of it and goes no further, so the field keeps the focus.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        rule.waitUntil("the ring around the field once a key was pressed", STEP_MILLIS) {
            near(frame().at(leftOf(fieldBounds())), colors.ink)
        }
        assertEquals("the field kept the focus", true, fieldFocused())
    }

    @Test
    fun a_dialog_that_takes_the_window_s_focus_takes_the_ring_from_the_action_that_opened_it() {
        var open by mutableStateOf(false)
        rule.setContent {
            FermixTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(colors.canvas).padding(DIALOG_ACTION_PADDING)) {
                    PrimaryAction(text = DIALOG_ACTION, onClick = { open = true })
                    if (open) {
                        Dialog(onDismissRequest = { open = false }) {
                            Box(Modifier.size(DIALOG_SIZE).background(colors.agentBubble))
                        }
                    }
                }
            }
        }
        rule.awaitWindowFocus()
        assertEquals(DIALOG_ACTION, focusNext())
        rule.waitUntil("the ring around $DIALOG_ACTION", STEP_MILLIS) { ringedAbove(screen(), bounds(DIALOG_ACTION)) }

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        rule.waitUntil("the dialog took the ring from $DIALOG_ACTION", STEP_MILLIS) {
            open && !ringedAbove(screen(), bounds(DIALOG_ACTION))
        }
        assertCanvas("$DIALOG_ACTION's left, under the dialog", screen(), leftOf(bounds(DIALOG_ACTION)))
        assertEquals("the action kept the focus in its window", DIALOG_ACTION, focusedAction())
    }

    /** The activity's window alone, under a dialog's own. */
    private fun screen(): Bitmap =
        rule.onNode(isRoot() and hasAnyDescendant(hasText(DIALOG_ACTION))).captureToImage().asAndroidBitmap()

    /** One press of the d-pad's down, through the input pipeline; the action that takes the focus, waited for. */
    private fun focusNext(): String {
        val before = focusedAction()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        var now: String? = null
        rule.waitUntil("an action other than $before has the focus", STEP_MILLIS) {
            now = focusedAction()
            now != null && now != before
        }
        return requireNotNull(now)
    }

    /** The words of the action that has the focus, or null while none has it. */
    private fun focusedAction(): String? =
        rule
            .onAllNodes(isFocused())
            .fetchSemanticsNodes()
            .firstNotNullOfOrNull { node -> node.config.getOrNull(SemanticsProperties.Text) }
            ?.joinToString()

    /** The ring around [on], waited for as its pixels show it, and around [off] none. */
    private fun assertRingOn(
        on: String,
        off: String,
    ) {
        rule.waitUntil("the ring around $on", STEP_MILLIS) { ringedAbove(frame(), bounds(on)) }
        val image = frame()
        val ringed = bounds(on)
        assertInk("$on's left", image, leftOf(ringed))
        assertCanvas("the gap above $on", image, Pair(ringed.center.x, ringed.top - px(FOCUS_RING_OFFSET / 2)))
        assertCanvas("$off's left", image, leftOf(bounds(off)))
    }

    private fun fieldFocused(): Boolean =
        rule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()

    private fun fieldBounds(): Rect = rule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot

    private fun touch(action: String) = tap(rule.onNodeWithText(action))

    /** A tap on [target], as a finger makes one: through the input pipeline, which puts the window in touch mode. */
    private fun tap(target: SemanticsNodeInteraction) {
        val where = target.fetchSemanticsNode().boundsInWindow.center
        val decor = rule.activity.window.decorView
        val origin = IntArray(2)
        rule.runOnUiThread { decor.getLocationOnScreen(origin) }
        val x = origin[0] + where.x
        val y = origin[1] + where.y
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        send(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
    }

    /** [event] through the input pipeline, then recycled. */
    private fun send(event: MotionEvent) {
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun frame(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()

    private fun bounds(action: String): Rect = rule.onNodeWithText(action).fetchSemanticsNode().boundsInRoot

    /** The middle of the ring's stroke above [bounds]: the offset and half the stroke out. */
    private fun ringedAbove(
        image: Bitmap,
        bounds: Rect,
    ): Boolean = near(image.at(Pair(bounds.center.x, bounds.top - middle())), colors.ink)

    /** The middle of the ring's stroke beside [bounds], at its start. */
    private fun leftOf(bounds: Rect): Pair<Float, Float> = Pair(bounds.left - middle(), bounds.center.y)

    private fun middle(): Float = px(FOCUS_RING_OFFSET) + px(FOCUS_RING_WIDTH) / 2f

    private fun px(dp: Dp): Float = dp.value * rule.activity.resources.displayMetrics.density

    private fun assertInk(
        what: String,
        image: Bitmap,
        at: Pair<Float, Float>,
    ) = assertNear(what, image.at(at), colors.ink)

    private fun assertCanvas(
        what: String,
        image: Bitmap,
        at: Pair<Float, Float>,
    ) = assertNear(what, image.at(at), colors.canvas)

    private fun assertNear(
        what: String,
        pixel: Int,
        expected: Color,
    ) {
        if (near(pixel, expected)) return
        throw AssertionError("$what is #${Integer.toHexString(pixel)}, not #${Integer.toHexString(expected.toArgb())}")
    }
}

private fun Bitmap.at(point: Pair<Float, Float>): Int = getPixel(point.first.toInt(), point.second.toInt())

/** Whether [pixel] is [expected], each channel within [CHANNEL_SLACK]. */
private fun near(
    pixel: Int,
    expected: Color,
): Boolean {
    val want = expected.toArgb()
    return listOf(RED, GREEN, BLUE).all { shift ->
        abs((pixel shr shift and CHANNEL) - (want shr shift and CHANNEL)) <= CHANNEL_SLACK
    }
}
