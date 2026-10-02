package io.tezra.fermix.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticUse

// The visual canon's Scan, dark in both modes: the camera's stand-in, `radial-gradient(120% 80% at 50% 40%,
// #3A3D46 0%, #17181C 62%, #0B0B0D 100%)`; a 64 dp bar with back and torch in white; a 240 dp reticle
// 96 dp down, its corners drawn 3 dp wide on a 16 dp radius and 44 dp long; the hint 28 dp under it; and
// "Paste a pairing link" on white at 16 %.
private val CAMERA_LIGHT = Color(0xFF3A3D46)
private val CAMERA_MID = Color(0xFF17181C)
private val CAMERA_DARK = Color(0xFF0B0B0D)
private const val CAMERA_MID_STOP = 0.62f
private const val CAMERA_CENTRE_X = 0.5f
private const val CAMERA_CENTRE_Y = 0.4f
private const val CAMERA_RADIUS_X = 1.2f
private const val CAMERA_RADIUS_Y = 0.8f
private val ON_CAMERA = Color.White
private val ON_CAMERA_FILL = Color.White.copy(alpha = 0.16f)
private val BAR_HEIGHT = 64.dp
private val BAR_SIDES = 4.dp
private val RETICLE = 240.dp
private val RETICLE_TOP = 96.dp
private val RETICLE_STROKE = 3.dp
private val RETICLE_CORNER = 16.dp
private val RETICLE_ARM = 44.dp
private val HINT_TOP = 28.dp
private val FOOT_SIDES = 24.dp
private val FOOT_BOTTOM = 16.dp

/** TalkBack's place for "Paste a pairing link": before everything else on the screen (design section 13.8). */
private const val PASTE_FIRST = -1f

/** Scan's actions: back, the torch switched on or off, and the paste. */
@Immutable
data class ScanActions(
    val onBack: () -> Unit,
    val onTorchChange: (Boolean) -> Unit,
    val onPaste: () -> Unit,
)

/**
 * Step 3 (design section 13.3), the frame around the camera: [preview] fills the window, the CameraX
 * preview on the phone, which a later change brings; on its stand-in the reticle, the torch, on or off as
 * [torchOn] says and not drawn when it is null (a camera without one), the hint, and "Paste a pairing
 * link", which TalkBack reaches first (section 13.8). A link the phone refused ([refused]) turns the hint
 * into "That's not a Fermix pairing code.", which TalkBack reads out, and plays `REJECT` (section 13.1).
 */
@Composable
fun ScanScreen(
    refused: Boolean,
    torchOn: Boolean?,
    actions: ScanActions,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
) {
    if (refused) HapticOnce(HapticUse.Refusal)
    // One traversal group, so that the paste's traversal index puts it before the bar and the hint.
    val frame = modifier.fillMaxSize().drawBehind { drawCameraStandIn() }.semantics { isTraversalGroup = true }
    Box(modifier = frame) {
        preview()
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            ScanBar(torchOn = torchOn, actions = actions)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Reticle(modifier = Modifier.padding(top = RETICLE_TOP))
                Hint(refused = refused)
            }
            FermixColumn(ColumnWidth.Narrow) {
                PasteOnCamera(onPaste = actions.onPaste)
            }
        }
    }
}

/**
 * The canon's stand-in: its 120 % × 80 % ellipse, centred 40 % down, drawn as a circle of the ellipse's
 * width squashed to its height.
 */
private fun DrawScope.drawCameraStandIn() {
    if (size.isEmpty()) return
    val centre = Offset(size.width * CAMERA_CENTRE_X, size.height * CAMERA_CENTRE_Y)
    val radius = size.width * CAMERA_RADIUS_X
    val squash = size.height * CAMERA_RADIUS_Y / radius
    val brush =
        Brush.radialGradient(
            0f to CAMERA_LIGHT,
            CAMERA_MID_STOP to CAMERA_MID,
            1f to CAMERA_DARK,
            center = centre,
            radius = radius,
        )
    // Squashed about the centre, the rectangle drawn this tall covers the window exactly.
    scale(scaleX = 1f, scaleY = squash, pivot = centre) {
        val top = centre.y - centre.y / squash
        drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, size.height / squash))
    }
}

/** The bar: back, and the torch when the camera has one. */
@Composable
private fun ScanBar(
    torchOn: Boolean?,
    actions: ScanActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = BAR_SIDES),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_onboarding_back),
                contentDescription = stringResource(R.string.onboarding_back),
                tint = ON_CAMERA,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (torchOn != null) Torch(on = torchOn, onChange = actions.onTorchChange)
    }
}

/** The torch as a toggle, so TalkBack says whether it is on; when on, it sits on the paste's white fill. */
@Composable
private fun Torch(
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    IconToggleButton(
        checked = on,
        onCheckedChange = onChange,
        colors =
            IconButtonDefaults.iconToggleButtonColors(
                contentColor = ON_CAMERA,
                checkedContentColor = ON_CAMERA,
                checkedContainerColor = ON_CAMERA_FILL,
            ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_onboarding_torch),
            contentDescription = stringResource(R.string.onboarding_torch),
        )
    }
}

/** The hint, or the refusal in its place, a polite live region so TalkBack reads the refusal as it comes. */
@Composable
private fun Hint(refused: Boolean) {
    val hint = if (refused) R.string.onboarding_scan_not_fermix else R.string.onboarding_scan_hint
    Text(
        text = stringResource(hint),
        style = FermixType.body,
        color = ON_CAMERA,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .padding(top = HINT_TOP, start = FOOT_SIDES, end = FOOT_SIDES)
                .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The reticle's four corners (the canon's `.ret`). */
@Composable
private fun Reticle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(RETICLE)) {
        val stroke = RETICLE_STROKE.toPx()
        val inset = stroke / 2f
        val box = Size(size.width - stroke, size.height - stroke)
        val radius = CornerRadius(RETICLE_CORNER.toPx())
        val arm = RETICLE_ARM.toPx()
        // The rounded square, kept only where the four corners' arms reach.
        clipRect(arm, 0f, size.width - arm, size.height, ClipOp.Difference) {
            clipRect(0f, arm, size.width, size.height - arm, ClipOp.Difference) {
                drawRoundRect(
                    color = ON_CAMERA,
                    topLeft = Offset(inset, inset),
                    size = box,
                    cornerRadius = radius,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun PasteOnCamera(onPaste: () -> Unit) {
    Button(
        onClick = onPaste,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = FOOT_SIDES, end = FOOT_SIDES, bottom = FOOT_BOTTOM)
                .heightIn(min = FermixSpacing.minTarget)
                .semantics { traversalIndex = PASTE_FIRST },
        colors = ButtonDefaults.buttonColors(containerColor = ON_CAMERA_FILL, contentColor = ON_CAMERA),
    ) {
        Text(text = stringResource(R.string.onboarding_paste_link), textAlign = TextAlign.Center)
    }
}
