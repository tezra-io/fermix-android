package io.tezra.fermix.instance

import android.text.format.Formatter
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import java.time.ZoneId

// The visual canon's `.ihd`: centred, 12 dp above, 8 below and 24 at the sides, 4 dp between its lines and
// 8 more above the name, which its 48 dp target gives (design section 13.8), as it does the reset link;
// the page's 12 dp at the foot; the Unpair row 12 dp below the log; the `.pfoot` 16 dp under it.
private val HEADER_TOP = 12.dp
private val HEADER_BOTTOM = 8.dp
private val SIDES = 24.dp
private val HEADER_GAP = 4.dp
private val PAGE_FOOT = 12.dp
private val UNPAIR_TOP = 12.dp
private val FOOTER_TOP = 16.dp

/**
 * The canon's `.ihd a`, 500 12/16, underlined: the accent marked it as an action, and in the ink it is told from
 * "Fermix on {host}" above it by its underline, as the M51 update's reference player draws a text button; and
 * `.pfoot`, 400 12/16.
 */
private val RESET_STYLE =
    FermixType.label.copy(fontSize = 12.sp, lineHeight = 16.sp, textDecoration = TextDecoration.Underline)
private val FOOTER_STYLE = FermixType.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** What the Instance screen's controls do, each the ViewModel's. */
data class InstanceActions(
    val onBack: () -> Unit,
    val onRename: (String?) -> Unit,
    val onTest: () -> Unit,
    val onNotifications: (Boolean) -> Unit,
    val onPreviews: (Boolean) -> Unit,
    val onClearCache: () -> Unit,
    val onUnpair: () -> Unit,
)

/**
 * The Instance screen (design section 13.7), not a settings screen: what the phone owns of one Fermix,
 * its connection, its key, its notifications and its cache, with the way out at the foot, the page
 * scrolled by [scroll], and the pairing's day in [zone], the phone's own. The rename and unpair dialogs
 * survive a rotation or a fold.
 */
@Composable
fun InstanceScreen(
    ui: InstanceUi,
    actions: InstanceActions,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var unpairing by rememberSaveable { mutableStateOf(false) }
    val host = ui.record.host
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(onBack = actions.onBack)
        FermixColumn(ColumnWidth.Narrow) {
            Column(modifier = Modifier.verticalScroll(scroll).padding(bottom = PAGE_FOOT)) {
                Header(ui, onName = { renaming = true }, onReset = { actions.onRename(null) })
                ConnectionSection(ui, actions.onTest)
                PhoneSection(ui, zone)
                NotificationsSection(ui, actions)
                StorageSection(ui.cacheBytes, actions.onClearCache)
                SectionHeader(stringResource(R.string.instance_section_diagnostics))
                Log(ui.diagnostics.map(::logLine))
                Spacer(modifier = Modifier.height(UNPAIR_TOP))
                KvRow(label = stringResource(R.string.instance_unpair_row, host), onClick = { unpairing = true })
                Footer()
            }
        }
    }
    if (renaming) {
        val done = { renaming = false }
        RenameDialog(ui.record, ui.others, onRename = {
            done()
            actions.onRename(it)
        }, onDismiss = done)
    }
    if (unpairing) {
        val done = { unpairing = false }
        UnpairDialog(host, onUnpair = {
            done()
            actions.onUnpair()
        }, onDismiss = done)
    }
}

/** The avatar, the name to tap and rename, "Fermix on {host}", and "Reset to gateway name" under a nickname. */
@Composable
private fun Header(
    ui: InstanceUi,
    onName: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = LocalFermixColors.current
    val record = ui.record
    Column(
        modifier =
            Modifier.fillMaxWidth().padding(
                start = SIDES,
                end = SIDES,
                top = HEADER_TOP,
                bottom = HEADER_BOTTOM,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HEADER_GAP),
    ) {
        InstanceAvatar(tint = record.tint, dot = ui.link.dot, size = AvatarSize.LARGE)
        Text(
            text = record.title,
            style = FermixType.headline,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .clickable(
                        onClickLabel = stringResource(R.string.instance_rename),
                        role = Role.Button,
                        onClick = onName,
                    ).minimumInteractiveComponentSize(),
        )
        Text(
            text = stringResource(R.string.instance_on_host, record.host),
            style = FermixType.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (record.nickname != null) {
            Text(
                text = stringResource(R.string.instance_reset_name),
                style = RESET_STYLE,
                color = colors.ink,
                modifier = Modifier.clickable(role = Role.Button, onClick = onReset).minimumInteractiveComponentSize(),
            )
        }
    }
}

/** State, path, the candidates, the protocol, and "Test connection" with its result in place. */
@Composable
private fun ConnectionSection(
    ui: InstanceUi,
    onTest: () -> Unit,
) {
    val link = ui.link
    val host = ui.record.host
    SectionHeader(stringResource(R.string.instance_section_connection))
    val state = if (link is Link.Up) stringResource(R.string.instance_state_connected) else linkWords(link, host)
    KvRow(label = stringResource(R.string.instance_state), value = state)
    if (link is Link.Up) {
        KvRow(
            label = stringResource(R.string.instance_path),
            value = pathWords(link.scope, link.latencyMs),
        )
    }
    Candidates(ui.record.candidates, ui.record.port) { it in ui.reachable }
    KvRow(
        label = stringResource(R.string.instance_protocol),
        value = stringResource(R.string.instance_protocol_version),
        mono = true,
    )
    KvRow(
        label = stringResource(R.string.instance_test_connection),
        value = testWords(ui.test, host),
        labelStyle = ACTION_LABEL,
        onClick = onTest,
    )
}

/** The last test's outcome in the deck's words: the path it reached, the banner's line, or the changed identity. */
@Composable
private fun testWords(
    test: TestState,
    host: String,
): String? {
    val outcome = (test as? TestState.Done)?.outcome
    return when {
        test == TestState.Running -> stringResource(R.string.instance_link_connecting)
        outcome is TestOutcome.Reached -> pathWords(outcome.candidate.scope, outcome.millis)
        outcome is TestOutcome.NotReached -> stringResource(R.string.instance_test_not_reached, host)
        outcome == TestOutcome.WrongIdentity -> stringResource(R.string.instance_link_identity_changed, host)
        else -> null
    }
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
    record.deviceName?.let { KvRow(label = stringResource(R.string.instance_phone_name), value = it) }
    record.pairedAt?.let {
        KvRow(label = stringResource(R.string.instance_paired_since), value = pairedDate(it, zone, locale))
    }
    KvRow(label = stringResource(R.string.instance_key), value = keyFingerprint(record.id), mono = true)
    val build = if (ui.releaseBuild) R.string.instance_secure_release else R.string.instance_secure_debug
    KvRow(label = stringResource(build))
}

/** The two switches, or the line that says the daemon has no FCM, in their place. */
@Composable
private fun NotificationsSection(
    ui: InstanceUi,
    actions: InstanceActions,
) {
    SectionHeader(stringResource(R.string.instance_section_notifications))
    if (!ui.record.pushReady) {
        KvRow(
            label = stringResource(R.string.instance_notifications_not_set_up, ui.record.host),
            labelStyle = FermixType.body.copy(color = LocalFermixColors.current.textSecondary),
        )
        return
    }
    SwitchRow(stringResource(R.string.instance_notifications), ui.record.notificationsEnabled, actions.onNotifications)
    SwitchRow(stringResource(R.string.instance_previews), ui.previews, actions.onPreviews)
}

/** The media cache's size, once read, and "Clear media cache". */
@Composable
private fun StorageSection(
    cacheBytes: Long?,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.instance_section_storage))
    KvRow(
        label = stringResource(R.string.instance_cache),
        value =
            cacheBytes?.let {
                Formatter.formatShortFileSize(context, it)
            },
    )
    KvRow(
        label = stringResource(R.string.instance_clear_cache),
        labelStyle = ACTION_LABEL,
        onClick = onClear,
    )
}

@Composable
private fun Footer() {
    Text(
        text = stringResource(R.string.instance_footer),
        style = FOOTER_STYLE,
        color = LocalFermixColors.current.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(start = SIDES, end = SIDES, top = FOOTER_TOP),
    )
}
