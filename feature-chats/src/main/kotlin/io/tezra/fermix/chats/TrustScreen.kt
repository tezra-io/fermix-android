package io.tezra.fermix.chats

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.ColumnWidth
import io.tezra.fermix.design.FermixColumn
import io.tezra.fermix.design.FermixShapes
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.focusRing
import io.tezra.fermix.design.textButtonColors
import io.tezra.fermix.instance.BackBar

// The visual canon's full-screen states (`.fail`): 16 dp and 24 dp of padding, the icon's 72 dp disc 8 dp
// down and 28 dp above the title, its glyph 32 dp, the actions 6 dp apart at the foot, 24 dp under the title
// at the least.
private val PAGE_SIDES = 24.dp
private val PAGE_ENDS = 16.dp
private val DISC = 72.dp
private val GLYPH = 32.dp
private val DISC_TOP = 8.dp
private val DISC_BELOW = 28.dp
private val ACTION_GAP = 6.dp
private val ACTIONS_TOP = 24.dp

/** The two trust states that replace a chat until the owner decides (design sections 9.2 and 9.4). */
enum class Trust(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
) {
    /** `4003` live, or `4004` on a reconnect. */
    REVOKED(R.drawable.ic_chats_unlink, R.string.chats_revoked_title),

    /** The daemon's certificate or Noise key changed: reinstalled, or not the daemon paired. */
    IDENTITY_CHANGED(R.drawable.ic_chats_key, R.string.chats_identity_changed_title),
}

/**
 * [trust]'s full screen about [host]: its icon, its sentence, "Pair again" (which pairs and merges into the
 * row) and "Remove"; neither state is ever resolved by the app on its own.
 */
@Composable
fun TrustScreen(
    trust: Trust,
    host: String,
    onBack: () -> Unit,
    onPairAgain: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(onBack = onBack)
        FermixColumn(ColumnWidth.Narrow) {
            BoxWithConstraints {
                Column(
                    modifier =
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(horizontal = PAGE_SIDES, vertical = PAGE_ENDS),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .padding(top = DISC_TOP, bottom = DISC_BELOW)
                                .size(DISC)
                                .background(colors.agentBubble, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(trust.icon), null, modifier = Modifier.size(GLYPH), tint = colors.ink)
                    }
                    Text(text = stringResource(trust.title, host), style = FermixType.headline, color = colors.ink)
                    Spacer(modifier = Modifier.weight(1f))
                    Actions(onPairAgain = onPairAgain, onRemove = onRemove)
                }
            }
        }
    }
}

@Composable
private fun Actions(
    onPairAgain: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = ACTIONS_TOP), verticalArrangement = Arrangement.spacedBy(ACTION_GAP)) {
        Button(
            onClick = onPairAgain,
            modifier = Modifier.fillMaxWidth().heightIn(min = FermixSpacing.minTarget).focusRing(FermixShapes.button),
        ) {
            Text(text = stringResource(R.string.chats_pair_again))
        }
        TextButton(
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth().heightIn(min = FermixSpacing.minTarget).focusRing(FermixShapes.button),
            colors = textButtonColors(LocalFermixColors.current),
        ) {
            Text(text = stringResource(R.string.chats_remove))
        }
    }
}
