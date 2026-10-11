package io.tezra.fermix.instance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.tezra.fermix.data.Instance
import io.tezra.fermix.data.TINT_NAMES
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.focusRing

// The sheet as the share sheet lays its own (the visual canon's): its title 24 dp in from the sides and 4 dp under
// the grab bar, its controls 16 dp under it and 12 dp apart, and the sheet's 36 dp at the foot. A tint is a 40 dp disc
// in its 48 dp target, the one chosen ringed 2 dp wide, 2 dp out from the disc, in the ink.
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
        tint = record.tint,
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
 * "Picture" (the owner, 2026-10-10), over the Instance screen: the avatar's six tints, [tint] chosen, a photo of the
 * owner's to choose, and the photo's removal while [hasPhoto], each one of the screen's [actions]. A tint is set at
 * once and the sheet stays; choosing or removing a photo closes it ([onDismiss]) first. Put down, nothing changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PictureSheet(
    tint: String,
    hasPhoto: Boolean,
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
        PictureSheetContent(tint, hasPhoto, actions.onTint, onChoose = {
            onDismiss()
            actions.onChoosePhoto()
        }, onRemove = {
            onDismiss()
            actions.onRemovePhoto()
        })
    }
}

/** What "Picture" holds: its title, the six tints as one choice, "Choose a photo", and "Remove photo" with a photo. */
@Composable
internal fun PictureSheetContent(
    tint: String,
    hasPhoto: Boolean,
    onTint: (String) -> Unit,
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
        // Six 48 dp targets want 288 dp: a narrow window, or a large display size, takes them on two lines.
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(CONTROLS_GAP),
        ) {
            TINT_NAMES.forEach { name -> TintChoice(name, selected = name == tint, onClick = { onTint(name) }) }
        }
        TonalButton(stringResource(R.string.instance_picture_choose), onChoose, Modifier.fillMaxWidth())
        if (hasPhoto) TonalButton(stringResource(R.string.instance_picture_remove), onRemove, Modifier.fillMaxWidth())
    }
}

/**
 * One tint as a 40 dp disc in its 48 dp target, a radio button TalkBack names by the tint's own words ([spokenTint]),
 * ringed once chosen; its press lights the round target, never its square.
 */
@Composable
private fun TintChoice(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ink = LocalFermixColors.current.ink
    val fill = tintColor(name)
    val spoken = stringResource(spokenTint(name))
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
        drawCircle(color = fill, radius = TINT_DISC.toPx() / 2f)
        if (selected) {
            val ring = TINT_RING.toPx()
            val radius = TINT_DISC.toPx() / 2f + TINT_RING_GAP.toPx() + ring / 2f
            drawCircle(color = ink, radius = radius, style = Stroke(width = ring))
        }
    }
}

/**
 * The words TalkBack says for [name], one of data's TINT_NAMES, which the record stores: the design's names (section
 * 13.1), in strings.xml; a name that is none of them fails loud.
 */
private fun spokenTint(name: String): Int =
    when (name) {
        "Slate" -> R.string.instance_tint_slate
        "Sage" -> R.string.instance_tint_sage
        "Clay" -> R.string.instance_tint_clay
        "Plum" -> R.string.instance_tint_plum
        "Ocean" -> R.string.instance_tint_ocean
        "Sand" -> R.string.instance_tint_sand
        else -> error("tint '$name' is not one of $TINT_NAMES")
    }
