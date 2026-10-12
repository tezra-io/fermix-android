package io.tezra.fermix.instance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.tezra.fermix.data.EYE_STYLES
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.TINT_NAMES
import io.tezra.fermix.design.EyeStyle
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.MarkEyePaths
import io.tezra.fermix.design.drawAvatarEyes
import io.tezra.fermix.design.focusRing

// The sheet as the share sheet lays its own (the visual canon's): its title 24 dp in from the sides and 4 dp under
// the grab bar, its controls 16 dp under it and 12 dp apart, and the sheet's 36 dp at the foot. A tint, and an eye
// style on the record's tint, is a 40 dp disc in its 48 dp target, the one chosen ringed 2 dp wide, 2 dp out from the
// disc, in the ink.
private val SHEET_SIDES = 24.dp
private val TITLE_TOP = 4.dp
private val CONTROLS_TOP = 16.dp
private val CONTROLS_GAP = 12.dp
private val SHEET_FOOT = 36.dp
private val TINT_DISC = 40.dp
private val TINT_RING = 2.dp
private val TINT_RING_GAP = 2.dp

/**
 * The Instance header's avatar (the owner, 2026-10-10): the tint or the owner's photo with the link's dot, 72 dp, the
 * button TalkBack calls "Change picture" that opens the Picture sheet ([onClick]). Its press lights its disc alone, an
 * unbounded ripple of the disc's radius, as an icon button's does: a clip to the circle would cut the dot, which lies
 * past it.
 */
@Composable
internal fun HeaderAvatar(
    record: Instance,
    dot: Dot,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.instance_picture_change)
    InstanceAvatar(
        record = record,
        dot = dot,
        size = AvatarSize.LARGE,
        picture = rememberAvatarPicture(record.id, record.pictureAt, AvatarSize.LARGE),
        modifier =
            Modifier
                .focusRing(CircleShape)
                .clickable(
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = AvatarSize.LARGE.disc / 2),
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { contentDescription = label },
    )
}

/**
 * "Picture" (the owner, 2026-10-10), over the Instance screen: the avatar's eight tints and its six eye styles
 * (2026-10-11), [record]'s chosen, a photo of the owner's to choose, and the photo's removal while there is one, each
 * one of the screen's [actions]. A tint or a style is set at once and the sheet stays; choosing or removing a photo
 * closes it ([onDismiss]) first. Put down, nothing changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PictureSheet(
    record: Instance,
    actions: InstanceActions,
    onDismiss: () -> Unit,
) {
    val colors = LocalFermixColors.current
    // Opened whole, as the attach sheet is: half open on a phone on its side, its buttons would sit under the edge.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.tonalSolid,
        scrimColor = colors.scrim,
    ) {
        PictureSheetContent(record, actions.onTint, actions.onEyes, onChoose = {
            onDismiss()
            actions.onChoosePhoto()
        }, onRemove = {
            onDismiss()
            actions.onRemovePhoto()
        })
    }
}

/**
 * What "Picture" holds: its title, [record]'s eight tints as one choice and its six eye styles, each on its tint, as
 * another, "Choose a photo", and "Remove photo" with a photo. With a photo the two choices still set the record, whose
 * disc the photo then covers.
 */
@Composable
internal fun PictureSheetContent(
    record: Instance,
    onTint: (String) -> Unit,
    onEyes: (String) -> Unit,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = SHEET_SIDES, end = SHEET_SIDES, bottom = SHEET_FOOT),
        verticalArrangement = Arrangement.spacedBy(CONTROLS_GAP),
    ) {
        Text(
            text = stringResource(R.string.instance_picture_title),
            style = FermixType.title,
            color = colors.ink,
            modifier = Modifier.padding(top = TITLE_TOP, bottom = CONTROLS_TOP - CONTROLS_GAP).semantics { heading() },
        )
        TintChoices(record.tint, onTint)
        EyesChoices(record, onEyes)
        TonalButton(stringResource(R.string.instance_picture_choose), onChoose, Modifier.fillMaxWidth())
        if (record.pictureAt != null) {
            TonalButton(stringResource(R.string.instance_picture_remove), onRemove, Modifier.fillMaxWidth())
        }
    }
}

/**
 * The eight tints as one choice, [tint] chosen. Eight 48 dp targets want 384 dp: where they do not fit on a line, a
 * phone's sheet among them, they take two lines of four, never seven and one.
 */
@Composable
private fun TintChoices(
    tint: String,
    onTint: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val all = FermixSpacing.minTarget * TINT_NAMES.size <= maxWidth
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(CONTROLS_GAP),
            maxItemsInEachRow = if (all) TINT_NAMES.size else TINT_NAMES.size / 2,
        ) {
            TINT_NAMES.forEach { name -> TintChoice(name, selected = name == tint, onClick = { onTint(name) }) }
        }
    }
}

/**
 * The six eye styles as one choice, each on [record]'s tint, its eyes chosen. Six 48 dp targets want 288 dp: a narrow
 * window, or a large display size, takes them on two lines.
 */
@Composable
private fun EyesChoices(
    record: Instance,
    onEyes: (String) -> Unit,
) {
    val fill = tintColor(record.tint)
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(CONTROLS_GAP),
    ) {
        EyeStyle.entries.forEach { style ->
            EyesChoice(style, fill, selected = style.name == record.eyes, onClick = { onEyes(style.name) })
        }
    }
}

/** One tint as a 40 dp disc in its 48 dp target, a radio button TalkBack names by the tint's name ([spokenTint]). */
@Composable
private fun TintChoice(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val fill = tintColor(name)
    DiscChoice(stringResource(spokenTint(name)), selected, onClick) { drawCircle(color = fill, radius = it / 2f) }
}

/**
 * One eye style as a 40 dp disc of [fill] with the style's eyes in the mark's ink, in its 48 dp target, a radio button
 * TalkBack names by the style's words ([spokenEyes]). Its eyes' paths are made once.
 */
@Composable
private fun EyesChoice(
    style: EyeStyle,
    fill: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val paths = remember { MarkEyePaths() }
    DiscChoice(stringResource(spokenEyes(style.name)), selected, onClick) { disc ->
        drawCircle(color = fill, radius = disc / 2f)
        translate(left = (size.width - disc) / 2f, top = (size.height - disc) / 2f) {
            drawAvatarEyes(style, Size(disc, disc), paths)
        }
    }
}

/**
 * A 48 dp target that [draw]s a 40 dp disc, given its width in pixels, at its centre: a radio button TalkBack calls
 * [spoken], ringed in the ink once [selected]; its press lights the round target, never its square.
 */
@Composable
private fun DiscChoice(
    spoken: String,
    selected: Boolean,
    onClick: () -> Unit,
    draw: DrawScope.(disc: Float) -> Unit,
) {
    val ink = LocalFermixColors.current.ink
    Canvas(
        modifier =
            Modifier
                .size(FermixSpacing.minTarget)
                .focusRing(CircleShape)
                .selectable(
                    selected = selected,
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = FermixSpacing.minTarget / 2),
                    role = Role.RadioButton,
                    onClick = onClick,
                ).semantics { contentDescription = spoken },
    ) {
        draw(TINT_DISC.toPx())
        if (selected) {
            val ring = TINT_RING.toPx()
            val radius = TINT_DISC.toPx() / 2f + TINT_RING_GAP.toPx() + ring / 2f
            drawCircle(color = ink, radius = radius, style = Stroke(width = ring))
        }
    }
}

/**
 * The words TalkBack says for [name], one of data's TINT_NAMES, which the record stores: the tint's own name (the
 * owner's eight, 2026-10-11, which the design does not name), in strings.xml; a name that is none of them fails loud.
 */
private fun spokenTint(name: String): Int =
    when (name) {
        "Coral" -> R.string.instance_tint_coral
        "Amber" -> R.string.instance_tint_amber
        "Olive" -> R.string.instance_tint_olive
        "Teal" -> R.string.instance_tint_teal
        "Sky" -> R.string.instance_tint_sky
        "Iris" -> R.string.instance_tint_iris
        "Rose" -> R.string.instance_tint_rose
        "Slate" -> R.string.instance_tint_slate
        else -> error("tint '$name' is not one of $TINT_NAMES")
    }

/**
 * The words TalkBack says for [name], one of data's EYE_STYLES, which the record stores (the owner, 2026-10-11; not
 * the design's words), in strings.xml; a name that is none of them fails loud.
 */
private fun spokenEyes(name: String): Int =
    when (name) {
        "Open" -> R.string.instance_eyes_open
        "Happy" -> R.string.instance_eyes_happy
        "Wink" -> R.string.instance_eyes_wink
        "Squint" -> R.string.instance_eyes_squint
        "Sleepy" -> R.string.instance_eyes_sleepy
        "Sideways" -> R.string.instance_eyes_sideways
        else -> error("eyes '$name' are not one of $EYE_STYLES")
    }
