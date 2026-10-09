package io.tezra.fermix.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.launch

/** The ring's stroke and its gap from the control (the M51 update's 1.3: "Focus | 2 dp `ink` ring, 2 dp offset"). */
val FOCUS_RING_WIDTH = 2.dp
val FOCUS_RING_OFFSET = 2.dp

/** A row's corners: a rectangle. */
private val ROW_CORNERS = RoundedCornerShape(0.dp)

/** Where a control whose ring shows, and what holds it ([raisedWhileFocused]), are placed over their siblings at 0. */
private const val RAISED = 1f

/**
 * What a focus ring lies on, which picks its colour: the ink on what content lies on, and on the ink, onInk, as the
 * update washes a mark on the ink in onInk.
 */
enum class RingOn {
    /** The canvas, the agent bubble, a sheet's, a dialog's or a bar's tone, a selected row's wash: the ink. */
    Surface,

    /** A fill in the ink, the owner's bubble: onInk. */
    Ink,

    /**
     * A surface dark in both modes, the camera's, the viewer's or the code card's: dark mode's ink in both modes, as
     * Scan's pills are, since light mode's near-black ink would not show on it.
     */
    Dark,

    /**
     * A picture, an image grid's cell, which can be any colour: the ink, lined inside with onInk, so that one of the
     * two lines reads on whatever the picture is (ContrastTest).
     */
    Picture,
}

/** The ring's colour on [on], in the mode of [colors]: the whole ring, or a two-tone ring's outer line. */
internal fun ringColor(
    colors: FermixColors,
    on: RingOn,
): Color =
    when (on) {
        RingOn.Surface, RingOn.Picture -> colors.ink
        RingOn.Ink -> colors.onInk
        RingOn.Dark -> FermixColors.Dark.ink
    }

/** A two-tone ring's inner line, a stroke's width inside [ringColor]'s: onInk on a picture; no other ring has one. */
internal fun ringLining(
    colors: FermixColors,
    on: RingOn,
): Color? = if (on == RingOn.Picture) colors.onInk else null

/**
 * The focus ring (the M51 update's 1.3): while the control after it in the chain is focused and the window shows
 * focus, a [FOCUS_RING_WIDTH] stroke [FOCUS_RING_OFFSET] outside the bounds this modifier is given, following [shape]
 * (a pill's full radius, a card's corner, each grown by the offset), in [ringColor] for [on]. The window shows focus
 * while it has the focus (not while a menu or a dialog over it holds it) and is in Compose's keyboard input mode,
 * Android's own "not in touch mode", which a key or a d-pad enters and a touch leaves, so a tap never shows it. It
 * takes no room, so no layout moves. It is drawn in the control's own layer after its content, and while it shows the
 * control is placed over its siblings, so a sibling drawn after it does not cover it; a control nested below the
 * level its neighbours lie at takes [raisedWhileFocused] on what holds it at theirs. A parent that clips at the
 * control's edge clips the ring too, so the focus a key moves asks a list or a scroll to bring the ring into view
 * with the control. It goes before the control's `clickable`, `toggleable`, `selectable` or `focusable` and after
 * any padding outside the control, so its bounds are the control's own: on a Material component, last in its
 * `modifier`, which Material lays out in its 48 dp touch target.
 */
fun Modifier.focusRing(
    shape: RoundedCornerShape,
    on: RingOn = RingOn.Surface,
): Modifier = ring(shape, on, within = false, shown = false)

/**
 * [focusRing] for a row that spans its column, a sheet or a menu: drawn inside its bounds in [shape], a rectangle
 * by default, its outer edge the row's own, as a ring outside would lie past the window's edge on a phone and under
 * a list's clip. A row at the bottom of a card takes the card's bottom corners, so the card's clip leaves it whole.
 */
fun Modifier.rowFocusRing(
    on: RingOn = RingOn.Surface,
    shape: RoundedCornerShape = ROW_CORNERS,
): Modifier = ring(shape, on, within = true, shown = false)

/**
 * [focusRing] for a Material icon button: 2 dp outside the 40 dp disc it draws its state layer in, which is inside
 * its 48 dp touch target, so the ring stays within the button's bounds, on the card or the bar it lies on, and a
 * scroll that brings the focused disc into view brings the whole target with it. An icon in a 48 dp target of its
 * own at a window's edge, with no disc, takes it too, as a ring outside the target would lie past the edge.
 */
fun Modifier.iconFocusRing(on: RingOn = RingOn.Surface): Modifier = ring(CircleShape, on, within = true, shown = false)

/**
 * Places what it modifies over its siblings while a control inside it shows its focus ring, so the ring is drawn
 * over them: for what holds a ringed control below the level its neighbours lie at, a list's item.
 */
fun Modifier.raisedWhileFocused(): Modifier = this then RaisedElement

/**
 * The ring of [focusRing] ([within] false) or [rowFocusRing] ([within] true), around what [ringedContent] marks
 * inside the control when [aroundContent] ([contentFocusRing]), drawn whatever has the focus while [shown]: for
 * design's specimens alone, which ring six controls at once and cannot focus them. It is internal, so no screen's
 * module can show a ring that is not the focus.
 */
internal fun Modifier.ring(
    shape: RoundedCornerShape,
    on: RingOn,
    within: Boolean,
    shown: Boolean,
    aroundContent: Boolean = false,
): Modifier = this then FocusRingElement(shape, on, within, shown, aroundContent)

private data class FocusRingElement(
    val shape: RoundedCornerShape,
    val on: RingOn,
    val within: Boolean,
    val shown: Boolean,
    val aroundContent: Boolean,
) : ModifierNodeElement<FocusRingNode>() {
    override fun create(): FocusRingNode = FocusRingNode(shape, on, within, shown, aroundContent)

    override fun update(node: FocusRingNode) {
        node.shape = shape
        node.on = on
        node.within = within
        node.shown = shown
        node.aroundContent = aroundContent
        node.invalidateMeasurement()
        node.invalidateDraw()
    }
}

/**
 * Whether the window shows the focus: it has the focus, and a key moved last, not a touch. Read only while a node's
 * control has the focus, so the two states are followed by the one control that has it.
 */
private fun CompositionLocalConsumerModifierNode.focusShown(): Boolean =
    currentValueOf(LocalInputModeManager).inputMode == InputMode.Keyboard &&
        currentValueOf(LocalWindowInfo).isWindowFocused

/** [placeable] placed over its siblings while [raised]. */
private fun MeasureScope.placedAt(
    placeable: Placeable,
    raised: () -> Boolean,
): MeasureResult =
    // Read as it is placed, so a change of the window's mode or focus places it again.
    layout(placeable.width, placeable.height) { placeable.place(0, 0, zIndex = if (raised()) RAISED else 0f) }

private class FocusRingNode(
    var shape: RoundedCornerShape,
    var on: RingOn,
    var within: Boolean,
    var shown: Boolean,
    var aroundContent: Boolean,
) : Modifier.Node(),
    FocusEventModifierNode,
    LayoutModifierNode,
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private var focused = false

    /** The left and right edges of what [ringedContent] marks inside the control, as last measured. */
    private var span: Pair<Int, Int>? = null

    override fun onFocusEvent(focusState: FocusState) {
        if (focused == focusState.isFocused) return
        focused = focusState.isFocused
        invalidateDraw()
        invalidatePlacement()
        // A control a key focuses is brought into view by its own focus target, which an icon button's disc is, in
        // its target where its ring lies, and a ring outside the control is not: what the ring lies on comes too.
        if (focused && focusShown()) coroutineScope.launch { bringIntoView(::ringBounds) }
    }

    private fun shows(): Boolean = shown || (focused && focusShown())

    /** What the ring is drawn around: what [ringedContent] marks, or the control's own bounds. */
    private fun ringed(size: Size): Rect {
        val (left, right) = span ?: return size.toRect()
        return Rect(left.toFloat(), 0f, right.toFloat(), size.height)
    }

    /** What the ring lies on, which a list or a scroll brings into view with the control: grown by it if outside. */
    private fun ringBounds(): Rect? {
        if (!isAttached) return null
        val grow = if (within) 0f else with(requireDensity()) { (FOCUS_RING_OFFSET + FOCUS_RING_WIDTH).toPx() }
        return ringed(requireLayoutCoordinates().size.toSize()).inflate(grow)
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        span = if (aroundContent) ringedSpan(placeable) else null
        return placedAt(placeable, ::shows)
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!shows()) return
        val colors = currentValueOf(LocalFermixColors)
        val bounds = ringed(size)
        val middle = ringMiddle(within, this)
        val width = FOCUS_RING_WIDTH.toPx()
        val outline = outlineAt(bounds, shape, middle, layoutDirection, this)
        drawPath(Path().apply { addRoundRect(outline) }, ringColor(colors, on), style = Stroke(width))
        val lining = ringLining(colors, on) ?: return
        val inner = outlineAt(bounds, shape, middle - width, layoutDirection, this)
        drawPath(Path().apply { addRoundRect(inner) }, lining, style = Stroke(width))
    }
}

private data object RaisedElement : ModifierNodeElement<RaisedNode>() {
    override fun create(): RaisedNode = RaisedNode()

    override fun update(node: RaisedNode) = Unit
}

/** [raisedWhileFocused]: the focus of the nearest focus targets inside it, which it is placed over its siblings for. */
private class RaisedNode :
    Modifier.Node(),
    FocusEventModifierNode,
    LayoutModifierNode,
    CompositionLocalConsumerModifierNode {
    private var holds = false

    override fun onFocusEvent(focusState: FocusState) {
        if (holds == focusState.hasFocus) return
        holds = focusState.hasFocus
        invalidatePlacement()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult = placedAt(measurable.measure(constraints)) { holds && focusShown() }
}

/** How far out of the control's bounds the middle of the ring's stroke lies: inside them, [within], by half of it. */
private fun ringMiddle(
    within: Boolean,
    density: Density,
): Float {
    val half = with(density) { FOCUS_RING_WIDTH.toPx() } / 2f
    return if (within) -half else with(density) { FOCUS_RING_OFFSET.toPx() } + half
}

/**
 * The line [out] pixels outside [bounds] in [shape], inside them when negative: the middle of a ring's stroke
 * ([ringMiddle]), or of a lining's a stroke further in. Each corner grows by as much, and a square one stays square.
 */
private fun outlineAt(
    bounds: Rect,
    shape: RoundedCornerShape,
    out: Float,
    layoutDirection: LayoutDirection,
    density: Density,
): RoundRect {
    val radius = { corner: CornerSize ->
        val own = corner.toPx(bounds.size, density)
        CornerRadius(if (own > 0f) (own + out).coerceAtLeast(0f) else 0f)
    }
    val ltr = layoutDirection == LayoutDirection.Ltr
    return RoundRect(
        rect = bounds.inflate(out),
        topLeft = radius(if (ltr) shape.topStart else shape.topEnd),
        topRight = radius(if (ltr) shape.topEnd else shape.topStart),
        bottomRight = radius(if (ltr) shape.bottomEnd else shape.bottomStart),
        bottomLeft = radius(if (ltr) shape.bottomStart else shape.bottomEnd),
    )
}
