package io.tezra.fermix.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.tezra.fermix.data.nicknameRefusal
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.Tint

// The visual canon's "Name this Fermix" (6b): the title 8 dp down, the field 22 dp under it on 16 dp
// corners, the chips 8 dp apart under the field, and the row as the Chats list will show it 12 dp lower:
// the avatar in the tint, its mark's 8 dp dots in white, the second at 62 %, and the DEV tag.
private val TITLE_TOP = 8.dp
private val FIELD_TOP = 22.dp
private val FIELD_CORNER = 16.dp
private val CHIP_GAP = 8.dp
private val CHIPS_TOP = 8.dp
private val CHIP_HEIGHT = 36.dp
private val ROW_TOP = 12.dp
private val ROW_GAP = 12.dp
private val SMALL_DOT = 8.dp
private const val SMALL_GAP = 0.28f
private const val SECOND_DOT_ALPHA = 0.62f
private val TAG_CORNER = 6.dp

/**
 * Section 9.2's question at step 6, when this Fermix would carry another's name: the nickname field, the
 * three suggestions, and the row with the auto-picked tint as the Chats list will show it. "Continue"
 * offers a nickname data's rule takes, the field starting with the first suggestion it takes.
 */
@Composable
fun NameScreen(
    paired: PairedFacts,
    onContinue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    val suggestions =
        listOf(R.string.onboarding_name_dev, R.string.onboarding_name_production, R.string.onboarding_name_test)
            .map { stringResource(it) }
    val record = paired.record
    val free = { name: String -> nicknameRefusal(name, record.id, paired.others) == null }
    var text by rememberSaveable { mutableStateOf(suggestions.firstOrNull(free).orEmpty()) }
    OnboardingPage(
        modifier = modifier,
        actions = {
            PrimaryAction(
                text = stringResource(R.string.onboarding_continue),
                onClick = { onContinue(text.trim()) },
                enabled = free(text),
            )
        },
    ) {
        val title = stringResource(R.string.onboarding_name_title)
        Text(
            text = title,
            style = FermixType.headline,
            color = colors.ink,
            modifier = Modifier.padding(top = TITLE_TOP),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(top = FIELD_TOP).semantics { contentDescription = title },
            singleLine = true,
            isError = !free(text),
            shape = RoundedCornerShape(FIELD_CORNER),
            colors =
                OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = colors.hairline,
                    focusedBorderColor = colors.accentInk,
                    cursorColor = colors.accentInk,
                ),
        )
        Suggestions(suggestions = suggestions, chosen = text.trim(), onChoose = { text = it })
        InstanceRow(nickname = text.trim(), paired = paired, modifier = Modifier.padding(top = ROW_TOP))
    }
}

/** The canon's `.cchips`: the chosen one in ink, the others outlined. */
@Composable
private fun Suggestions(
    suggestions: List<String>,
    chosen: String,
    onChoose: (String) -> Unit,
) {
    val colors = LocalFermixColors.current
    FlowRow(
        modifier = Modifier.padding(top = CHIPS_TOP),
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        for (suggestion in suggestions) {
            val on = suggestion.equals(chosen, ignoreCase = true)
            FilterChip(
                selected = on,
                onClick = { onChoose(suggestion) },
                label = { Text(text = suggestion, style = FermixType.label) },
                modifier = Modifier.heightIn(min = CHIP_HEIGHT),
                shape = FermixShapes.chip,
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = colors.canvas,
                        labelColor = colors.ink,
                        selectedContainerColor = colors.ink,
                        selectedLabelColor = colors.canvas,
                    ),
                border = BorderStroke(FermixSpacing.hairline, if (on) colors.ink else colors.hairline),
            )
        }
    }
}

/** The paired Fermix as its Chats row: the tinted avatar, the nickname with its DEV tag, "Fermix on host". */
@Composable
private fun InstanceRow(
    nickname: String,
    paired: PairedFacts,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(FermixSpacing.avatar).background(tintOf(paired.record.tint), CircleShape),
            contentAlignment = Alignment.Center,
        ) { SmallMark() }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = nickname,
                    style = FermixType.title,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (showsDevTag(paired.record.profile, nickname)) DevTag()
            }
            Text(
                text = stringResource(R.string.onboarding_name_subtitle, paired.record.host),
                style = FermixType.bodyMedium,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The canon's `.tag`: mono on a hairline border. */
@Composable
private fun DevTag() {
    val colors = LocalFermixColors.current
    Text(
        text = stringResource(R.string.onboarding_name_dev_tag),
        style = FermixType.labelSmall.copy(fontFamily = FermixType.mono.fontFamily),
        color = colors.inkSecondary,
        modifier =
            Modifier
                .border(FermixSpacing.hairline, colors.hairline, RoundedCornerShape(TAG_CORNER))
                .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** The mark on a tint (the canon's `.mark.sm.on`). */
@Composable
private fun SmallMark() {
    val white = LocalFermixColors.current.onTint
    Canvas(modifier = Modifier.size(SMALL_DOT * (2f + SMALL_GAP), SMALL_DOT)) {
        val radius = SMALL_DOT.toPx() / 2f
        drawCircle(white, radius, Offset(radius, center.y))
        drawCircle(white.copy(alpha = SECOND_DOT_ALPHA), radius, Offset(size.width - radius, center.y))
    }
}

/** The design's tint a record names (data keeps it by name). */
private fun tintOf(name: String) = Tint.entries.single { it.name == name }.color
