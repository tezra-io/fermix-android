package io.tezra.fermix.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.instance.FermixMark

// The visual canon's lock (`.lk`): centred, 24 dp of padding, 16 dp between the mark, the headline and
// "Unlock", each 12 dp further down; its App lock setting: a 64 dp bar with the title 4 dp past the back
// button, and one `.kv` row, 24 dp at the sides.
private val LOCK_PADDING = 24.dp
private val LOCK_GAP = 16.dp
private val LOCK_STEP = 12.dp
private val BAR_HEIGHT = 64.dp
private val BAR_SIDES = 4.dp
private val TITLE_START = 4.dp
private val ROW_SIDES = 24.dp
private val ROW_ENDS = 4.dp

/**
 * "Fermix is locked" and "Unlock" (design sections 13.7 and 13.9), on the canvas, in place of everything
 * while the lock holds.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val colors = LocalFermixColors.current
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.canvas)
                .safeDrawingPadding()
                .verticalScroll(
                    rememberScrollState(),
                ).padding(LOCK_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LOCK_GAP, Alignment.CenterVertically),
    ) {
        FermixMark()
        Text(
            text = stringResource(R.string.chats_locked),
            style = FermixType.headline,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = LOCK_STEP),
        )
        Button(
            onClick = onUnlock,
            modifier = Modifier.padding(top = LOCK_STEP).heightIn(min = FermixSpacing.minTarget),
        ) {
            Text(text = stringResource(R.string.chats_unlock))
        }
    }
}

/**
 * The App lock setting (design section 13.7), the Chats list's overflow's one entry: "Lock with biometrics",
 * which asks for `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` and hides the recents preview. A phone with no screen
 * lock cannot hold it, and says so under the switch, which it leaves off.
 */
@Composable
fun AppLockScreen(
    on: Boolean,
    available: Boolean,
    onBack: () -> Unit,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalFermixColors.current
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = BAR_SIDES),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_chats_back), stringResource(R.string.chats_back), tint = colors.ink)
            }
            Text(
                text = stringResource(R.string.chats_app_lock),
                style = BAR_TITLE,
                color = colors.ink,
                modifier = Modifier.padding(start = TITLE_START),
            )
        }
        FermixColumn(ColumnWidth.Narrow) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LockSwitch(on = on && available, enabled = available, onChange = onChange)
                if (!available) {
                    Text(
                        text = stringResource(R.string.chats_lock_needs_screen_lock),
                        style = FermixType.bodyMedium,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(horizontal = ROW_SIDES),
                    )
                }
            }
        }
    }
}

@Composable
private fun LockSwitch(
    on: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .heightIn(min = FermixSpacing.minTarget)
                .padding(horizontal = ROW_SIDES, vertical = ROW_ENDS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chats_lock_with_biometrics),
            style = FermixType.body,
            color = LocalFermixColors.current.ink,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = on, onCheckedChange = null, enabled = enabled)
    }
}
