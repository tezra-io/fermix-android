package io.tezra.fermix.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.tezra.fermix.design.FermixMotion
import io.tezra.fermix.design.FermixType
import io.tezra.fermix.design.LocalFermixColors
import io.tezra.fermix.design.LocalReducedMotion
import io.tezra.fermix.design.TwoDotMark
import io.tezra.fermix.session.IndicatorPools
import kotlinx.coroutines.delay

/** How often the card reads the clock for its phrase: well inside the 5 s and 8 s the phrases keep. */
private const val TICK_MILLIS = 1_000L

/** The headings the card shows: the latest two at 70 %, and the one before at 28 % as it leaves. */
private const val SHOWN_HEADINGS = 2
private const val HEADING_ALPHA = 0.7f
private const val OLD_HEADING_ALPHA = 0.28f

/** The working indicator's pools, as the owner edits them in the strings (design section 13.9). */
@Composable
internal fun indicatorPools(): IndicatorPools {
    val opening = stringResource(R.string.chat_indicator_opening)
    val first = stringArrayResource(R.array.chat_indicator_first)
    val second = stringArrayResource(R.array.chat_indicator_second)
    return remember(opening, first, second) { IndicatorPools(opening, first.toList(), second.toList()) }
}

/**
 * The thinking card (design sections 8.2 and 13.5): flat and borderless where the answer will be; the orbiting
 * mark and one shimmering line, "Thinking" or a phrase by the time since `accepted` (cardLine), cross-faded in
 * 200 ms (at once under reduce-motion); the daemon's latest two headings at 70 %; its tool chips. TalkBack hears
 * "Fermix is thinking" once, as the card appears, and nothing as the phrase changes; the headings are read when
 * the card is focused. [nowMono] is the clock the session's fold read the turn's start on.
 */
@Composable
internal fun ThinkingCard(
    item: ChatItem.Thinking,
    nowMono: () -> Long,
    modifier: Modifier = Modifier,
) {
    val pools = indicatorPools()
    var now by remember { mutableLongStateOf(nowMono()) }
    if (!LocalInspectionMode.current) {
        // Re-keyed by each tick, for as long as the card is in the list: never a loop of its own.
        LaunchedEffect(now) {
            delay(TICK_MILLIS)
            now = nowMono()
        }
    }
    val line = cardLine(item.card, item.startedMono, item.seed, now, pools)
    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IndicatorHeader(line)
        Headings(item.card.headings)
        if (item.card.chips.isNotEmpty()) ToolChips(item.card.chips, Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun IndicatorHeader(line: String) {
    val announcement = stringResource(R.string.chat_thinking_announcement)
    val fade: FiniteAnimationSpec<Float> =
        if (LocalReducedMotion.current) snap() else tween(FermixMotion.INDICATOR_CROSS_FADE_MILLIS)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        // TalkBack's one line, polite, said as the card appears: it never changes, so it is never said again.
        Box(
            modifier =
                Modifier.clearAndSetSemantics {
                    contentDescription = announcement
                    liveRegion = LiveRegionMode.Polite
                },
        ) { TwoDotMark() }
        val colors = LocalFermixColors.current
        val peak = rememberShimmerPeak()
        Crossfade(targetState = line, animationSpec = fade, label = "indicator line") { shown ->
            // The phrase is for the eye: hidden from TalkBack, so a new one says nothing.
            Text(
                shown,
                style = FermixType.label,
                color = colors.ink,
                modifier =
                    Modifier
                        .shimmer(peak, colors.inkTertiary, colors.ink)
                        .semantics { hideFromAccessibility() },
            )
        }
    }
}

/**
 * Where the shimmer's ink peaks, as a fraction of the line's width, read as the line draws: still under
 * reduce-motion and in a preview, at the canon's 45 %, over the words; otherwise sweeping across them every
 * 1.6 s, from that same place, so the frame before the sweep runs, which a screenshot holds, is the canon's.
 */
@Composable
private fun rememberShimmerPeak(): () -> Float {
    if (LocalReducedMotion.current || LocalInspectionMode.current) return { SHIMMER_PEAK }
    val sweep =
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = SHIMMER_PEAK,
            targetValue = SHIMMER_PEAK + SWEEP_TO - SWEEP_FROM,
            animationSpec =
                infiniteRepeatable(
                    tween(FermixMotion.THINKING_SHIMMER_MILLIS, easing = LinearEasing),
                    RepeatMode.Restart,
                ),
            label = "shimmer sweep",
        )
    return { inSweep(sweep.value) }
}

/** [value] brought into the sweep, from before the line to past it, where it goes on from the start. */
private fun inSweep(value: Float): Float = (value - SWEEP_FROM).mod(SWEEP_TO - SWEEP_FROM) + SWEEP_FROM

/**
 * The line's shimmer (the canon's `.think .hd span`), drawn over its own glyphs: the ink between two of the third
 * ink across the width the line measured, its peak at [peak], so it reads the same at any density, font scale
 * and phrase. The sweep is read as the line draws, so it redraws the line and recomposes nothing.
 */
private fun Modifier.shimmer(
    peak: () -> Float,
    edge: Color,
    ink: Color,
): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val start = (peak() - SHIMMER_PEAK) * size.width
            val gradient =
                Brush.horizontalGradient(
                    0f to edge,
                    SHIMMER_PEAK to ink,
                    SHIMMER_END to edge,
                    startX = start,
                    endX = start + size.width,
                )
            drawRect(gradient, blendMode = BlendMode.SrcIn)
        }

/** Where the peak starts and ends its sweep, as a fraction of the line: in from before it, out past it. */
private const val SWEEP_FROM = -0.25f
private const val SWEEP_TO = 1.25f

/** Where across the shimmer's gradient the ink peaks, and where it is back to the third ink (the canon's stops). */
private const val SHIMMER_PEAK = 0.45f
private const val SHIMMER_END = 0.9f

/**
 * The daemon's headings, a snapshot: the latest two at 70 %, the one before them at 28 %, and an older one
 * slides up and fades as a new one comes (design section 13.5).
 */
@Composable
private fun Headings(headings: List<String>) {
    val shown = headings.takeLast(SHOWN_HEADINGS + 1)
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            if (reduced) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
            }
        },
        label = "headings",
    ) { lines ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val old = lines.size > SHOWN_HEADINGS
            lines.forEachIndexed { index, heading ->
                val alpha = if (old && index == 0) OLD_HEADING_ALPHA else HEADING_ALPHA
                Text(heading, style = FermixType.bodyMedium, color = LocalFermixColors.current.ink.copy(alpha = alpha))
            }
        }
    }
}
