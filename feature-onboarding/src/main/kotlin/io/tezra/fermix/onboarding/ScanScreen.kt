package io.tezra.fermix.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.HapticUse
import io.tezra.fermix.design.RingOn
import io.tezra.fermix.design.focusRing
import io.tezra.fermix.design.iconFocusRing

// The visual canon's Scan, dark in both modes: the camera's stand-in, `radial-gradient(120% 80% at 50% 40%,
// #3A3D46 0%, #17181C 62%, #0B0B0D 100%)`; a 64 dp bar with back and torch in white; the reticle 96 dp
// down (ScanMotion.kt draws it); the hint 28 dp under it; and "Paste a pairing link" on white at 16 %.
private val CAMERA_LIGHT = Color(0xFF3A3D46)
private val CAMERA_MID = Color(0xFF17181C)
private val CAMERA_DARK = Color(0xFF0B0B0D)
private const val CAMERA_MID_STOP = 0.62f
private const val CAMERA_CENTRE_X = 0.5f
private const val CAMERA_CENTRE_Y = 0.4f
private const val CAMERA_RADIUS_X = 1.2f
private const val CAMERA_RADIUS_Y = 0.8f
internal val ON_CAMERA = Color.White
private val ON_CAMERA_FILL = Color.White.copy(alpha = 0.16f)
private val BAR_HEIGHT = 64.dp
private val BAR_SIDES = 4.dp
private val RETICLE_TOP = 96.dp
private val HINT_TOP = 28.dp
private val FOOT_SIDES = 24.dp
private val FOOT_BOTTOM = 16.dp

// The rationale and the denied screen, which the canon does not draw, as its failure page (`.fail`) on the
// frame: the camera on a 72 dp disc of the paste's white at 16 %, 72 dp down and 28 dp above the title,
// the sentence 12 dp under it; their action 6 dp above the paste, the canon's gap between actions (`.ft`).
private val NOTE_TOP = 72.dp
private val NOTE_DISC = 72.dp
private val NOTE_ICON = 32.dp
private val NOTE_TITLE_TOP = 28.dp
private val NOTE_BODY_TOP = 12.dp
private val FOOT_GAP = 6.dp

/** TalkBack's place for "Paste a pairing link": before everything else on the screen (design section 13.8). */
private const val PASTE_FIRST = -1f

/**
 * Scan's actions: back, the torch switched on or off, the paste, and for a camera this app may not use
 * yet, the rationale's "Continue" to the system's prompt and the denied screen's "Open settings".
 */
@Immutable
data class ScanActions(
    val onBack: () -> Unit,
    val onTorchChange: (Boolean) -> Unit,
    val onPaste: () -> Unit,
    val onAllowCamera: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/** What the scan's frame holds (design section 13.3, step 3). */
enum class CameraAccess {
    /** The camera, under the reticle and the hint. */
    ALLOWED,

    /** Why the scan asks for the camera, before the system's prompt does. */
    RATIONALE,

    /** "Camera is off for Fermix", once the owner said no to the prompt. */
    DENIED,
}

/**
 * The frame's state: the camera when the system says this app may use it ([granted]), "Camera is off for
 * Fermix" once the owner said no to the prompt on this visit ([refusedPrompt]), and the rationale before
 * the prompt otherwise. A prompt Android no longer shows, once the owner said no twice, answers no at
 * once, so the rationale leads to the denied screen.
 */
fun cameraAccess(
    granted: Boolean,
    refusedPrompt: Boolean,
): CameraAccess =
    when {
        granted -> CameraAccess.ALLOWED
        refusedPrompt -> CameraAccess.DENIED
        else -> CameraAccess.RATIONALE
    }

/**
 * What the scan shows: whether the phone [refused] a link, the torch, on or off as [torchOn] says and not
 * drawn when it is null (a camera without one), what the frame holds, as [access] allows, whether the
 * camera [found] a Fermix code, which the reticle locks onto before Connecting follows, and the scan's
 * [visit], a new one each time it comes back to the top, in which the reticle settles in once.
 */
@Immutable
data class ScanUi(
    val refused: Boolean,
    val torchOn: Boolean?,
    val access: CameraAccess = CameraAccess.ALLOWED,
    val found: Boolean = false,
    val visit: Int = 0,
)

/**
 * Step 3 (design section 13.3), the frame around the camera, dark in both modes: with the camera allowed,
 * [preview] fills the window, the CameraX preview on the phone, and on it the reticle, the torch and the
 * hint; before the camera is allowed, the rationale in their place with "Continue" to the system's prompt,
 * or "Camera is off for Fermix" with "Open settings". "Paste a pairing link" is at the foot of all three,
 * which TalkBack reaches first (section 13.8). A link the phone refused turns the hint into "That's not a
 * Fermix pairing code.", which TalkBack reads out, and plays `REJECT` (section 13.1). The reticle settles in,
 * breathes while the camera searches and locks onto a code it found; a refused code's hint shakes once (the M51
 * update's 7.4, in ScanMotion.kt).
 */
@Composable
fun ScanScreen(
    state: ScanUi,
    actions: ScanActions,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
) {
    val allowed = state.access == CameraAccess.ALLOWED
    if (state.refused && allowed) HapticOnce(HapticUse.Refusal)
    val reticle = rememberReticle(shown = allowed, found = state.found, visit = state.visit)
    val shake = rememberShake(refused = state.refused && allowed)
    ScanAt(state, actions, ScanMotion(reticle, shake), modifier, preview)
}

/** The scan with the reticle and the hint where [motion] has them, as the screen or a preview gives it. */
@Composable
internal fun ScanAt(
    state: ScanUi,
    actions: ScanActions,
    motion: ScanMotion,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
) {
    val access = state.access
    val allowed = access == CameraAccess.ALLOWED
    // One traversal group, so that the paste's traversal index puts it before the bar and the hint.
    val frame = modifier.fillMaxSize().drawBehind { drawCameraStandIn() }.semantics { isTraversalGroup = true }
    Box(modifier = frame) {
        if (allowed) preview()
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            ScanBar(torchOn = state.torchOn.takeIf { allowed }, actions = actions)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (access) {
                    CameraAccess.ALLOWED -> {
                        Reticle(pose = motion.reticle, modifier = Modifier.padding(top = RETICLE_TOP))
                        val sides = Modifier.padding(top = HINT_TOP, start = FOOT_SIDES, end = FOOT_SIDES)
                        Hint(refused = state.refused, shake = motion.shake, modifier = sides)
                    }

                    CameraAccess.RATIONALE -> {
                        CameraNote(
                            title = R.string.onboarding_camera_rationale_title,
                            body = R.string.onboarding_camera_rationale_body,
                        )
                    }

                    CameraAccess.DENIED -> {
                        CameraNote(R.string.onboarding_camera_off_title, body = null)
                    }
                }
            }
            FermixColumn(ColumnWidth.Narrow) {
                ScanFoot(access = access, actions = actions)
            }
        }
    }
}

/**
 * The foot: "Paste a pairing link", under the one action a camera not yet allowed has, "Continue" to the
 * system's prompt or "Open settings".
 */
@Composable
private fun ScanFoot(
    access: CameraAccess,
    actions: ScanActions,
) {
    val sides = Modifier.padding(start = FOOT_SIDES, end = FOOT_SIDES, bottom = FOOT_GAP)
    Column {
        when (access) {
            CameraAccess.ALLOWED -> {}

            CameraAccess.RATIONALE -> {
                val label = stringResource(R.string.onboarding_continue)
                PrimaryAction(label, actions.onAllowCamera, modifier = sides, dark = true)
            }

            CameraAccess.DENIED -> {
                val label = stringResource(R.string.onboarding_open_settings)
                PrimaryAction(label, actions.onOpenSettings, modifier = sides, dark = true)
            }
        }
        PasteOnCamera(onPaste = actions.onPaste)
    }
}

/**
 * The rationale or the denied screen in the reticle's place: the camera on its disc, the title, and the
 * rationale's one sentence under it, in white on the frame.
 */
@Composable
private fun CameraNote(
    @StringRes title: Int,
    @StringRes body: Int?,
) {
    Box(
        modifier =
            Modifier
                .padding(top = NOTE_TOP)
                .size(NOTE_DISC)
                .clip(CircleShape)
                .background(ON_CAMERA_FILL),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_onboarding_camera),
            contentDescription = null,
            modifier = Modifier.size(NOTE_ICON),
            tint = ON_CAMERA,
        )
    }
    Text(
        text = stringResource(title),
        style = FermixType.headline,
        color = ON_CAMERA,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = NOTE_TITLE_TOP, start = FOOT_SIDES, end = FOOT_SIDES),
    )
    if (body != null) {
        Text(
            text = stringResource(body),
            style = FermixType.body,
            color = ON_CAMERA,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = NOTE_BODY_TOP, start = FOOT_SIDES, end = FOOT_SIDES),
        )
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
        IconButton(onClick = actions.onBack, modifier = Modifier.iconFocusRing(RingOn.Dark)) {
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
        modifier = Modifier.iconFocusRing(RingOn.Dark),
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

@Composable
private fun PasteOnCamera(onPaste: () -> Unit) {
    Button(
        onClick = onPaste,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = FOOT_SIDES, end = FOOT_SIDES, bottom = FOOT_BOTTOM)
                .heightIn(min = FermixSpacing.minTarget)
                .semantics { traversalIndex = PASTE_FIRST }
                .focusRing(FermixShapes.button, RingOn.Dark),
        colors = ButtonDefaults.buttonColors(containerColor = ON_CAMERA_FILL, contentColor = ON_CAMERA),
    ) {
        Text(text = stringResource(R.string.onboarding_paste_link), textAlign = TextAlign.Center)
    }
}
