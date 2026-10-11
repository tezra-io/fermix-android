package io.tezra.fermix.instance

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalFermixMotion
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.rowFocusRing
import java.time.ZoneId

// "Advanced" (the owner, 2026-10-10): a row 48 dp tall at least, 24 dp at the sides, its chevron turned up while it
// is open; under it the page's details, each button 24 dp in from the sides and 8 dp under what is above it.
private val SIDES = 24.dp
private val ROW_ENDS = 4.dp
private val BUTTON_TOP = 8.dp
private val CHEVRON = 24.dp
private const val CHEVRON_OPEN = 180f

/**
 * "Advanced", closed until the owner opens it ([startsOpen] a preview's), which the page keeps through a rotation or
 * a fold: the connection, this phone and the cache, each section's rows on a card of their own (RowsCard), the
 * cache's button under its card, the diagnostics' log, and "Reset to gateway name" under a nickname. It opens and
 * closes on the standard scheme's springs, the page's one animation, at once under reduce-motion. TalkBack reads the
 * row as a button, "Expanded" or "Collapsed".
 */
@Composable
internal fun Advanced(
    ui: InstanceUi,
    zone: ZoneId,
    actions: InstanceActions,
    startsOpen: Boolean,
) {
    var open by rememberSaveable { mutableStateOf(startsOpen) }
    AdvancedRow(open, onToggle = { open = !open })
    val reduced = LocalReducedMotion.current
    val motion = LocalFermixMotion.current
    val enter =
        if (reduced) {
            EnterTransition.None
        } else {
            expandVertically(motion.defaultSpatial.spec(false)) + fadeIn(motion.defaultEffects.spec(false))
        }
    val exit =
        if (reduced) {
            ExitTransition.None
        } else {
            shrinkVertically(motion.defaultSpatial.spec(false)) + fadeOut(motion.defaultEffects.spec(false))
        }
    AnimatedVisibility(visible = open, enter = enter, exit = exit) {
        Column {
            ConnectionSection(ui)
            PhoneSection(ui, zone)
            StorageSection(ui.cacheBytes, actions.onClearCache)
            SectionHeader(stringResource(R.string.instance_section_diagnostics))
            Log(ui.diagnostics.map(::logLine))
            if (ui.record.nickname != null) {
                val reset = stringResource(R.string.instance_reset_name)
                TonalButton(reset, { actions.onRename(null) }, Modifier.padding(start = SIDES, top = BUTTON_TOP))
            }
        }
    }
}

/** The row that opens and closes the details: "Advanced" and its chevron, one button to TalkBack. */
@Composable
private fun AdvancedRow(
    open: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalFermixColors.current
    val state = stringResource(if (open) R.string.instance_advanced_expanded else R.string.instance_advanced_collapsed)
    Row(
        modifier =
            Modifier
                .padding(top = BUTTON_TOP)
                .fillMaxWidth()
                .rowFocusRing()
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = state }
                .heightIn(min = FermixSpacing.minTarget)
                .padding(horizontal = SIDES, vertical = ROW_ENDS),
        horizontalArrangement = Arrangement.spacedBy(SIDES),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.instance_advanced),
            style = FermixType.label,
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_instance_chevron),
            contentDescription = null,
            tint = colors.ink,
            modifier = Modifier.size(CHEVRON).rotate(if (open) CHEVRON_OPEN else 0f),
        )
    }
}

/**
 * The path while the link is up (the status line says it too, and this row stands with the routes it chose from),
 * the candidates, each lit while it is reachable, and the protocol.
 */
@Composable
private fun ConnectionSection(ui: InstanceUi) {
    SectionHeader(stringResource(R.string.instance_section_connection))
    val link = ui.link
    val path = (link as? Link.Up)?.let { pathWords(it.scope, it.latencyMs) }
    val pathLabel = stringResource(R.string.instance_path)
    val protocol = stringResource(R.string.instance_protocol)
    val version = stringResource(R.string.instance_protocol_version)
    RowsCard(
        listOfNotNull(
            path?.let { words -> { KvRow(label = pathLabel, value = words, sides = CARD_ROW_SIDES) } },
            { Candidates(ui.record.candidates, ui.record.port, CARD_ROW_SIDES) { it in ui.reachable } },
            { KvRow(label = protocol, value = version, mono = true, sides = CARD_ROW_SIDES) },
        ),
    )
}

/** The phone's name as paired, since when, the gateway key's fingerprint, and the hardware and build. */
@Composable
private fun PhoneSection(
    ui: InstanceUi,
    zone: ZoneId,
) {
    val record = ui.record
    val locale = LocalConfiguration.current.locales[0]
    SectionHeader(stringResource(R.string.instance_section_phone))
    val name = stringResource(R.string.instance_phone_name)
    val since = stringResource(R.string.instance_paired_since)
    val paired = record.pairedAt?.let { pairedDate(it, zone, locale) }
    val key = stringResource(R.string.instance_key)
    val hardware = if (ui.releaseBuild) R.string.instance_secure_release else R.string.instance_secure_debug
    val build = stringResource(hardware)
    RowsCard(
        listOfNotNull(
            record.deviceName?.let { device -> { KvRow(label = name, value = device, sides = CARD_ROW_SIDES) } },
            paired?.let { day -> { KvRow(label = since, value = day, sides = CARD_ROW_SIDES) } },
            { KvRow(label = key, value = keyFingerprint(record.id), mono = true, sides = CARD_ROW_SIDES) },
            { KvRow(label = build, sides = CARD_ROW_SIDES) },
        ),
    )
}

/** The media cache's size, once read, on its card, and "Clear media cache" under it. */
@Composable
private fun StorageSection(
    cacheBytes: Long?,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.instance_section_storage))
    val cache = stringResource(R.string.instance_cache)
    val size = cacheBytes?.let { Formatter.formatShortFileSize(context, it) }
    RowsCard(listOf { KvRow(label = cache, value = size, sides = CARD_ROW_SIDES) })
    TonalButton(
        stringResource(R.string.instance_clear_cache),
        onClear,
        Modifier.padding(start = SIDES, top = BUTTON_TOP),
    )
}
