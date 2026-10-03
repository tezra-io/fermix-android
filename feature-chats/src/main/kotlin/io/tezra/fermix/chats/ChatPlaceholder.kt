package io.tezra.fermix.chats

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tezra.fermix.data.Instance
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixSpacing
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.instance.AvatarSize
import io.tezra.fermix.instance.InstanceAvatar
import io.tezra.fermix.instance.Link
import io.tezra.fermix.instance.dot
import io.tezra.fermix.instance.linkWords
import io.tezra.fermix.instance.tintColor

// The visual canon's chat bar (`.ab.tonal.line`): 64 dp on the tonal surface over a hairline, the tint's
// 2 dp line under it, 4 dp at the sides, 12 dp between the 32 dp avatar and the name.
private val BAR_HEIGHT = 64.dp
private val BAR_SIDES = 4.dp
private val WHO_GAP = 12.dp

/** The canon's `.nm b`, 600 16/22, and `.nm span`, 400 12/16. */
private val NAME_STYLE = FermixType.title.copy(lineHeight = 22.sp)
private val SUBTITLE_STYLE = FermixType.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** What a chat's app bar says under its title (design section 13.5). */
sealed interface ChatLine {
    /** The link's words: waiting, connecting, can't reach, updating, the path, or a state that ended. */
    data class Of(
        val link: Link,
    ) : ChatLine

    data object Thinking : ChatLine

    data object Nothing : ChatLine
}

/**
 * Section 13.5's subtitle priority: "Waiting for network…", "Connecting…", "Can't reach {host}" and
 * "Updating…" first, then "thinking…", then the path, "Tailscale · 38 ms" or "Wi-Fi · 9 ms"; a session that
 * ended says how.
 */
fun chatLine(
    link: Link,
    thinking: Boolean,
): ChatLine =
    when {
        link is Link.Up && link.caughtUp -> if (thinking) ChatLine.Thinking else ChatLine.Of(link)
        link == Link.NotOpen || link == Link.Closed -> if (thinking) ChatLine.Thinking else ChatLine.Nothing
        else -> ChatLine.Of(link)
    }

/** A chat's bar as it reads: [record]'s title, tint and host, its [link], and whether a turn is [thinking]. */
data class ChatHeader(
    val record: Instance,
    val link: Link,
    val thinking: Boolean,
)

/**
 * The Chat screen until it is built: its app bar as section 13.5 draws it, live (back, the 32 dp avatar
 * with its dot, the title and the subtitle; a tap on the title opens the Instance screen), over the canvas.
 */
@Composable
fun ChatPlaceholder(
    header: ChatHeader,
    onBack: () -> Unit,
    onTitle: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        val top = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        Column(modifier = Modifier.background(colors.tonal).windowInsetsPadding(top)) {
            ChatBar(header, onBack, onTitle)
            Spacer(modifier = Modifier.fillMaxWidth().height(FermixSpacing.hairline).background(colors.hairline))
            Spacer(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            FermixSpacing.tintLine,
                        ).background(tintColor(header.record.tint)),
            )
        }
        // The timeline and the composer come with the Chat screen; until then the canvas.
        Box(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ChatBar(
    header: ChatHeader,
    onBack: () -> Unit,
    onTitle: () -> Unit,
) {
    val colors = LocalFermixColors.current
    Row(
        // At least the canon's 64 dp, and taller as the font scale grows the name and the subtitle.
        modifier = Modifier.fillMaxWidth().heightIn(min = BAR_HEIGHT).padding(horizontal = BAR_SIDES),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_chats_back), stringResource(R.string.chats_back), tint = colors.ink)
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
    }
}

/**
 * The subtitle's words, cross-fading as they change (design section 13.10, item 4: "Wi-Fi · 9 ms" becomes
 * "Tailscale · 38 ms" mid-stream), on the working indicator's cross-fade, the one the design times; under
 * reduce-motion the words change at once.
 */
@Composable
private fun Subtitle(line: String?) {
    val colors = LocalFermixColors.current
    val fade: FiniteAnimationSpec<Float> =
        if (LocalReducedMotion.current) snap() else tween(FermixMotion.INDICATOR_CROSS_FADE_MILLIS)
    Crossfade(targetState = line, animationSpec = fade, label = "chat subtitle") { shown ->
        if (shown != null) {
            Text(
                text = shown,
                style = SUBTITLE_STYLE,
                color = colors.inkSecondary,
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
        ChatLine.Thinking -> stringResource(R.string.chats_thinking)
        ChatLine.Nothing -> null
    }
