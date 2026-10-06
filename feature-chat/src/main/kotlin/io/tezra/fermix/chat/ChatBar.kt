package io.tezra.fermix.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.instance.AvatarSize
import io.tezra.fermix.instance.InstanceAvatar
import io.tezra.fermix.instance.dot
import io.tezra.fermix.instance.linkWords
import io.tezra.fermix.instance.tintColor

// The visual canon's chat bar (`.ab.tonal.line`): 64 dp on the tonal surface over a hairline, the tint's 2 dp
// line under it, 4 dp at the sides, 12 dp between the 32 dp avatar and the name.
private val BAR_HEIGHT = 64.dp
private val BAR_SIDES = 4.dp
private val WHO_GAP = 12.dp

/** The canon's `.nm b`, 600 16/22, and `.nm span`, 400 12/16. */
private val NAME_STYLE = FermixType.title.copy(lineHeight = 22.sp)
private val SUBTITLE_STYLE = FermixType.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp)

/**
 * The chat's app bar (design section 13.5): back, the 32 dp avatar with its dot, the title and the subtitle,
 * and search at its end (design section 13.7); a tap on the title opens the Instance screen. Under it, the
 * tint's line.
 */
@Composable
internal fun ChatBar(
    header: ChatHeader,
    onBack: () -> Unit,
    onTitle: () -> Unit,
    onSearch: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Column(modifier = Modifier.background(colors.tonal)) {
        Row(
            // At least the canon's 64 dp, and taller as the font scale grows the name and the subtitle.
            modifier = Modifier.fillMaxWidth().heightIn(min = BAR_HEIGHT).padding(horizontal = BAR_SIDES),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_chat_back), stringResource(R.string.chat_back), tint = colors.ink)
            }
            Row(
                modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onTitle),
                horizontalArrangement = Arrangement.spacedBy(WHO_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InstanceAvatar(tint = header.record.tint, dot = header.link.dot, size = AvatarSize.SMALL)
                Column {
                    Text(
                        header.record.title,
                        style = NAME_STYLE,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Subtitle(subtitle(header))
                }
            }
            IconButton(onClick = onSearch) {
                Icon(
                    painterResource(R.drawable.ic_chat_search),
                    stringResource(R.string.chat_search),
                    tint = colors.ink,
                )
            }
        }
        Spacer(modifier = Modifier.fillMaxWidth().height(FermixSpacing.hairline).background(colors.hairline))
        Spacer(
            modifier = Modifier.fillMaxWidth().height(FermixSpacing.tintLine).background(tintColor(header.record.tint)),
        )
    }
}

/**
 * The subtitle's words, cross-fading as they change (design section 13.10, item 4: "Wi-Fi · 9 ms" becomes
 * "Tailscale · 38 ms" mid-stream), on the working indicator's cross-fade; under reduce-motion at once.
 */
@Composable
private fun Subtitle(line: String?) {
    val colors = LocalFermixColors.current
    val fade: FiniteAnimationSpec<Float> =
        if (LocalReducedMotion.current) snap() else tween(FermixMotion.INDICATOR_CROSS_FADE_MILLIS)
    Crossfade(targetState = line, animationSpec = fade, label = "chat subtitle") { shown ->
        if (shown != null) {
            Text(
                shown,
                style = SUBTITLE_STYLE,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun subtitle(header: ChatHeader): String? =
    when (val line = chatLine(header.link, header.thinking)) {
        is ChatLine.Of -> linkWords(line.link, header.record.host)
        ChatLine.Thinking -> stringResource(R.string.chat_thinking)
        ChatLine.Nothing -> null
    }

/**
 * The thin banner under the bar (design section 13.5): one line, offline, or the computer out of reach, whose
 * tap opens the Instance screen at its Connection section. The one that taps is at least 48 dp tall, as every
 * target is (section 13.8); the offline line, which does not, stays the canon's thin line.
 */
@Composable
internal fun BannerLine(
    banner: Banner,
    host: String,
    onUnreachable: () -> Unit,
) {
    val colors = LocalFermixColors.current
    val words =
        when (banner) {
            Banner.OFFLINE -> stringResource(R.string.chat_offline)
            Banner.UNREACHABLE -> stringResource(R.string.chat_unreachable, host)
        }
    val tap =
        when (banner) {
            Banner.UNREACHABLE -> {
                Modifier
                    .heightIn(min = FermixSpacing.minTarget)
                    .clickable(role = Role.Button, onClick = onUnreachable)
            }

            Banner.OFFLINE -> {
                Modifier
            }
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.agentBubble)
                .then(tap)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = words, style = FermixType.labelSmall, color = colors.textSecondary, textAlign = TextAlign.Center)
    }
}

/** The bar while messages are selected (design section 13.7, "Multi-select"): close, the count, Copy, Share. */
@Composable
internal fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    colors.tonal,
                ).heightIn(min = BAR_HEIGHT)
                .padding(horizontal = BAR_SIDES),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                painterResource(R.drawable.ic_chat_x),
                stringResource(R.string.chat_close_selection),
                tint = colors.ink,
            )
        }
        Text(
            selectedWords(count),
            style = NAME_STYLE,
            color = colors.ink,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        IconButton(onClick = onCopy) {
            Icon(
                painterResource(R.drawable.ic_chat_copy),
                stringResource(R.string.chat_copy_transcript),
                tint = colors.ink,
            )
        }
        IconButton(onClick = onShare) {
            Icon(painterResource(R.drawable.ic_chat_share), stringResource(R.string.chat_share), tint = colors.ink)
        }
    }
}
