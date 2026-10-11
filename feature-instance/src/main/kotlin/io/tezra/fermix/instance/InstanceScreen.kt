package io.tezra.fermix.instance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.focusRing
import java.time.ZoneId

// The page (the owner, 2026-10-10, over the visual canon's `.ihd`): the header centred, 12 dp above, 8 below and 24
// at the sides, 4 dp between its lines; Rename and Test connection 8 dp apart, 12 dp under it; the Notifications
// card (RowsCard); Unpair 24 dp under what is above it; the `.pfoot` 16 dp under that, and the page's 12 dp at the
// foot.
private val HEADER_TOP = 12.dp
private val HEADER_BOTTOM = 8.dp
private val SIDES = 24.dp
private val HEADER_GAP = 4.dp
private val BUTTON_GAP = 8.dp
private val BUTTONS_TOP = 12.dp
private val PAGE_FOOT = 12.dp
private val UNPAIR_TOP = 24.dp
private val FOOTER_TOP = 16.dp

/** The canon's `.pfoot`, 400 12/16. */
private val FOOTER_STYLE = FermixType.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp)

/**
 * What the Instance screen's controls do, each the ViewModel's; [onChoosePhoto] opens the system's Photo Picker for
 * an image, whose pick the route hands the ViewModel.
 */
data class InstanceActions(
    val onBack: () -> Unit,
    val onRename: (String?) -> Unit,
    val onTest: () -> Unit,
    val onNotifications: (Boolean) -> Unit,
    val onPreviews: (Boolean) -> Unit,
    val onClearCache: () -> Unit,
    val onUnpair: () -> Unit,
    val onTint: (String) -> Unit,
    val onChoosePhoto: () -> Unit,
    val onRemovePhoto: () -> Unit,
)

/**
 * The Instance screen (design section 13.7, laid out anew by the owner, 2026-10-10), not a settings screen: who
 * the Fermix is and how it is reached, in one line; Rename and Test connection; its notifications; "Advanced",
 * closed until opened, with its connection, this phone, its cache, its diagnostics and the name's reset; and the
 * way out at the foot. What the page does is a button, never underlined words; the avatar is the one that opens its
 * Picture sheet. The page is scrolled by [scroll], and the pairing's day read in [zone], the phone's own. The rename
 * and unpair dialogs and the sheet survive a rotation or a fold.
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
    var picturing by rememberSaveable { mutableStateOf(false) }
    val host = ui.record.host
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(onBack = actions.onBack)
        FermixColumn(ColumnWidth.Narrow) {
            Column(modifier = Modifier.verticalScroll(scroll).padding(bottom = PAGE_FOOT)) {
                Header(ui, onPicture = { picturing = true })
                Controls(ui.test, host, onRename = { renaming = true }, onTest = actions.onTest)
                NotificationsCard(ui, actions)
                Advanced(ui, zone, actions, startsOpen = false)
                UnpairButton(host, onClick = { unpairing = true })
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
    if (picturing) {
        PictureSheet(ui.record.tint, ui.record.pictureAt != null, actions, onDismiss = { picturing = false })
    }
}

/**
 * The avatar, the button that opens the Picture sheet ([onPicture]), the name, "Fermix on {host}", the one status
 * line, none while no session is open, and, after a photo that could not be used, the line that says so.
 */
@Composable
private fun Header(
    ui: InstanceUi,
    onPicture: () -> Unit,
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
        HeaderAvatar(record, ui.link.dot, onPicture)
        Text(text = record.title, style = FermixType.headline, color = colors.ink, textAlign = TextAlign.Center)
        Text(
            text = stringResource(R.string.instance_on_host, record.host),
            style = FermixType.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        statusWords(statusLineOf(ui.link), record.host)?.let { status ->
            Text(
                text = status,
                style = FermixType.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (ui.pictureFailed) {
            Text(
                text = stringResource(R.string.instance_picture_failed),
                style = FermixType.bodyMedium,
                color = colors.errText,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Rename and Test connection, side by side and each half the row, or one over the other once the type is too
 * large for both on one line; the last test's outcome under them, which TalkBack says as it comes, politely, as the
 * test's button is no longer where it shows.
 */
@Composable
private fun Controls(
    test: TestState,
    host: String,
    onRename: () -> Unit,
    onTest: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = SIDES, end = SIDES, top = BUTTONS_TOP),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BUTTON_GAP),
    ) {
        ButtonPair(
            first = stringResource(R.string.instance_rename) to onRename,
            second = stringResource(R.string.instance_test_connection) to onTest,
        )
        testWords(test, host)?.let { outcome ->
            Text(
                text = outcome,
                style = FermixType.bodyMedium,
                color = LocalFermixColors.current.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Two tonal buttons side by side, each half the row, while each one's words fit its half on one line; else one over
 * the other, each the row's width, so a large type size never cuts or breaks them.
 */
@Composable
private fun ButtonPair(
    first: Pair<String, () -> Unit>,
    second: Pair<String, () -> Unit>,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val padding = ButtonDefaults.ContentPadding
        val ltr = LayoutDirection.Ltr
        val sides = padding.calculateStartPadding(ltr) + padding.calculateEndPadding(ltr)
        val room = with(LocalDensity.current) { ((maxWidth - BUTTON_GAP) / 2 - sides).roundToPx() }
        val fit =
            listOf(first, second).all { (words, _) ->
                measurer.measure(words, style, maxLines = 1).size.width <= room
            }
        if (fit) {
            Row(horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
                TonalButton(first.first, first.second, Modifier.weight(1f))
                TonalButton(second.first, second.second, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
                TonalButton(first.first, first.second, Modifier.fillMaxWidth())
                TonalButton(second.first, second.second, Modifier.fillMaxWidth())
            }
        }
    }
}

/** A tonal button (Material's filled tonal: the agent bubble's grey, the ink on it), 48 dp tall at least. */
@Composable
internal fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = FermixSpacing.minTarget).focusRing(FermixShapes.button),
    ) {
        Text(text = text, textAlign = TextAlign.Center)
    }
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

/**
 * The two switches on a card of the agent bubble's grey (RowsCard), a hairline between them, or the line that says
 * the daemon has no FCM in their place.
 */
@Composable
private fun NotificationsCard(
    ui: InstanceUi,
    actions: InstanceActions,
) {
    val colors = LocalFermixColors.current
    SectionHeader(stringResource(R.string.instance_section_notifications))
    if (!ui.record.pushReady) {
        val notSetUp = stringResource(R.string.instance_notifications_not_set_up, ui.record.host)
        val style = FermixType.body.copy(color = colors.textSecondary)
        RowsCard(listOf { KvRow(label = notSetUp, labelStyle = style, sides = CARD_ROW_SIDES) })
        return
    }
    val notifications = stringResource(R.string.instance_notifications)
    val previews = stringResource(R.string.instance_previews)
    RowsCard(
        listOf(
            { SwitchRow(notifications, ui.record.notificationsEnabled, actions.onNotifications, CARD_ROW_SIDES) },
            { SwitchRow(previews, ui.previews, actions.onPreviews, sides = CARD_ROW_SIDES) },
        ),
    )
}

/** "Unpair from {host}…", outlined in the ink: the way out, which its dialog asks about first. */
@Composable
private fun UnpairButton(
    host: String,
    onClick: () -> Unit,
) {
    val ink = LocalFermixColors.current.ink
    OutlinedButton(
        onClick = onClick,
        modifier =
            Modifier
                .padding(start = SIDES, end = SIDES, top = UNPAIR_TOP)
                .fillMaxWidth()
                .heightIn(min = FermixSpacing.minTarget)
                .focusRing(FermixShapes.button),
        border = BorderStroke(FermixSpacing.hairline, ink),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
    ) {
        Text(text = stringResource(R.string.instance_unpair_row, host), textAlign = TextAlign.Center)
    }
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
